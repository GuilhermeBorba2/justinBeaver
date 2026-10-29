package io.justinbeaver.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Properties;

import io.justinbeaver.driver.DriverDescriptor;
import org.junit.jupiter.api.Test;

class SslPropertiesTest {

    private static DriverDescriptor driver(String className) {
        return new DriverDescriptor("d", null, "Driver", className, null, null, null, null, null, List.of());
    }

    private static Properties apply(String className, SslConfig ssl, Properties props) {
        SslProperties.apply(driver(className), ssl, props);
        return props;
    }

    @Test
    void postgres() {
        Properties props = apply("org.postgresql.Driver",
                new SslConfig(true, SslConfig.Mode.VERIFY_CA, "/ca.pem", "/client.crt", "/client.key"), new Properties());
        assertThat(props).containsEntry("ssl", "true").containsEntry("sslmode", "verify-ca")
                .containsEntry("sslrootcert", "/ca.pem").containsEntry("sslcert", "/client.crt").containsEntry("sslkey", "/client.key");
    }

    @Test
    void explicitDriverPropertiesWin() {
        Properties props = new Properties();
        props.setProperty("sslmode", "prefer");
        apply("org.postgresql.Driver", new SslConfig(true, SslConfig.Mode.VERIFY_FULL, null, null, null), props);
        assertThat(props).containsEntry("sslmode", "prefer");
    }

    @Test
    void mysqlNeedsKeystores() {
        Properties props = apply("com.mysql.cj.jdbc.Driver", new SslConfig(true, SslConfig.Mode.VERIFY_FULL, "/trust.p12", null, null),
                new Properties());
        assertThat(props).containsEntry("sslMode", "VERIFY_IDENTITY")
                .containsEntry("trustCertificateKeyStoreUrl", "file:/trust.p12")
                .containsEntry("trustCertificateKeyStoreType", "PKCS12");

        assertThatThrownBy(() -> apply("com.mysql.cj.jdbc.Driver",
                new SslConfig(true, SslConfig.Mode.VERIFY_CA, "/ca.pem", null, null), new Properties()))
                .hasMessageContaining("JKS or PKCS#12");
    }

    @Test
    void mariadbAndSqlServer() {
        assertThat(apply("org.mariadb.jdbc.Driver", new SslConfig(true, SslConfig.Mode.REQUIRE, "/ca.pem", null, null), new Properties()))
                .containsEntry("sslMode", "trust").containsEntry("serverSslCert", "/ca.pem");
        assertThat(apply("com.microsoft.sqlserver.jdbc.SQLServerDriver", new SslConfig(true, SslConfig.Mode.REQUIRE, null, null, null),
                new Properties()))
                .containsEntry("encrypt", "true").containsEntry("trustServerCertificate", "true");
    }

    @Test
    void disabledDoesNothingAndUnknownDriversAreRejected() {
        assertThat(apply("org.sqlite.JDBC", new SslConfig(false, null, null, null, null), new Properties())).isEmpty();
        assertThatThrownBy(() -> apply("org.sqlite.JDBC", new SslConfig(true, null, null, null, null), new Properties()))
                .hasMessageContaining("not mapped");
    }
}
