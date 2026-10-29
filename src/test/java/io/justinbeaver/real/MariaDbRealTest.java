package io.justinbeaver.real;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The whole workbench against a real MariaDB 10.11 (same version as the local mysqldump client).
 * Run with {@code mvn test -Djustinbeaver.it.docker=true}; needs Docker and network.
 */
@EnabledIfSystemProperty(named = "justinbeaver.it.docker", matches = "true")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class MariaDbRealTest extends RealDatabaseSupport {

    @Container
    static final MariaDBContainer<?> DB = new MariaDBContainer<>("mariadb:10.11").withUsername("root").withPassword("rootpw");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        storage(registry, "maria");
    }

    @TempDir
    Path tmp;

    @Test
    void workbenchAgainstMariaDb() throws Exception {
        id = connect("mariadb-jdbc", DB.getHost(), DB.getFirstMappedPort(), DB.getDatabaseName(), DB.getUsername(), DB.getPassword());
        String db = DB.getDatabaseName();
        script("""
                CREATE TABLE dept(id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(30) NOT NULL);
                CREATE TABLE emp(id INT PRIMARY KEY, dept_id INT, salary DECIMAL(10,2) DEFAULT 0, hired DATE, active BOOLEAN,
                                 photo BLOB, meta JSON, CONSTRAINT fk_dept FOREIGN KEY (dept_id) REFERENCES dept(id));
                CREATE INDEX idx_salary ON emp(salary);
                CREATE VIEW rich AS SELECT id FROM emp WHERE salary > 100;
                CREATE SEQUENCE order_seq START WITH 100 INCREMENT BY 5;
                CREATE FUNCTION add_one(i INT) RETURNS INT DETERMINISTIC RETURN i + 1;
                CREATE TRIGGER emp_touch BEFORE INSERT ON emp FOR EACH ROW SET NEW.salary = COALESCE(NEW.salary, 0);
                INSERT INTO dept(name) VALUES ('eng'), ('ops');
                INSERT INTO emp VALUES (1, 1, 50, '2020-01-01', TRUE, X'89504E470D0A1A0A', NULL),
                                       (2, 1, 500, NULL, FALSE, NULL, '{"a": 1}')
                """);

        // server output: warnings
        String warn = postJsonOk("/api/sessions/{id}/query", Map.of("sql", "SELECT 1/0"), id);
        assertThat(String.join(" ", (List<String>) JsonPath.read(warn, "$.messages")).toLowerCase()).contains("division by 0");

        // databases are catalogs here
        Map<String, String> scope = Map.of("catalog", db);
        assertThat((List<String>) JsonPath.read(getOk("/api/sessions/{id}/tables", scope, id), "$[*].name")).contains("dept", "emp", "rich");
        String emp = getOk("/api/sessions/{id}/table", Map.of("catalog", db, "name", "emp"), id);
        assertThat((List<String>) JsonPath.read(emp, "$.foreignKeys[*].refTable")).containsExactly("dept");
        String ddl = JsonPath.read(getOk("/api/sessions/{id}/ddl", Map.of("catalog", db, "name", "emp"), id), "$.ddl");
        assertThat(ddl).contains("CREATE TABLE `emp`", "CONSTRAINT `fk_dept` FOREIGN KEY");

        String page = getOk("/api/sessions/{id}/data", Map.of("catalog", db, "table", "emp", "limit", "1", "offset", "1", "orderBy", "id"), id);
        assertThat((Integer) JsonPath.read(page, "$.rows[0][0]")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(getOk("/api/sessions/{id}/count",
                Map.of("catalog", db, "table", "emp", "where", "salary > 100"), id), "$.count")).isEqualTo(1);
        assertThat((List<?>) JsonPath.read(postJsonOk("/api/sessions/{id}/explain", Map.of("sql", "SELECT * FROM emp"), id), "$.rows"))
                .isNotEmpty();

        // edits: BOOLEAN is TINYINT(1); the grid sends true/false back
        postJsonOk("/api/sessions/{id}/edits/apply", Map.of("catalog", db, "table", "emp", "changes", List.of(
                Map.of("op", "UPDATE", "key", Map.of("id", 1), "values", Map.of("salary", "75.50", "hired", "2021-02-03", "active", false)),
                Map.of("op", "INSERT", "values", Map.of("id", "3", "dept_id", "2", "active", true)),
                Map.of("op", "DELETE", "key", Map.of("id", 2)))), id);
        assertThat(scalar("SELECT CONCAT(salary, '|', hired, '|', active) FROM emp WHERE id = 1")).isEqualTo("75.50|2021-02-03|0");
        assertThat(scalar("SELECT COUNT(*) FROM emp")).isEqualTo(2);

        String cell = postJsonOk("/api/sessions/{id}/cell", Map.of("catalog", db, "table", "emp", "column", "photo", "key", Map.of("id", 1)), id);
        assertThat((String) JsonPath.read(cell, "$.mime")).isEqualTo("image/png");

        String objects = getOk("/api/sessions/{id}/objects", scope, id);
        assertThat((List<String>) JsonPath.read(objects, "$..name")).contains("add_one", "order_seq", "emp_touch");
        assertThat((String) JsonPath.read(getOk("/api/sessions/{id}/source",
                Map.of("catalog", db, "kind", "FUNCTION", "name", "add_one"), id), "$.source")).contains("FUNCTION `add_one`");
        assertThat((String) JsonPath.read(getOk("/api/sessions/{id}/source",
                Map.of("catalog", db, "kind", "TRIGGER", "name", "emp_touch"), id), "$.source")).contains("TRIGGER");
        getOk("/api/sessions/{id}/privileges", Map.of("catalog", db, "name", "emp"), id);

        assertThat((List<String>) JsonPath.read(getOk("/api/sessions/{id}/completion", Map.of(), id), "$.schemas." + db + ".emp"))
                .contains("salary");
        assertThat((List<String>) JsonPath.read(getOk("/api/sessions/{id}/admin/sessions", Map.of(), id), "$.rows[*][0]")).isNotEmpty();
        getOk("/api/sessions/{id}/admin/locks", Map.of(), id);
        assertThat((List<String>) JsonPath.read(getOk("/api/sessions/{id}/admin/metrics", Map.of(), id), "$[*].name"))
                .contains("connections", "queries");

        // mysqldump, then restore into a second database with the mysql client
        Path dump = tmp.resolve("shop.sql");
        Map<String, Object> backup = waitForJob(postJsonOk("/api/connections/{id}/backup", Map.of("file", dump.toString()), id));
        assertThat(backup.get("status")).as("%s", backup.get("log")).isEqualTo("SUCCEEDED");
        assertThat(Files.readString(dump)).contains("CREATE TABLE `emp`");
        script("CREATE DATABASE restored");
        String main = id;
        id = connect("mariadb-jdbc", DB.getHost(), DB.getFirstMappedPort(), "restored", DB.getUsername(), DB.getPassword());
        Map<String, Object> restore = waitForJob(postJsonOk("/api/connections/{id}/restore", Map.of("file", dump.toString()), id));
        assertThat(restore.get("status")).as("%s", restore.get("log")).isEqualTo("SUCCEEDED");
        assertThat(scalar("SELECT COUNT(*) FROM emp")).isEqualTo(2);
        id = main;

        // statements the UI's DDL generator emits for MariaDB (frontend/src/lib/ddl.ts)
        String t = "`" + db + "`.`emp`";
        script("""
                ALTER TABLE %1$s ADD COLUMN `note` TEXT DEFAULT 'n/a';
                ALTER TABLE %1$s RENAME COLUMN `note` TO `remark`;
                ALTER TABLE %1$s MODIFY COLUMN `remark` VARCHAR(100) DEFAULT 'x' NOT NULL;
                CREATE INDEX `idx_emp_remark` ON %1$s (`remark`);
                DROP INDEX `idx_emp_remark` ON %1$s;
                ALTER TABLE %1$s DROP COLUMN `remark`;
                RENAME TABLE `%2$s`.`dept` TO `%2$s`.`department`;
                ALTER TABLE %1$s ADD CONSTRAINT `fk2` FOREIGN KEY (`dept_id`) REFERENCES `%2$s`.`department` (`id`) ON DELETE CASCADE;
                ALTER TABLE %1$s DROP FOREIGN KEY `fk2`;
                DROP VIEW `%2$s`.`rich`;
                TRUNCATE TABLE %1$s;
                DROP TABLE %1$s
                """.formatted(t, db));
    }
}
