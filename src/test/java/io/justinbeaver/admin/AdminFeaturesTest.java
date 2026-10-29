package io.justinbeaver.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import io.justinbeaver.connection.SavedConnection;
import io.justinbeaver.network.SslConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Session manager, metrics, job runner and backup command lines, against H2. */
@SpringBootTest
@AutoConfigureMockMvc
class AdminFeaturesTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path storage = Files.createTempDirectory("jb-admin");
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
        registry.add("justinbeaver.tools.dir", () -> "/opt/pg/bin");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    BackupService backups;

    @Autowired
    JobService jobs;

    String id;

    @BeforeEach
    void connect() throws Exception {
        String created = mvc.perform(post("/api/connections").header("X-JB-Client", "t").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"a\",\"driverId\":\"h2-test\",\"database\":\"adm%d\",\"user\":\"sa\",\"password\":\"\"}"
                                .formatted(System.nanoTime())))
                .andReturn().getResponse().getContentAsString();
        id = JsonPath.read(created, "$.id");
        mvc.perform(post("/api/connections/{id}/connect", id).header("X-JB-Client", "t")).andExpect(status().isOk());
    }

    @Test
    void listsServerSessionsAndLocks() throws Exception {
        mvc.perform(get("/api/sessions/{id}/admin/sessions", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0].name").value("ID"))
                .andExpect(jsonPath("$.rows.length()").value(greaterThan(0)));
        mvc.perform(get("/api/sessions/{id}/admin/locks", id)).andExpect(status().isOk());
    }

    @Test
    void rejectsSessionIdsThatAreNotNumbers() throws Exception {
        mvc.perform(post("/api/sessions/{id}/admin/session-action", id).header("X-JB-Client", "t")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"1; DROP TABLE x\",\"action\":\"TERMINATE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reportsMetrics() throws Exception {
        mvc.perform(get("/api/sessions/{id}/admin/metrics", id))
                .andExpect(jsonPath("$[?(@.name=='connections')].kind").value("GAUGE"))
                .andExpect(jsonPath("$[?(@.name=='memory')].unit").value("MB"));
    }

    @Test
    void backupIsOnlyForPostgresAndMysql() throws Exception {
        mvc.perform(post("/api/connections/{id}/backup", id).header("X-JB-Client", "t").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"file\":\"/tmp/x.dump\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("PostgreSQL, MySQL and MariaDB")));
    }

    @Test
    void buildsPgDumpAndMysqlCommandsWithoutPasswordsOnTheCommandLine() {
        SavedConnection pg = new SavedConnection("1", "pg", "postgres-jdbc", "db.local", 5433, "shop", "me", "s3cret", null,
                Map.of(), true, null, new SslConfig(true, SslConfig.Mode.VERIFY_FULL, "/ca.pem", null, null));
        BackupService.Plan plan = backups.backupPlan(pg, "127.0.0.1", 40000, Path.of("/tmp/shop.dump"), BackupService.Format.CUSTOM);
        assertThat(plan.command()).containsExactly("/opt/pg/bin/pg_dump", "--host", "127.0.0.1", "--port", "40000", "--format", "custom",
                "--file", "/tmp/shop.dump", "--verbose", "--username", "me", "shop");
        assertThat(plan.env()).containsEntry("PGPASSWORD", "s3cret").containsEntry("PGSSLMODE", "verify-full")
                .containsEntry("PGSSLROOTCERT", "/ca.pem");
        assertThat(String.join(" ", plan.command())).doesNotContain("s3cret");

        SavedConnection my = new SavedConnection("2", "my", "mysql-jdbc", "db", null, "shop", "root", "pw", null, Map.of(), true, null, null);
        BackupService.Plan restore = backups.restorePlan(my, "db", 3306, Path.of("/tmp/shop.sql"));
        assertThat(restore.command()).containsExactly("/opt/pg/bin/mysql", "--host", "db", "--port", "3306", "--user", "root", "shop");
        assertThat(restore.stdin()).isEqualTo(Path.of("/tmp/shop.sql").toFile());
        assertThat(restore.env()).containsEntry("MYSQL_PWD", "pw");
    }

    @Test
    void readOnlyProductionConnectionsRefuseWrites() throws Exception {
        String created = mvc.perform(post("/api/connections").header("X-JB-Client", "t").contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"name\":\"prod\",\"driverId\":\"h2-test\",\"database\":\"ro%d\",\"user\":\"sa\","
                                + "\"password\":\"\",\"connectionType\":\"PROD\",\"readOnly\":true}").formatted(System.nanoTime())))
                .andExpect(jsonPath("$.connectionType").value("PROD"))
                .andExpect(jsonPath("$.readOnly").value(true))
                .andReturn().getResponse().getContentAsString();
        String ro = JsonPath.read(created, "$.id");
        mvc.perform(post("/api/connections/{id}/connect", ro).header("X-JB-Client", "t")).andExpect(status().isOk());

        mvc.perform(post("/api/sessions/{id}/query", ro).header("X-JB-Client", "t").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"/* hi */ SELECT 1\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/sessions/{id}/query", ro).header("X-JB-Client", "t").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"CREATE TABLE t(a INT)\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("read-only")));
        mvc.perform(post("/api/sessions/{id}/explain", ro).header("X-JB-Client", "t").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"DELETE FROM x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/sessions/{id}/edits/apply", ro).header("X-JB-Client", "t").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"table\":\"X\",\"changes\":[{\"op\":\"INSERT\",\"values\":{\"A\":1}}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void runsJobsAndCapturesOutput() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        JobService.JobView started = jobs.start("java version", List.of(java, "-version"), Map.of(), null, null);
        JobService.JobView view = started;
        for (int i = 0; i < 100 && view.status() == JobService.Status.RUNNING; i++) {
            Thread.sleep(100);
            view = jobs.get(started.id());
        }
        assertThat(view.status()).isEqualTo(JobService.Status.SUCCEEDED);
        assertThat(String.join("\n", view.log())).contains("version");

        JobService.JobView missing = jobs.start("missing", List.of("/nonexistent/tool"), Map.of(), null, null);
        assertThat(missing.status()).isEqualTo(JobService.Status.FAILED);
        assertThat(missing.log()).anyMatch(l -> l.contains("Cannot start"));
    }
}
