package io.justinbeaver.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "justinbeaver.storage.dir=${java.io.tmpdir}/jb-api-test")
@AutoConfigureMockMvc
class ConnectionApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void listsBundledDrivers() throws Exception {
        mvc.perform(get("/api/drivers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='postgres-jdbc')].className").value("org.postgresql.Driver"));
    }

    @Test
    void unknownDriverIs404() throws Exception {
        mvc.perform(post("/api/connections/test").header("X-JB-Client", "test")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"driverId\":\"nope\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingDriverIdIs400() throws Exception {
        mvc.perform(post("/api/connections/test").header("X-JB-Client", "test")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsNonLoopbackHost() throws Exception {
        mvc.perform(get("/api/drivers").with(r -> {
                    r.setServerName("evil.example.com");
                    return r;
                }))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsCrossOriginRequests() throws Exception {
        mvc.perform(get("/api/drivers").header("Origin", "https://evil.example.com"))
                .andExpect(status().isForbidden());
    }

    @Test
    void acceptsLoopbackOrigin() throws Exception {
        mvc.perform(get("/api/drivers").header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk());
    }

    @Test
    void stateChangingRequestsNeedTheClientHeader() throws Exception {
        mvc.perform(post("/api/connections/test").contentType(MediaType.APPLICATION_JSON).content("{\"driverId\":\"x\"}"))
                .andExpect(status().isForbidden());
    }
}
