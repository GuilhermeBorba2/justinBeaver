package io.justinbeaver.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** DDL, row count, explain, autocomplete names, references and reconnect, against H2. */
@SpringBootTest
@AutoConfigureMockMvc
class WorkbenchFeaturesTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path storage = Files.createTempDirectory("jb-features");
        Path drivers = Files.createDirectories(storage.resolve("drivers"));
        String h2Jar = Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Files.writeString(drivers.resolve("h2.yml"), """
                drivers:
                  - id: h2-test
                    className: org.h2.Driver
                    sampleUrl: "jdbc:h2:mem:{database}"
                    libraries:
                      - file: "%s"
                """.formatted(h2Jar));
        registry.add("justinbeaver.storage.dir", storage::toString);
        registry.add("justinbeaver.drivers.extra-dir", drivers::toString);
    }

    @Autowired
    MockMvc mvc;

    String id;

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url, id).header("X-JB-Client", "test").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @BeforeEach
    void seed() throws Exception {
        String created = mvc.perform(post("/api/connections").header("X-JB-Client", "test").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"f\",\"driverId\":\"h2-test\",\"database\":\"f%d\",\"user\":\"sa\",\"password\":\"\"}"
                                .formatted(System.nanoTime())))
                .andReturn().getResponse().getContentAsString();
        id = JsonPath.read(created, "$.id");
        mvc.perform(post("/api/connections/{id}/connect", id).header("X-JB-Client", "test")).andExpect(status().isOk());
        postJson("/api/sessions/{id}/script", """
                {"sql":"CREATE TABLE dept(id INT PRIMARY KEY, name VARCHAR(30) NOT NULL); \
                CREATE TABLE emp(id INT PRIMARY KEY, dept_id INT, salary DECIMAL(10,2) DEFAULT 0, \
                CONSTRAINT fk_dept FOREIGN KEY (dept_id) REFERENCES dept(id)); \
                CREATE INDEX idx_salary ON emp(salary); \
                CREATE VIEW rich AS SELECT id FROM emp WHERE salary > 100; \
                INSERT INTO dept VALUES (1,'eng'),(2,'ops'); INSERT INTO emp VALUES (1,1,50),(2,1,500),(3,2,900)"}""")
                .andExpect(status().isOk());
    }

    @Test
    void generatesTableDdlFromMetadata() throws Exception {
        String ddl = JsonPath.read(mvc.perform(get("/api/sessions/{id}/ddl", id).param("schema", "PUBLIC").param("name", "EMP"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.ddl");

        assertThat(ddl).startsWith("CREATE TABLE \"PUBLIC\".\"EMP\" (")
                .contains("\"SALARY\" DECIMAL(10, 2)")
                .contains("PRIMARY KEY (\"ID\")")
                .contains("CONSTRAINT \"FK_DEPT\" FOREIGN KEY (\"DEPT_ID\") REFERENCES \"PUBLIC\".\"DEPT\" (\"ID\")")
                .contains("CREATE INDEX \"IDX_SALARY\" ON \"PUBLIC\".\"EMP\" (\"SALARY\");");
    }

    @Test
    void viewDdlComesFromInformationSchema() throws Exception {
        mvc.perform(get("/api/sessions/{id}/ddl", id).param("schema", "PUBLIC").param("name", "RICH"))
                .andExpect(jsonPath("$.ddl").value(containsString("CREATE VIEW \"PUBLIC\".\"RICH\" AS")));
    }

    @Test
    void countsRowsWithTheDataFilter() throws Exception {
        mvc.perform(get("/api/sessions/{id}/count", id).param("schema", "PUBLIC").param("table", "EMP"))
                .andExpect(jsonPath("$.count").value(3));
        mvc.perform(get("/api/sessions/{id}/count", id).param("schema", "PUBLIC").param("table", "EMP").param("where", "salary > 100"))
                .andExpect(jsonPath("$.count").value(2));
        mvc.perform(get("/api/sessions/{id}/count", id).param("schema", "PUBLIC").param("table", "EMP").param("where", "1=1; DROP TABLE EMP"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void explainsAQuery() throws Exception {
        postJson("/api/sessions/{id}/explain", "{\"sql\":\"SELECT * FROM emp WHERE salary > 100;\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(greaterThan(0)))
                .andExpect(jsonPath("$.rows[0][0]").value(containsString("EMP")));
    }

    @Test
    void completionListsTablesAndColumns() throws Exception {
        mvc.perform(get("/api/sessions/{id}/completion", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultSchema").value("PUBLIC"))
                .andExpect(jsonPath("$.schemas.PUBLIC.EMP[2]").value("SALARY"))
                .andExpect(jsonPath("$.schemas.INFORMATION_SCHEMA").doesNotExist());
    }

    @Test
    void tableDetailsIncludeIncomingReferences() throws Exception {
        mvc.perform(get("/api/sessions/{id}/table", id).param("schema", "PUBLIC").param("name", "DEPT"))
                .andExpect(jsonPath("$.referencedBy[0].refTable").value("EMP"))
                .andExpect(jsonPath("$.referencedBy[0].refColumn").value("DEPT_ID"))
                .andExpect(jsonPath("$.referencedBy[0].column").value("ID"));
    }

    @Test
    void describesTheDatabaseForDdlGeneration() throws Exception {
        mvc.perform(get("/api/sessions/{id}/info", id))
                .andExpect(jsonPath("$.dialect").value("h2"))
                .andExpect(jsonPath("$.identifierQuote").value("\""));
    }

    @Test
    void searchesObjectsByNameExactFirst() throws Exception {
        mvc.perform(get("/api/sessions/{id}/search", id).param("q", "e"))
                .andExpect(jsonPath("$[*].name", org.hamcrest.Matchers.hasItems("EMP", "DEPT")))
                .andExpect(jsonPath("$[?(@.name=='TABLES')]").isEmpty()); // INFORMATION_SCHEMA is skipped
        mvc.perform(get("/api/sessions/{id}/search", id).param("q", "emp"))
                .andExpect(jsonPath("$[0].name").value("EMP"))
                .andExpect(jsonPath("$[0].schema").value("PUBLIC"));
    }

    @Test
    void readsFullCellContentWithMimeGuess() throws Exception {
        postJson("/api/sessions/{id}/script", """
                {"sql":"CREATE TABLE doc(id INT PRIMARY KEY, img BLOB, body CLOB); \
                INSERT INTO doc VALUES (1, X'89504E470D0A1A0A0000', 'long text')"}""").andExpect(status().isOk());

        postJson("/api/sessions/{id}/cell", "{\"schema\":\"PUBLIC\",\"table\":\"DOC\",\"column\":\"IMG\",\"key\":{\"ID\":1}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mime").value("image/png"))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.base64").value("iVBORw0KGgoAAA=="));
        postJson("/api/sessions/{id}/cell", "{\"schema\":\"PUBLIC\",\"table\":\"DOC\",\"column\":\"BODY\",\"key\":{\"ID\":1}}")
                .andExpect(jsonPath("$.text").value("long text"));
        postJson("/api/sessions/{id}/cell", "{\"schema\":\"PUBLIC\",\"table\":\"DOC\",\"column\":\"BODY\",\"key\":{\"ID\":9}}")
                .andExpect(status().isConflict());
    }

    @Test
    void listsRoutinesAndSequencesWithSource() throws Exception {
        postJson("/api/sessions/{id}/script", """
                {"sql":"CREATE SEQUENCE order_seq START WITH 100 INCREMENT BY 5;                 CREATE ALIAS MY_ABS FOR 'java.lang.Math.abs(int)'"}""").andExpect(status().isOk());

        mvc.perform(get("/api/sessions/{id}/objects", id).param("schema", "PUBLIC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sequences[*].name", org.hamcrest.Matchers.hasItem("ORDER_SEQ")))
                // H2 reports Java aliases as procedures; either list is fine, but only once
                .andExpect(jsonPath("$..[?(@.name == 'MY_ABS')]", org.hamcrest.Matchers.hasSize(1)));
        mvc.perform(get("/api/sessions/{id}/source", id).param("schema", "PUBLIC").param("kind", "SEQUENCE").param("name", "ORDER_SEQ"))
                .andExpect(jsonPath("$.source").value(containsString("START WITH 100")))
                .andExpect(jsonPath("$.source").value(containsString("INCREMENT BY 5")));
        mvc.perform(get("/api/sessions/{id}/source", id).param("schema", "PUBLIC").param("kind", "TRIGGER").param("name", "NONE"))
                .andExpect(jsonPath("$.source").value(containsString("not available")));
        mvc.perform(get("/api/sessions/{id}/privileges", id).param("schema", "PUBLIC").param("name", "EMP"))
                .andExpect(status().isOk());
    }

    @Test
    void singleTableResultsWithTheirKeyAreEditable() throws Exception {
        postJson("/api/sessions/{id}/query", "{\"sql\":\"SELECT id, salary, salary * 2 AS doubled FROM emp ORDER BY id\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source.table").value("EMP"))
                .andExpect(jsonPath("$.source.schema").value("PUBLIC"))
                .andExpect(jsonPath("$.source.columns[0]").value("ID"))
                .andExpect(jsonPath("$.source.columns[1]").value("SALARY"))
                .andExpect(jsonPath("$.source.columns[2]").doesNotExist())
                .andExpect(jsonPath("$.source.primaryKey[0]").value("ID"));
        // without the key, or across tables: read-only
        postJson("/api/sessions/{id}/query", "{\"sql\":\"SELECT salary FROM emp\"}")
                .andExpect(jsonPath("$.source").doesNotExist());
        postJson("/api/sessions/{id}/query", "{\"sql\":\"SELECT e.id, d.name FROM emp e JOIN dept d ON d.id = e.dept_id\"}")
                .andExpect(jsonPath("$.source").doesNotExist());
    }

    @Test
    void tablesWithoutPrimaryKeyUseUniqueIndexOrAllColumns() throws Exception {
        postJson("/api/sessions/{id}/script", """
                {"sql":"CREATE TABLE legacy(a INT, b VARCHAR(10), c DOUBLE); \
                INSERT INTO legacy VALUES (1, 'x', 0.1), (1, 'x', 0.2), (2, NULL, 0.3); \
                CREATE TABLE coded(code VARCHAR(10) NOT NULL, v VARCHAR(10)); CREATE UNIQUE INDEX coded_code ON coded(code); \
                INSERT INTO coded VALUES ('k1', 'a')"}""").andExpect(status().isOk());

        // no key at all: every comparable column (floating point excluded) is the virtual key
        mvc.perform(get("/api/sessions/{id}/table", id).param("schema", "PUBLIC").param("name", "LEGACY"))
                .andExpect(jsonPath("$.rowKeyKind").value("ALL_COLUMNS"))
                .andExpect(jsonPath("$.rowKey.length()").value(2));
        postJson("/api/sessions/{id}/query", "{\"sql\":\"SELECT a, b, c FROM legacy\"}")
                .andExpect(jsonPath("$.source.keyKind").value("ALL_COLUMNS"));
        // a NULL key value matches with IS NULL
        postJson("/api/sessions/{id}/edits/apply", """
                {"schema":"PUBLIC","table":"LEGACY","changes":[{"op":"UPDATE","key":{"A":2,"B":null},"values":{"B":"y"}}]}""")
                .andExpect(status().isOk());
        // duplicate rows are never changed together
        postJson("/api/sessions/{id}/edits/apply", """
                {"schema":"PUBLIC","table":"LEGACY","changes":[{"op":"UPDATE","key":{"A":1,"B":"x"},"values":{"B":"z"}}]}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("matched 2 rows")));

        // a unique index is used when there is no primary key
        mvc.perform(get("/api/sessions/{id}/table", id).param("schema", "PUBLIC").param("name", "CODED"))
                .andExpect(jsonPath("$.rowKeyKind").value("UNIQUE_INDEX"))
                .andExpect(jsonPath("$.rowKey[0]").value("CODE"));
        postJson("/api/sessions/{id}/edits/apply", """
                {"schema":"PUBLIC","table":"CODED","changes":[{"op":"UPDATE","key":{"CODE":"k1"},"values":{"V":"b"}}]}""")
                .andExpect(status().isOk());
    }

    @Test
    void explainsWhyAResultIsReadOnly() throws Exception {
        postJson("/api/sessions/{id}/query", "{\"sql\":\"SELECT e.id, d.name FROM emp e JOIN dept d ON d.id = e.dept_id\"}")
                .andExpect(jsonPath("$.readOnlyReason").value(containsString("more than one table")));
        postJson("/api/sessions/{id}/query", "{\"sql\":\"SELECT salary FROM emp\"}")
                .andExpect(jsonPath("$.readOnlyReason").value(containsString("primary key ID")));
        postJson("/api/sessions/{id}/query", "{\"sql\":\"SELECT 1 + 1\"}")
                .andExpect(jsonPath("$.readOnlyReason").value(containsString("no column comes straight from a table")));
        postJson("/api/sessions/{id}/query", "{\"sql\":\"SELECT id, salary FROM emp\"}")
                .andExpect(jsonPath("$.readOnlyReason").doesNotExist())
                .andExpect(jsonPath("$.source.keyKind").value("PRIMARY_KEY"));
    }

    @Test
    void reconnectsAfterTheConnectionIsLost() throws Exception {
        postJson("/api/sessions/{id}/query", "{\"sql\":\"SHUTDOWN\"}");
        // The first call after the drop may fail (and marks the session lost); the next one reconnects.
        postJson("/api/sessions/{id}/query", "{\"sql\":\"SELECT 1\"}");
        postJson("/api/sessions/{id}/query", "{\"sql\":\"SELECT 1\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0][0]").value(1));
    }
}
