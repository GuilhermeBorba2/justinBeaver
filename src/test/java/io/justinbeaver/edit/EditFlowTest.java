package io.justinbeaver.edit;

import static org.assertj.core.api.Assertions.assertThat;
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

/** Grid editing end to end against H2 loaded as a user driver. */
@SpringBootTest
@AutoConfigureMockMvc
class EditFlowTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path storage = Files.createTempDirectory("jb-edit");
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

    private Object scalar(String sql) throws Exception {
        String json = postJson("/api/sessions/{id}/query", "{\"sql\":\"%s\"}".formatted(sql))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.rows[0][0]");
    }

    private static String edits(String changes) {
        return "{\"schema\":\"PUBLIC\",\"table\":\"ITEM\",\"changes\":[%s]}".formatted(changes);
    }

    @BeforeEach
    void connectAndSeed() throws Exception {
        String created = mvc.perform(post("/api/connections").header("X-JB-Client", "test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"edit\",\"driverId\":\"h2-test\",\"database\":\"edit%d\",\"user\":\"sa\",\"password\":\"\"}"
                                .formatted(System.nanoTime())))
                .andReturn().getResponse().getContentAsString();
        id = JsonPath.read(created, "$.id");
        mvc.perform(post("/api/connections/{id}/connect", id).header("X-JB-Client", "test")).andExpect(status().isOk());
        postJson("/api/sessions/{id}/script", """
                {"sql":"CREATE TABLE item(id INT PRIMARY KEY, name VARCHAR(40) NOT NULL, price DECIMAL(10,2), born DATE, active BOOLEAN); \
                CREATE TABLE nokey(a INT); \
                INSERT INTO item VALUES (1,'a',1.50,'2020-01-01',TRUE),(2,'b',2.00,NULL,FALSE)"}""")
                .andExpect(status().isOk());
    }

    @Test
    void previewRendersReadableSqlWithoutTouchingData() throws Exception {
        postJson("/api/sessions/{id}/edits/preview", edits("""
                {"op":"UPDATE","key":{"ID":1},"values":{"NAME":"O'Brien","PRICE":"2.75"}},
                {"op":"INSERT","values":{"ID":"3","NAME":"c","BORN":"2021-05-06"}},
                {"op":"DELETE","key":{"ID":2}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statements[0]").value("UPDATE \"PUBLIC\".\"ITEM\" SET \"NAME\" = 'O''Brien', \"PRICE\" = 2.75 WHERE \"ID\" = 1"))
                .andExpect(jsonPath("$.statements[1]").value("INSERT INTO \"PUBLIC\".\"ITEM\" (\"ID\", \"NAME\", \"BORN\") VALUES (3, 'c', '2021-05-06')"))
                .andExpect(jsonPath("$.statements[2]").value("DELETE FROM \"PUBLIC\".\"ITEM\" WHERE \"ID\" = 2"));

        assertThat(scalar("SELECT COUNT(*) FROM item")).isEqualTo(2);
    }

    @Test
    void applyCommitsAllChangesInAutoCommitMode() throws Exception {
        postJson("/api/sessions/{id}/edits/apply", edits("""
                {"op":"UPDATE","key":{"ID":1},"values":{"NAME":"O'Brien","PRICE":"2.75","ACTIVE":"no"}},
                {"op":"INSERT","values":{"ID":3,"NAME":"c","BORN":"2021-05-06"}},
                {"op":"DELETE","key":{"ID":2}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statements").value(3))
                .andExpect(jsonPath("$.rowsAffected").value(3))
                .andExpect(jsonPath("$.committed").value(true));

        assertThat(scalar("SELECT name FROM item WHERE id = 1")).isEqualTo("O'Brien");
        assertThat(scalar("SELECT price FROM item WHERE id = 1")).isEqualTo("2.75");
        assertThat(scalar("SELECT active FROM item WHERE id = 1")).isEqualTo(false);
        assertThat(scalar("SELECT born FROM item WHERE id = 3")).isEqualTo("2021-05-06");
        assertThat(scalar("SELECT COUNT(*) FROM item WHERE id = 2")).isEqualTo(0);
    }

    @Test
    void setsNull() throws Exception {
        postJson("/api/sessions/{id}/edits/apply", edits("{\"op\":\"UPDATE\",\"key\":{\"ID\":1},\"values\":{\"PRICE\":null}}"))
                .andExpect(status().isOk());

        assertThat(scalar("SELECT COUNT(*) FROM item WHERE id = 1 AND price IS NULL")).isEqualTo(1);
    }

    @Test
    void missingRowUndoesTheWholeSave() throws Exception {
        postJson("/api/sessions/{id}/edits/apply", edits("""
                {"op":"UPDATE","key":{"ID":1},"values":{"NAME":"changed"}},
                {"op":"UPDATE","key":{"ID":99},"values":{"NAME":"ghost"}}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("matched 0 rows")));

        assertThat(scalar("SELECT name FROM item WHERE id = 1")).isEqualTo("a");
    }

    @Test
    void manualTransactionLeavesChangesPendingAndSavepointUndoesOnlyTheFailedSave() throws Exception {
        postJson("/api/sessions/{id}/autocommit", "{\"value\":false}").andExpect(status().isOk());

        postJson("/api/sessions/{id}/edits/apply", edits("{\"op\":\"UPDATE\",\"key\":{\"ID\":1},\"values\":{\"NAME\":\"kept\"}}"))
                .andExpect(jsonPath("$.committed").value(false));
        postJson("/api/sessions/{id}/edits/apply", edits("""
                {"op":"UPDATE","key":{"ID":2},"values":{"NAME":"undone"}},
                {"op":"DELETE","key":{"ID":42}}"""))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/sessions/{id}/commit", id).header("X-JB-Client", "test")).andExpect(status().isOk());

        assertThat(scalar("SELECT name FROM item WHERE id = 1")).isEqualTo("kept");
        assertThat(scalar("SELECT name FROM item WHERE id = 2")).isEqualTo("b");

        postJson("/api/sessions/{id}/edits/apply", edits("{\"op\":\"DELETE\",\"key\":{\"ID\":1}}"))
                .andExpect(jsonPath("$.committed").value(false));
        mvc.perform(post("/api/sessions/{id}/rollback", id).header("X-JB-Client", "test")).andExpect(status().isOk());
        assertThat(scalar("SELECT COUNT(*) FROM item")).isEqualTo(2);
    }

    @Test
    void rejectsInvalidRequests() throws Exception {
        // value that does not fit the column type
        postJson("/api/sessions/{id}/edits/apply", edits("{\"op\":\"UPDATE\",\"key\":{\"ID\":1},\"values\":{\"PRICE\":\"abc\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("PRICE")));
        // unknown column (cannot smuggle SQL through a column name)
        postJson("/api/sessions/{id}/edits/apply", edits("{\"op\":\"UPDATE\",\"key\":{\"ID\":1},\"values\":{\"NAME = 'x' --\":\"y\"}}"))
                .andExpect(status().isBadRequest());
        // key must be the primary key
        postJson("/api/sessions/{id}/edits/apply", edits("{\"op\":\"DELETE\",\"key\":{\"NAME\":\"a\"}}"))
                .andExpect(status().isBadRequest());
        // tables without a primary key are addressed by all their columns; a row that is not there is a conflict
        postJson("/api/sessions/{id}/edits/apply",
                "{\"schema\":\"PUBLIC\",\"table\":\"NOKEY\",\"changes\":[{\"op\":\"UPDATE\",\"key\":{\"A\":1},\"values\":{\"A\":2}}]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("matched 0 rows")));
        // ...but inserting into them is fine (CSV import)
        postJson("/api/sessions/{id}/edits/apply",
                "{\"schema\":\"PUBLIC\",\"table\":\"NOKEY\",\"changes\":[{\"op\":\"INSERT\",\"values\":{\"A\":\"7\"}}]}")
                .andExpect(status().isOk());
        // empty change list
        postJson("/api/sessions/{id}/edits/apply", edits("")).andExpect(status().isBadRequest());

        assertThat(scalar("SELECT name FROM item WHERE id = 1")).isEqualTo("a");
    }
}
