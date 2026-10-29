package io.justinbeaver.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The whole path through the HTTP API against a real JDBC driver: H2 is declared as a user driver
 * (extra-dir descriptor) pointing at its JAR, so it is loaded in an isolated class loader like any other.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SessionFlowTest {

    static Path storageDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        storageDir = Files.createTempDirectory("jb-storage");
        Path driversDir = Files.createDirectories(storageDir.resolve("drivers"));
        String h2Jar = Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Files.writeString(driversDir.resolve("h2.yml"), """
                drivers:
                  - id: h2-test
                    label: H2
                    className: org.h2.Driver
                    sampleUrl: "jdbc:h2:mem:{database}"
                    libraries:
                      - file: "%s"
                """.formatted(h2Jar));
        registry.add("justinbeaver.storage.dir", () -> storageDir.toString());
        registry.add("justinbeaver.drivers.extra-dir", () -> driversDir.toString());
    }

    @Autowired
    MockMvc mvc;

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.header("X-JB-Client", "test").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder action(MockHttpServletRequestBuilder request) {
        return request.header("X-JB-Client", "test");
    }

    @Test
    void fullSessionLifecycle() throws Exception {
        // create: password is stored encrypted and never returned
        String created = mvc.perform(json(post("/api/connections"), """
                        {"name":"h2 flow","driverId":"h2-test","database":"flow","user":"sa","password":"secret"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.connected").value(false))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        assertThat(Files.readString(storageDir.resolve("connections.json"))).doesNotContain("secret");

        // not connected yet
        mvc.perform(get("/api/sessions/{id}/containers", id)).andExpect(status().isConflict());

        mvc.perform(action(post("/api/connections/{id}/connect", id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true));

        // script: DDL + DML + query, each reported separately
        mvc.perform(json(post("/api/sessions/{id}/script", id), """
                        {"sql":"CREATE TABLE person(id INT PRIMARY KEY, name VARCHAR(50)); \
                        INSERT INTO person VALUES (1,'Ana'),(2,'Bob'); SELECT * FROM person ORDER BY id"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[1].result.updateCount").value(2))
                .andExpect(jsonPath("$[2].result.rows[0][1]").value("Ana"))
                .andExpect(jsonPath("$[2].result.columns[0].kind").value("number"));

        // navigator
        mvc.perform(get("/api/sessions/{id}/containers", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.schema=='PUBLIC')]").isNotEmpty());
        mvc.perform(get("/api/sessions/{id}/tables", id).param("schema", "PUBLIC"))
                .andExpect(jsonPath("$[?(@.name=='PERSON')].type").value("BASE TABLE"));
        mvc.perform(get("/api/sessions/{id}/table", id).param("schema", "PUBLIC").param("name", "PERSON"))
                .andExpect(jsonPath("$.columns[0].name").value("ID"))
                .andExpect(jsonPath("$.columns[0].primaryKey").value(true))
                .andExpect(jsonPath("$.columns[1].primaryKey").value(false));

        // data tab: paging, sorting, filtering
        mvc.perform(get("/api/sessions/{id}/data", id).param("schema", "PUBLIC").param("table", "PERSON")
                        .param("limit", "1"))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.hasMore").value(true));
        mvc.perform(get("/api/sessions/{id}/data", id).param("schema", "PUBLIC").param("table", "PERSON")
                        .param("limit", "1").param("offset", "1"))
                .andExpect(jsonPath("$.rows[0][1]").value("Bob"))
                .andExpect(jsonPath("$.hasMore").value(false));
        mvc.perform(get("/api/sessions/{id}/data", id).param("schema", "PUBLIC").param("table", "PERSON")
                        .param("orderBy", "NAME").param("desc", "true"))
                .andExpect(jsonPath("$.rows[0][1]").value("Bob"));
        mvc.perform(get("/api/sessions/{id}/data", id).param("schema", "PUBLIC").param("table", "PERSON")
                        .param("where", "ID > 1"))
                .andExpect(jsonPath("$.rows.length()").value(1));

        // path input cannot smuggle SQL
        mvc.perform(get("/api/sessions/{id}/data", id).param("schema", "PUBLIC").param("table", "PERSON; DROP TABLE PERSON"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/sessions/{id}/data", id).param("schema", "PUBLIC").param("table", "PERSON")
                        .param("orderBy", "ID; DROP TABLE PERSON"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/sessions/{id}/data", id).param("schema", "PUBLIC").param("table", "PERSON")
                        .param("where", "1=1; DROP TABLE PERSON"))
                .andExpect(status().isBadRequest());

        // transactions
        mvc.perform(json(post("/api/sessions/{id}/autocommit", id), "{\"value\":false}"))
                .andExpect(jsonPath("$.autoCommit").value(false));
        mvc.perform(json(post("/api/sessions/{id}/query", id), "{\"sql\":\"INSERT INTO person VALUES (3,'Cy')\"}"))
                .andExpect(jsonPath("$.updateCount").value(1));
        mvc.perform(action(post("/api/sessions/{id}/rollback", id))).andExpect(status().isOk());
        mvc.perform(json(post("/api/sessions/{id}/query", id), "{\"sql\":\"SELECT COUNT(*) FROM person\"}"))
                .andExpect(jsonPath("$.rows[0][0]").value(2));

        // SQL errors are reported with their SQL state, not as 500
        mvc.perform(json(post("/api/sessions/{id}/query", id), "{\"sql\":\"SELECT * FROM nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.sqlState").value("42S02"));

        // maxRows truncates and says so
        mvc.perform(json(post("/api/sessions/{id}/query", id), "{\"sql\":\"SELECT * FROM person\",\"maxRows\":1}"))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.truncated").value(true));

        // editing without a password keeps it; settings changes drop the live session
        mvc.perform(json(put("/api/connections/{id}", id), """
                        {"name":"renamed","driverId":"h2-test","database":"flow","user":"sa"}"""))
                .andExpect(jsonPath("$.name").value("renamed"))
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.connected").value(false));
        mvc.perform(action(post("/api/connections/{id}/connect", id))).andExpect(jsonPath("$.connected").value(true));

        mvc.perform(action(post("/api/connections/{id}/disconnect", id))).andExpect(jsonPath("$.connected").value(false));
        mvc.perform(action(delete("/api/connections/{id}", id))).andExpect(status().isNoContent());
        mvc.perform(get("/api/connections")).andExpect(jsonPath("$.length()").value(0));
    }
}
