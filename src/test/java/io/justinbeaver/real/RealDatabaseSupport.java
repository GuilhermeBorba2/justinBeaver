package io.justinbeaver.real;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Shared helpers for the integration tests against real databases in Docker. They go through the
 * whole application: the bundled driver descriptor, Maven download, isolated class loader, HTTP API.
 */
abstract class RealDatabaseSupport {

    static final ObjectMapper JSON = new ObjectMapper();

    static void storage(DynamicPropertyRegistry registry, String name) throws Exception {
        Path dir = Files.createTempDirectory("jb-real-" + name);
        registry.add("justinbeaver.storage.dir", dir::toString);
        registry.add("justinbeaver.maven.local-repo", () -> dir.resolve("driver-cache").toString());
    }

    @Autowired
    MockMvc mvc;

    String id;

    String body(Object value) throws Exception {
        return JSON.writeValueAsString(value);
    }

    String postJson(String url, Object payload, Object... vars) throws Exception {
        MockHttpServletRequestBuilder request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(url, vars).header("X-JB-Client", "it").contentType(MediaType.APPLICATION_JSON).content(body(payload));
        return mvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    String postJsonOk(String url, Object payload, Object... vars) throws Exception {
        MockHttpServletRequestBuilder request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(url, vars).header("X-JB-Client", "it").contentType(MediaType.APPLICATION_JSON).content(body(payload));
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    String getOk(String url, Map<String, String> params, Object... vars) throws Exception {
        MockHttpServletRequestBuilder request = get(url, vars);
        params.forEach(request::param);
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    String connect(String driverId, String host, int port, String database, String user, String password) throws Exception {
        String created = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/connections")
                        .header("X-JB-Client", "it").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", driverId + "-" + database, "driverId", driverId, "host", host, "port", port,
                                "database", database, "user", user, "password", password))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String connectionId = JsonPath.read(created, "$.id");
        mvc.perform(post("/api/connections/{id}/connect", connectionId).header("X-JB-Client", "it")).andExpect(status().isOk());
        return connectionId;
    }

    /** Run a script and require every statement to succeed. */
    List<Map<String, Object>> script(String sql) throws Exception {
        String json = postJsonOk("/api/sessions/{id}/script", Map.of("sql", sql), id);
        List<Map<String, Object>> results = JsonPath.read(json, "$");
        for (Map<String, Object> r : results) {
            assertThat(r.get("error")).as("statement %s", r.get("sql")).isNull();
        }
        return results;
    }

    Object scalar(String sql) throws Exception {
        return JsonPath.read(postJsonOk("/api/sessions/{id}/query", Map.of("sql", sql), id), "$.rows[0][0]");
    }

    Map<String, Object> waitForJob(String jobJson) throws Exception {
        String jobId = JsonPath.read(jobJson, "$.id");
        Map<String, Object> job = JsonPath.read(jobJson, "$");
        for (int i = 0; i < 300 && "RUNNING".equals(job.get("status")); i++) {
            Thread.sleep(200);
            job = JsonPath.read(getOk("/api/jobs/{id}", Map.of(), jobId), "$");
        }
        return job;
    }
}
