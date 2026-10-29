package io.justinbeaver.jdbc;

import io.justinbeaver.driver.DriverDescriptor;
import io.justinbeaver.driver.DriverLoader;
import io.justinbeaver.network.TunnelFactory;
import io.justinbeaver.registry.ProviderRegistry;
import org.springframework.stereotype.Service;

/** Wires a {@link ConnectionConfig} to its driver descriptor and provider. */
@Service
public class ConnectionService {

    private final ProviderRegistry registry;
    private final DriverLoader driverLoader;
    private final TunnelFactory tunnels;

    public ConnectionService(ProviderRegistry registry, DriverLoader driverLoader, TunnelFactory tunnels) {
        this.registry = registry;
        this.driverLoader = driverLoader;
        this.tunnels = tunnels;
    }

    public JdbcDataSource dataSource(ConnectionConfig config) {
        DriverDescriptor driver = registry.driver(config.driverId());
        return new JdbcDataSource(driver, registry.providerFor(driver), driverLoader, config, tunnels);
    }
}
