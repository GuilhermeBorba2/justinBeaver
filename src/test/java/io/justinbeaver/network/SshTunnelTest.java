package io.justinbeaver.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import io.justinbeaver.config.JustinBeaverProperties;
import io.justinbeaver.driver.DriverDescriptor;
import io.justinbeaver.driver.DriverLibrary;
import io.justinbeaver.driver.DriverLoader;
import io.justinbeaver.jdbc.ConnectionConfig;
import io.justinbeaver.jdbc.JdbcDataSource;
import io.justinbeaver.maven.MavenResolver;
import io.justinbeaver.provider.GenericDataSourceProvider;
import net.schmizz.sshj.common.KeyType;
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts.HostEntry;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.h2.tools.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real tunnel: embedded SSH server (Apache MINA SSHD) forwarding to an H2 TCP server, with the H2
 * driver loaded in its own class loader like any user driver.
 */
class SshTunnelTest {

    @TempDir
    Path dir;

    SshServer sshd;
    Server h2;
    Path knownHosts;
    TunnelFactory tunnels;
    DriverLoader loader;
    DriverDescriptor h2Tcp;

    @BeforeEach
    void start() throws Exception {
        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(dir.resolve("hostkey.ser")));
        sshd.setPasswordAuthenticator((user, password, session) -> "tunnel".equals(user) && "s3cret".equals(password));
        sshd.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        sshd.start();

        h2 = Server.createTcpServer("-tcpPort", "0", "-ifNotExists").start();

        knownHosts = dir.resolve("known_hosts");
        tunnels = new TunnelFactory(knownHosts, null);
        loader = new DriverLoader(new MavenResolver(new JustinBeaverProperties(
                new JustinBeaverProperties.Drivers(null), new JustinBeaverProperties.Maven(null, List.of()))));
        String h2Jar = Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        h2Tcp = new DriverDescriptor("h2-tcp", null, "H2 TCP", "org.h2.Driver", "jdbc:h2:tcp://{host}:{port}/mem:{database}",
                null, null, null, null, List.of(new DriverLibrary(null, h2Jar, false)));
    }

    @AfterEach
    void stop() throws Exception {
        sshd.stop(true);
        h2.stop();
    }

    private SshConfig ssh(String password, boolean acceptNew) {
        return new SshConfig(true, "127.0.0.1", sshd.getPort(), "tunnel", SshConfig.Auth.PASSWORD, password, null, null, acceptNew);
    }

    private JdbcDataSource dataSource(SshConfig ssh) {
        // host/port are the database as seen from the SSH server
        ConnectionConfig config = new ConnectionConfig("h2-tcp", "127.0.0.1", h2.getPort(), "tunneled", "sa", "", null,
                Map.of(), ssh, null);
        return new JdbcDataSource(h2Tcp, new GenericDataSourceProvider(), loader, config, tunnels);
    }

    @Test
    void connectsThroughTheTunnelAndRemembersTheHostKey() throws Exception {
        try (JdbcDataSource.Opened opened = dataSource(ssh("s3cret", true)).open()) {
            assertThat(opened.tunnel()).isNotNull();
            assertThat(opened.tunnel().localPort()).isNotEqualTo(h2.getPort());
            assertThat(opened.connection().getMetaData().getURL()).contains("127.0.0.1:" + opened.tunnel().localPort());
            try (var rs = opened.connection().createStatement().executeQuery("SELECT 41 + 1")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(42);
            }
        }
        assertThat(Files.readString(knownHosts)).contains("[127.0.0.1]:" + sshd.getPort());

        // second connection verifies against the remembered key
        assertThat(dataSource(ssh("s3cret", false)).test().productName()).isEqualTo("H2");
    }

    @Test
    void wrongPasswordIsAnAuthenticationError() {
        assertThatThrownBy(() -> dataSource(ssh("nope", true)).open())
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("SSH authentication")
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("28000"));
    }

    @Test
    void unknownHostIsRejectedWhenNewKeysAreNotAccepted() {
        assertThatThrownBy(() -> dataSource(ssh("s3cret", false)).open())
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Unknown host key");
    }

    @Test
    void changedHostKeyIsAlwaysRejected() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String host = "[127.0.0.1]:" + sshd.getPort();
        Files.writeString(knownHosts, new HostEntry(null, host, KeyType.RSA, generator.generateKeyPair().getPublic()).getLine() + "\n");

        assertThatThrownBy(() -> dataSource(ssh("s3cret", true)).open())
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("HOST KEY CHANGED");
    }

    @Test
    void customUrlCannotBeTunneled() {
        ConnectionConfig config = new ConnectionConfig("h2-tcp", null, null, null, "sa", "", "jdbc:h2:mem:x", Map.of(),
                ssh("s3cret", true), null);
        assertThatThrownBy(() -> new JdbcDataSource(h2Tcp, new GenericDataSourceProvider(), loader, config, tunnels).open())
                .hasMessageContaining("clear the custom JDBC URL");
    }
}
