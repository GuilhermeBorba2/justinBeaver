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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The whole workbench against a real PostgreSQL 16 (same major as the local pg_dump client).
 * Run with {@code mvn test -Djustinbeaver.it.docker=true}; needs Docker and network.
 */
@EnabledIfSystemProperty(named = "justinbeaver.it.docker", matches = "true")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class PostgresRealTest extends RealDatabaseSupport {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        storage(registry, "pg");
    }

    @TempDir
    Path tmp;

    @Test
    void workbenchAgainstPostgres() throws Exception {
        id = connect("postgres-jdbc", PG.getHost(), PG.getFirstMappedPort(), PG.getDatabaseName(), PG.getUsername(), PG.getPassword());
        script("""
                CREATE TABLE dept(id serial PRIMARY KEY, name varchar(30) NOT NULL);
                CREATE TABLE emp(id integer PRIMARY KEY, dept_id integer REFERENCES dept(id), salary numeric(10,2) DEFAULT 0,
                                 hired date, active boolean, photo bytea, meta jsonb);
                CREATE INDEX idx_salary ON emp(salary);
                CREATE VIEW rich AS SELECT id FROM emp WHERE salary > 100;
                CREATE SEQUENCE order_seq START 100 INCREMENT 5;
                CREATE FUNCTION add_one(i integer) RETURNS integer LANGUAGE sql AS $$ SELECT i + 1 $$;
                CREATE FUNCTION touch() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE NOTICE 'touched %', NEW.id; RETURN NEW; END $$;
                CREATE TRIGGER emp_touch BEFORE INSERT ON emp FOR EACH ROW EXECUTE FUNCTION touch();
                INSERT INTO dept(name) VALUES ('eng'), ('ops');
                INSERT INTO emp VALUES (1, 1, 50, '2020-01-01', true, '\\x89504e470d0a1a0a', null),
                                       (2, 1, 500, null, false, null, '{"a": 1}')
                """);

        // server output (RAISE NOTICE), also from a trigger
        String notice = postJsonOk("/api/sessions/{id}/query", Map.of("sql", "DO $$ BEGIN RAISE NOTICE 'hello %', 42; END $$"), id);
        assertThat((List<String>) JsonPath.read(notice, "$.messages")).contains("hello 42");

        // navigator, details, DDL
        Map<String, String> pub = Map.of("schema", "public");
        assertThat((List<String>) JsonPath.read(getOk("/api/sessions/{id}/tables", pub, id), "$[*].name")).contains("dept", "emp", "rich");
        String emp = getOk("/api/sessions/{id}/table", Map.of("schema", "public", "name", "emp"), id);
        assertThat((List<String>) JsonPath.read(emp, "$.columns[?(@.primaryKey == true)].name")).containsExactly("id");
        assertThat((List<String>) JsonPath.read(emp, "$.foreignKeys[*].refTable")).containsExactly("dept");
        String dept = getOk("/api/sessions/{id}/table", Map.of("schema", "public", "name", "dept"), id);
        assertThat((List<String>) JsonPath.read(dept, "$.referencedBy[*].refTable")).containsExactly("emp");
        String ddl = JsonPath.read(getOk("/api/sessions/{id}/ddl", Map.of("schema", "public", "name", "emp"), id), "$.ddl");
        assertThat(ddl).contains("CREATE TABLE \"public\".\"emp\"", "numeric(10, 2)", "FOREIGN KEY (\"dept_id\")", "CREATE INDEX \"idx_salary\"");
        String viewDdl = JsonPath.read(getOk("/api/sessions/{id}/ddl", Map.of("schema", "public", "name", "rich"), id), "$.ddl");
        assertThat(viewDdl).startsWith("CREATE OR REPLACE VIEW");

        // data: server-side paging, count, explain
        String page = getOk("/api/sessions/{id}/data",
                Map.of("schema", "public", "table", "emp", "limit", "1", "offset", "1", "orderBy", "id"), id);
        assertThat((Integer) JsonPath.read(page, "$.rows[0][0]")).isEqualTo(2);
        assertThat((Boolean) JsonPath.read(page, "$.hasMore")).isFalse();
        assertThat((Integer) JsonPath.read(getOk("/api/sessions/{id}/count",
                Map.of("schema", "public", "table", "emp", "where", "salary > 100"), id), "$.count")).isEqualTo(1);
        assertThat((List<?>) JsonPath.read(postJsonOk("/api/sessions/{id}/explain", Map.of("sql", "SELECT * FROM emp WHERE id = 1"), id),
                "$.rows")).isNotEmpty();

        // edits with type conversion (numeric, date, boolean, jsonb through Types.OTHER)
        postJsonOk("/api/sessions/{id}/edits/apply", Map.of("schema", "public", "table", "emp", "changes", List.of(
                Map.of("op", "UPDATE", "key", Map.of("id", 1), "values",
                        Map.of("salary", "75.50", "hired", "2021-02-03", "active", "false", "meta", "{\"b\": 2}")),
                Map.of("op", "INSERT", "values", Map.of("id", "3", "dept_id", "2", "salary", "10")),
                Map.of("op", "DELETE", "key", Map.of("id", 2)))), id);
        assertThat(scalar("SELECT salary::text || '|' || hired::text || '|' || active::text || '|' || (meta->>'b') FROM emp WHERE id = 1"))
                .isEqualTo("75.50|2021-02-03|false|2");
        assertThat(scalar("SELECT count(*) FROM emp")).isEqualTo(2);

        // full cell content with mime detection (bytea)
        String cell = postJsonOk("/api/sessions/{id}/cell",
                Map.of("schema", "public", "table", "emp", "column", "photo", "key", Map.of("id", 1)), id);
        assertThat((String) JsonPath.read(cell, "$.mime")).isEqualTo("image/png");

        // routines, sequences, triggers and their source
        String objects = getOk("/api/sessions/{id}/objects", pub, id);
        assertThat((List<String>) JsonPath.read(objects, "$.functions[*].name")).contains("add_one");
        assertThat((List<String>) JsonPath.read(objects, "$.sequences[*].name")).contains("order_seq", "dept_id_seq");
        assertThat((List<String>) JsonPath.read(objects, "$.triggers[*].name")).containsExactly("emp_touch");
        assertThat((String) JsonPath.read(getOk("/api/sessions/{id}/source",
                Map.of("schema", "public", "kind", "FUNCTION", "name", "add_one"), id), "$.source")).contains("CREATE OR REPLACE FUNCTION");
        assertThat((String) JsonPath.read(getOk("/api/sessions/{id}/source",
                Map.of("schema", "public", "kind", "TRIGGER", "name", "emp_touch"), id), "$.source")).contains("CREATE TRIGGER emp_touch");
        assertThat((String) JsonPath.read(getOk("/api/sessions/{id}/source",
                Map.of("schema", "public", "kind", "SEQUENCE", "name", "order_seq"), id), "$.source")).contains("START WITH 100", "INCREMENT BY 5");
        assertThat((List<?>) JsonPath.read(getOk("/api/sessions/{id}/privileges", Map.of("schema", "public", "name", "emp"), id), "$"))
                .isNotEmpty();

        // completion and search
        assertThat((List<String>) JsonPath.read(getOk("/api/sessions/{id}/completion", Map.of(), id), "$.schemas.public.emp"))
                .contains("salary");
        assertThat((String) JsonPath.read(getOk("/api/sessions/{id}/search", Map.of("q", "em"), id), "$[0].name")).isEqualTo("emp");

        // administration
        assertThat((String) JsonPath.read(getOk("/api/sessions/{id}/admin/sessions", Map.of(), id), "$.columns[0].name")).isEqualTo("pid");
        getOk("/api/sessions/{id}/admin/locks", Map.of(), id);
        assertThat((List<String>) JsonPath.read(getOk("/api/sessions/{id}/admin/metrics", Map.of(), id), "$[*].name"))
                .contains("connections", "transactions", "cache_hit");

        // native backup with pg_dump, restore with pg_restore into a second database
        Path dump = tmp.resolve("shop.dump");
        Map<String, Object> backup = waitForJob(postJsonOk("/api/connections/{id}/backup", Map.of("file", dump.toString()), id));
        assertThat(backup.get("status")).as("%s", backup.get("log")).isEqualTo("SUCCEEDED");
        assertThat(Files.size(dump)).isGreaterThan(1000);
        script("CREATE DATABASE restored");
        String main = id;
        id = connect("postgres-jdbc", PG.getHost(), PG.getFirstMappedPort(), "restored", PG.getUsername(), PG.getPassword());
        Map<String, Object> restore = waitForJob(postJsonOk("/api/connections/{id}/restore", Map.of("file", dump.toString()), id));
        assertThat(restore.get("status")).as("%s", restore.get("log")).isEqualTo("SUCCEEDED");
        assertThat(scalar("SELECT count(*) FROM emp")).isEqualTo(2);
        id = main;

        // query results from one table with its key are editable in place (pgjdbc reports no schema: resolved)
        String editable = postJsonOk("/api/sessions/{id}/query", Map.of("sql", "SELECT id, salary, salary + 1 AS next FROM emp"), id);
        assertThat((String) JsonPath.read(editable, "$.source.schema")).isEqualTo("public");
        assertThat((String) JsonPath.read(editable, "$.source.table")).isEqualTo("emp");
        assertThat((List<String>) JsonPath.read(editable, "$.source.columns")).containsExactly("id", "salary", null);

        // "Show all databases": other databases of the server open their own session on demand
        String all = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/connections")
                        .header("X-JB-Client", "it").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "all", "driverId", "postgres-jdbc", "host", PG.getHost(), "port", PG.getFirstMappedPort(),
                                "database", PG.getDatabaseName(), "user", PG.getUsername(), "password", PG.getPassword(),
                                "showAllDatabases", true))))
                .andReturn().getResponse().getContentAsString();
        String allId = JsonPath.read(all, "$.id");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/connections/{id}/connect", allId)
                .header("X-JB-Client", "it"));
        String databases = getOk("/api/sessions/{id}/databases", Map.of(), allId);
        assertThat((List<String>) JsonPath.read(databases, "$.databases")).contains(PG.getDatabaseName(), "restored", "postgres");
        assertThat((String) JsonPath.read(databases, "$.current")).isEqualTo(PG.getDatabaseName());
        assertThat((List<String>) JsonPath.read(getOk("/api/sessions/{id}/tables", Map.of("schema", "public"), allId + "::restored"),
                "$[*].name")).contains("emp");

        // every kind of statement typed in the SQL editor
        for (String sql : List.of(
                "UPDATE emp SET salary = salary WHERE id = 1",
                "UPDATE emp SET salary = salary WHERE id = 1 RETURNING id",
                "WITH u AS (UPDATE emp SET salary = salary RETURNING *) SELECT count(*) FROM u",
                "MERGE INTO emp t USING (SELECT 1 AS id) s ON t.id = s.id WHEN MATCHED THEN UPDATE SET salary = t.salary",
                "INSERT INTO emp(id) VALUES (99)",
                "DELETE FROM emp WHERE id = 99",
                "SELECT * FROM emp FOR UPDATE",
                "-- comment first\nUPDATE emp SET salary = salary WHERE id = 1",
                "SET search_path TO public",
                "SHOW search_path",
                "VACUUM emp",
                "ANALYZE emp",
                "CREATE INDEX CONCURRENTLY idx_tmp ON emp(hired)",
                "DROP INDEX CONCURRENTLY idx_tmp")) {
            String json = postJson("/api/sessions/{id}/query", Map.of("sql", sql), id);
            assertThat(json).as(sql).doesNotContain("\"status\":4").doesNotContain("\"status\":5");
        }

        // a legacy table without primary key: rows matched by all their values (NULLs with IS NULL)
        script("CREATE TABLE legacy_turma(cod bigint, nome varchar(20), criado timestamp); "
                + "INSERT INTO legacy_turma VALUES (10000000069038, 'a', '2024-03-06 12:11:01.314'), (2, NULL, NULL)");
        String legacy = postJsonOk("/api/sessions/{id}/query", Map.of("sql", "SELECT * FROM legacy_turma ORDER BY cod"), id);
        assertThat((String) JsonPath.read(legacy, "$.source.keyKind")).isEqualTo("ALL_COLUMNS");
        assertThat((String) JsonPath.read(legacy, "$.rows[1][2]")).isEqualTo("2024-03-06 12:11:01.314");
        Map<String, Object> nullKey = new java.util.HashMap<>(Map.of("cod", 2));
        nullKey.put("nome", null);
        nullKey.put("criado", null);
        postJsonOk("/api/sessions/{id}/edits/apply", Map.of("schema", "public", "table", "legacy_turma", "changes",
                List.of(Map.of("op", "UPDATE", "key", nullKey, "values", Map.of("nome", "b")))), id);
        postJsonOk("/api/sessions/{id}/edits/apply", Map.of("schema", "public", "table", "legacy_turma", "changes",
                List.of(Map.of("op", "UPDATE", "key", Map.of("cod", 10000000069038L, "nome", "a", "criado", "2024-03-06 12:11:01.314"),
                        "values", Map.of("nome", "c")))), id);
        assertThat(scalar("SELECT string_agg(nome, ',' ORDER BY cod) FROM legacy_turma")).isEqualTo("b,c");

        // the statements the UI's DDL generator emits for PostgreSQL (frontend/src/lib/ddl.ts) run as is
        script("""
                ALTER TABLE "public"."emp" ADD COLUMN "note" text DEFAULT 'n/a';
                ALTER TABLE "public"."emp" RENAME COLUMN "note" TO "remark";
                ALTER TABLE "public"."emp" ALTER COLUMN "remark" TYPE varchar(100);
                ALTER TABLE "public"."emp" ALTER COLUMN "remark" SET NOT NULL;
                ALTER TABLE "public"."emp" ALTER COLUMN "remark" DROP NOT NULL;
                ALTER TABLE "public"."emp" ALTER COLUMN "remark" DROP DEFAULT;
                ALTER TABLE "public"."emp" ALTER COLUMN "remark" SET DEFAULT 'x';
                CREATE INDEX "idx_emp_remark" ON "public"."emp" ("remark");
                DROP INDEX "public"."idx_emp_remark";
                ALTER TABLE "public"."emp" DROP COLUMN "remark";
                ALTER TABLE "public"."dept" RENAME TO "department";
                ALTER TABLE "public"."emp" ADD CONSTRAINT "fk2" FOREIGN KEY ("dept_id") REFERENCES "public"."department" ("id") ON DELETE CASCADE;
                ALTER TABLE "public"."emp" DROP CONSTRAINT "fk2";
                DROP VIEW "public"."rich";
                TRUNCATE TABLE "public"."emp";
                DROP TABLE "public"."emp" CASCADE
                """);
    }
}
