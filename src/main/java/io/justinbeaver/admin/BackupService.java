package io.justinbeaver.admin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.justinbeaver.connection.SavedConnection;
import io.justinbeaver.driver.DriverDescriptor;
import io.justinbeaver.network.SshTunnel;
import io.justinbeaver.network.SslConfig;
import io.justinbeaver.network.TunnelFactory;
import io.justinbeaver.registry.ProviderRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Native backup and restore (DBeaver tasks.native): pg_dump / pg_restore / psql for PostgreSQL,
 * mysqldump / mysql for MySQL and MariaDB. Tools come from {@code justinbeaver.tools.dir} or the
 * PATH. Passwords are passed in the environment (PGPASSWORD, MYSQL_PWD), not on the command line.
 * Connections through SSH get a tunnel for the duration of the job.
 */
@Service
public class BackupService {

    public enum Format { CUSTOM, PLAIN }

    /** What will run, kept separate from running it so it can be tested. */
    record Plan(String label, List<String> command, Map<String, String> env, File stdin) {
    }

    private final ProviderRegistry registry;
    private final TunnelFactory tunnels;
    private final JobService jobs;
    private final String toolsDir;

    public BackupService(ProviderRegistry registry, TunnelFactory tunnels, JobService jobs,
                         @Value("${justinbeaver.tools.dir:}") String toolsDir) {
        this.registry = registry;
        this.tunnels = tunnels;
        this.jobs = jobs;
        this.toolsDir = toolsDir;
    }

    public JobService.JobView backup(SavedConnection saved, String file, Format format) throws SQLException {
        return run(saved, (host, port) -> backupPlan(saved, host, port, requireFile(file, false), format));
    }

    public JobService.JobView restore(SavedConnection saved, String file) throws SQLException {
        return run(saved, (host, port) -> restorePlan(saved, host, port, requireFile(file, true)));
    }

    private interface Planner {
        Plan plan(String host, int port);
    }

    private JobService.JobView run(SavedConnection saved, Planner planner) throws SQLException {
        DriverDescriptor driver = registry.driver(saved.driverId());
        if (saved.url() != null && !saved.url().isBlank()) {
            throw new IllegalArgumentException("Backup needs host, port and database fields; this connection uses a custom JDBC URL");
        }
        if (saved.database() == null || saved.database().isBlank()) {
            throw new IllegalArgumentException("Backup needs the connection's database name");
        }
        String host = saved.host() == null || saved.host().isBlank() ? "localhost" : saved.host().trim();
        int port = saved.port() != null ? saved.port() : driver.defaultPort() == null ? 0 : driver.defaultPort();
        SshTunnel tunnel = null;
        if (saved.ssh() != null && saved.ssh().enabled()) {
            tunnel = tunnels.open(saved.ssh(), host, port);
            host = "127.0.0.1";
            port = tunnel.localPort();
        }
        try {
            Plan plan = planner.plan(host, port);
            return jobs.start(plan.label(), plan.command(), plan.env(), plan.stdin(), tunnel);
        } catch (RuntimeException e) {
            if (tunnel != null) {
                tunnel.close();
            }
            throw e;
        }
    }

    Plan backupPlan(SavedConnection c, String host, int port, Path file, Format format) {
        String db = c.database().trim();
        return switch (family(c)) {
            case POSTGRES -> {
                List<String> cmd = new ArrayList<>(List.of(tool("pg_dump"), "--host", host, "--port", String.valueOf(port),
                        "--format", format == Format.PLAIN ? "plain" : "custom", "--file", file.toString(), "--verbose"));
                user(cmd, "--username", c);
                cmd.add(db);
                yield new Plan("Backup " + db + " → " + file.getFileName(), cmd, pgEnv(c), null);
            }
            case MYSQL -> {
                List<String> cmd = new ArrayList<>(List.of(tool("mysqldump"), "--host", host, "--port", String.valueOf(port),
                        "--single-transaction", "--routines", "--triggers", "--events", "--hex-blob", "--result-file", file.toString()));
                user(cmd, "--user", c);
                mysqlSsl(cmd, c.ssl());
                cmd.add(db);
                yield new Plan("Backup " + db + " → " + file.getFileName(), cmd, mysqlEnv(c), null);
            }
        };
    }

    Plan restorePlan(SavedConnection c, String host, int port, Path file) {
        String db = c.database().trim();
        return switch (family(c)) {
            case POSTGRES -> {
                boolean plain = !isPgCustomArchive(file);
                List<String> cmd = new ArrayList<>(List.of(tool(plain ? "psql" : "pg_restore"), "--host", host, "--port", String.valueOf(port)));
                user(cmd, "--username", c);
                if (plain) {
                    cmd.addAll(List.of("--dbname", db, "--set", "ON_ERROR_STOP=1", "--file", file.toString()));
                } else {
                    cmd.addAll(List.of("--dbname", db, "--no-owner", "--verbose", file.toString()));
                }
                yield new Plan("Restore " + file.getFileName() + " → " + db, cmd, pgEnv(c), null);
            }
            case MYSQL -> {
                List<String> cmd = new ArrayList<>(List.of(tool("mysql"), "--host", host, "--port", String.valueOf(port)));
                user(cmd, "--user", c);
                mysqlSsl(cmd, c.ssl());
                cmd.add(db);
                yield new Plan("Restore " + file.getFileName() + " → " + db, cmd, mysqlEnv(c), file.toFile());
            }
        };
    }

    private enum Family { POSTGRES, MYSQL }

    private Family family(SavedConnection c) {
        String className = registry.driver(c.driverId()).className();
        if (className.startsWith("org.postgresql")) {
            return Family.POSTGRES;
        }
        if (className.startsWith("com.mysql") || className.startsWith("org.mariadb")) {
            return Family.MYSQL;
        }
        throw new IllegalArgumentException("Native backup is available for PostgreSQL, MySQL and MariaDB connections");
    }

    private String tool(String name) {
        if (toolsDir != null && !toolsDir.isBlank()) {
            return Path.of(toolsDir, name).toString();
        }
        return name;
    }

    private static void user(List<String> cmd, String flag, SavedConnection c) {
        if (c.user() != null && !c.user().isBlank()) {
            cmd.add(flag);
            cmd.add(c.user().trim());
        }
    }

    private static Map<String, String> pgEnv(SavedConnection c) {
        Map<String, String> env = new HashMap<>();
        if (c.password() != null) {
            env.put("PGPASSWORD", c.password());
        }
        SslConfig ssl = c.ssl();
        if (ssl != null && ssl.enabled()) {
            env.put("PGSSLMODE", switch (ssl.modeOrDefault()) {
                case REQUIRE -> "require";
                case VERIFY_CA -> "verify-ca";
                case VERIFY_FULL -> "verify-full";
            });
            if (ssl.caCertPath() != null && !ssl.caCertPath().isBlank()) {
                env.put("PGSSLROOTCERT", ssl.caCertPath().trim());
            }
            if (ssl.clientCertPath() != null && !ssl.clientCertPath().isBlank()) {
                env.put("PGSSLCERT", ssl.clientCertPath().trim());
            }
            if (ssl.clientKeyPath() != null && !ssl.clientKeyPath().isBlank()) {
                env.put("PGSSLKEY", ssl.clientKeyPath().trim());
            }
        }
        return env;
    }

    private static Map<String, String> mysqlEnv(SavedConnection c) {
        Map<String, String> env = new HashMap<>();
        if (c.password() != null) {
            env.put("MYSQL_PWD", c.password());
        }
        return env;
    }

    private static void mysqlSsl(List<String> cmd, SslConfig ssl) {
        if (ssl != null && ssl.enabled() && ssl.caCertPath() != null && !ssl.caCertPath().isBlank()
                && ssl.caCertPath().toLowerCase().endsWith(".pem")) {
            cmd.add("--ssl-ca=" + ssl.caCertPath().trim());
        }
    }

    /** pg_dump custom archives start with "PGDMP". */
    static boolean isPgCustomArchive(Path file) {
        try (var in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(5);
            return new String(head, java.nio.charset.StandardCharsets.US_ASCII).equals("PGDMP");
        } catch (Exception e) {
            return false;
        }
    }

    private static Path requireFile(String file, boolean mustExist) {
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("Choose a file");
        }
        Path path = Path.of(file.trim().replaceFirst("^~", System.getProperty("user.home"))).toAbsolutePath().normalize();
        if (mustExist && !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("File not found: " + path);
        }
        if (!mustExist && (path.getParent() == null || !Files.isDirectory(path.getParent()))) {
            throw new IllegalArgumentException("Folder does not exist: " + path.getParent());
        }
        return path;
    }
}
