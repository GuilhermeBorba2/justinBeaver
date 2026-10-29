package io.justinbeaver.network;

import java.util.Locale;
import java.util.Properties;

import io.justinbeaver.driver.DriverDescriptor;

/**
 * Maps {@link SslConfig} onto driver properties (what DBeaver's per-database SSL handlers do).
 * Properties the user set explicitly under "driver properties" always win.
 */
public final class SslProperties {

    private SslProperties() {
    }

    public static void apply(DriverDescriptor driver, SslConfig ssl, Properties props) {
        if (ssl == null || !ssl.enabled()) {
            return;
        }
        String className = driver.className();
        SslConfig.Mode mode = ssl.modeOrDefault();
        if (className.startsWith("org.postgresql")) {
            set(props, "ssl", "true");
            set(props, "sslmode", switch (mode) {
                case REQUIRE -> "require";
                case VERIFY_CA -> "verify-ca";
                case VERIFY_FULL -> "verify-full";
            });
            setIfPresent(props, "sslrootcert", ssl.caCertPath());
            setIfPresent(props, "sslcert", ssl.clientCertPath());
            setIfPresent(props, "sslkey", ssl.clientKeyPath());
        } else if (className.startsWith("com.mysql")) {
            set(props, "sslMode", switch (mode) {
                case REQUIRE -> "REQUIRED";
                case VERIFY_CA -> "VERIFY_CA";
                case VERIFY_FULL -> "VERIFY_IDENTITY";
            });
            if (notBlank(ssl.caCertPath())) {
                set(props, "trustCertificateKeyStoreUrl", "file:" + keystore(ssl.caCertPath(), "MySQL CA"));
                set(props, "trustCertificateKeyStoreType", storeType(ssl.caCertPath()));
            }
            if (notBlank(ssl.clientCertPath())) {
                set(props, "clientCertificateKeyStoreUrl", "file:" + keystore(ssl.clientCertPath(), "MySQL client certificate"));
                set(props, "clientCertificateKeyStoreType", storeType(ssl.clientCertPath()));
            }
        } else if (className.startsWith("org.mariadb")) {
            set(props, "sslMode", switch (mode) {
                case REQUIRE -> "trust";
                case VERIFY_CA -> "verify-ca";
                case VERIFY_FULL -> "verify-full";
            });
            setIfPresent(props, "serverSslCert", ssl.caCertPath());
            if (notBlank(ssl.clientCertPath())) {
                throw new IllegalArgumentException("Client certificates for MariaDB need a keystore: set keyStore under driver properties");
            }
        } else if (className.startsWith("com.microsoft.sqlserver")) {
            set(props, "encrypt", "true");
            set(props, "trustServerCertificate", String.valueOf(mode == SslConfig.Mode.REQUIRE));
            if (notBlank(ssl.caCertPath())) {
                set(props, "trustStore", keystore(ssl.caCertPath(), "SQL Server CA"));
                set(props, "trustStoreType", storeType(ssl.caCertPath()));
            }
        } else {
            throw new IllegalArgumentException(("SSL settings are not mapped for driver %s; set the driver's own SSL "
                    + "properties under Advanced → Driver properties").formatted(driver.label()));
        }
    }

    private static void set(Properties props, String key, String value) {
        props.putIfAbsent(key, value);
    }

    private static void setIfPresent(Properties props, String key, String value) {
        if (notBlank(value)) {
            set(props, key, value.trim());
        }
    }

    /** These drivers read Java keystores, not PEM files. */
    private static String keystore(String path, String what) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".jks") || lower.endsWith(".p12") || lower.endsWith(".pfx"))) {
            throw new IllegalArgumentException(what + " must be a JKS or PKCS#12 keystore (.jks, .p12, .pfx), not PEM");
        }
        return path.trim();
    }

    private static String storeType(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".jks") ? "JKS" : "PKCS12";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
