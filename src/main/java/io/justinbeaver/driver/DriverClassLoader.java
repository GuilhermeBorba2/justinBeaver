package io.justinbeaver.driver;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * One class loader per driver, parented to the <em>platform</em> loader. The driver sees
 * {@code java.sql.*} but neither the application classes nor other drivers, so two drivers (or two
 * versions of one driver) can ship conflicting transitive dependencies.
 * This is the role of DBeaver's {@code DriverClassLoader}.
 */
public final class DriverClassLoader extends URLClassLoader {

    private final String driverId;

    public DriverClassLoader(String driverId, URL[] urls) {
        super("driver-" + driverId, urls, ClassLoader.getPlatformClassLoader());
        this.driverId = driverId;
    }

    public String driverId() {
        return driverId;
    }
}
