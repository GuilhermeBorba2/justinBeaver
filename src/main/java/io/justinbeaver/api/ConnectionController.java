package io.justinbeaver.api;

import java.sql.SQLException;
import java.util.Collection;

import io.justinbeaver.connection.ConnectionStore;
import io.justinbeaver.connection.SavedConnection;
import io.justinbeaver.network.SshConfig;
import io.justinbeaver.driver.DriverDescriptor;
import io.justinbeaver.jdbc.ConnectionConfig;
import io.justinbeaver.jdbc.ConnectionService;
import io.justinbeaver.jdbc.ConnectionTestResult;
import io.justinbeaver.registry.ProviderRegistry;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ConnectionController {

    private final ProviderRegistry registry;
    private final ConnectionService connections;
    private final ConnectionStore store;
    private final io.justinbeaver.driver.DriverLoader loader;

    public ConnectionController(ProviderRegistry registry, ConnectionService connections, ConnectionStore store,
                                io.justinbeaver.driver.DriverLoader loader) {
        this.registry = registry;
        this.connections = connections;
        this.store = store;
        this.loader = loader;
    }

    @GetMapping("/drivers")
    public Collection<DriverDescriptor> drivers() {
        return registry.drivers();
    }

    /** Ids of the drivers made in the driver manager (the others are bundled and read-only). */
    @GetMapping("/drivers/custom")
    public java.util.Set<String> customDrivers() {
        return registry.customIds();
    }

    @org.springframework.web.bind.annotation.PutMapping("/drivers/custom/{id}")
    public DriverDescriptor saveCustomDriver(@org.springframework.web.bind.annotation.PathVariable String id,
                                             @RequestBody DriverDescriptor driver) throws java.io.IOException {
        if (!id.equals(driver.id())) {
            throw new IllegalArgumentException("Driver id in the path and the body differ");
        }
        loader.evict(id);
        return registry.saveCustom(driver);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/drivers/custom/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void deleteCustomDriver(@org.springframework.web.bind.annotation.PathVariable String id) throws java.io.IOException {
        loader.evict(id);
        registry.deleteCustom(id);
    }

    /**
     * Downloads the driver on first use, opens a connection, validates it, closes it.
     * Only registered driver ids are accepted, so a request can never make the server fetch an
     * arbitrary Maven artifact.
     */
    @PostMapping("/connections/test")
    public ConnectionTestResult test(@Valid @RequestBody ConnectionConfig config,
                                     @RequestParam(required = false) String savedId) throws SQLException {
        ConnectionConfig effective = config;
        if (savedId != null) {
            // editing a saved connection without retyping its secrets: fill in the stored ones
            SavedConnection saved = store.get(savedId);
            String password = config.password() == null ? saved.password() : config.password();
            SshConfig ssh = config.ssh();
            if (ssh != null && saved.ssh() != null) {
                ssh = ssh.withSecrets(ssh.password() == null ? saved.ssh().password() : ssh.password(),
                        ssh.passphrase() == null ? saved.ssh().passphrase() : ssh.passphrase());
            }
            effective = config.withSecrets(password, ssh);
        }
        return connections.dataSource(effective).test();
    }
}
