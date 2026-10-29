package io.justinbeaver.driver;

import java.sql.Driver;

/** A driver instance together with the isolated class loader that owns it. */
public record LoadedDriver(DriverDescriptor descriptor, DriverClassLoader classLoader, Driver driver) {
}
