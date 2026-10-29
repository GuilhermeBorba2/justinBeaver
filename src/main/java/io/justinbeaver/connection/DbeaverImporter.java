package io.justinbeaver.connection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.justinbeaver.network.SshConfig;
import io.justinbeaver.registry.ProviderRegistry;
import io.justinbeaver.registry.UnknownDriverException;
import org.springframework.stereotype.Component;

/**
 * Imports connections from a DBeaver workspace ({@code .dbeaver/data-sources.json}), with the saved user names
 * and passwords of {@code credentials-config.json}. DBeaver encrypts that file with a fixed AES key that is part of
 * its open source code, so it can be read back here; the passwords are re-encrypted with this app's own key.
 */
@Component
public class DbeaverImporter {

    /** DBeaver's built-in key for credentials-config.json (org.jkiss.dbeaver.model.impl.app.DefaultSecureStorage). */
    private static final byte[] DBEAVER_KEY = HexFormat.of().parseHex("babb4a9f774ab853c96c2d653dfe544a");

    /** DBeaver driver ids whose driver has another id here. */
    private static final Map<String, String> DRIVER_ALIASES = Map.of(
            "mysql8", "mysql-jdbc", "mysql5", "mysql-jdbc", "mysql", "mysql-jdbc",
            "mariaDB", "mariadb-jdbc", "mariadb", "mariadb-jdbc",
            "sqlite_jdbc", "sqlite-jdbc", "sqlite", "sqlite-jdbc", "postgresql", "postgres-jdbc");

    public record Candidate(String id, String name, String driverId, boolean driverKnown, String host, Integer port,
                            String database, String url, String user, boolean hasPassword, String folder, ConnectionType type,
                            boolean readOnly, boolean ssh, boolean exists) {
    }

    public record Preview(String dir, List<Candidate> connections, List<String> warnings) {
    }

    public record Skipped(String name, String reason) {
    }

    public record Result(List<String> created, List<Skipped> skipped) {
    }

    private final ConnectionStore store;
    private final ProviderRegistry registry;
    private final ObjectMapper json = new ObjectMapper();

    public DbeaverImporter(ConnectionStore store, ProviderRegistry registry) {
        this.store = store;
        this.registry = registry;
    }

    /** The usual workspace folders of DBeaver CE / EE on Linux, macOS and Windows; the first that exists. */
    public static Path defaultDir() {
        String home = System.getProperty("user.home");
        String appData = System.getenv("APPDATA");
        List<Path> candidates = new ArrayList<>(List.of(
                Path.of(home, ".local", "share", "DBeaverData", "workspace6", "General", ".dbeaver"),
                Path.of(home, "Library", "DBeaverData", "workspace6", "General", ".dbeaver"),
                Path.of(home, ".dbeaver4", "General", ".dbeaver")));
        if (appData != null) {
            candidates.add(1, Path.of(appData, "DBeaverData", "workspace6", "General", ".dbeaver"));
        }
        return candidates.stream().filter(p -> Files.isRegularFile(p.resolve("data-sources.json"))).findFirst()
                .orElse(candidates.getFirst());
    }

    /** {@code dir} is the {@code .dbeaver} folder, its project folder, or the data-sources.json file itself. */
    static Path resolve(String dir) {
        if (dir == null || dir.isBlank()) {
            return defaultDir();
        }
        Path path = Path.of(dir.strip().replaceFirst("^~", System.getProperty("user.home")));
        if (path.getFileName() != null && path.getFileName().toString().endsWith(".json")) {
            return path.getParent();
        }
        return Files.isDirectory(path.resolve(".dbeaver")) ? path.resolve(".dbeaver") : path;
    }

    public Preview preview(String dir) throws IOException {
        Path folder = resolve(dir);
        Parsed parsed = read(folder);
        Set<String> names = store.all().stream().map(c -> c.name().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        List<Candidate> out = new ArrayList<>();
        parsed.inputs.forEach((id, input) -> out.add(new Candidate(id, input.name(), input.driverId(), known(input.driverId()),
                input.host(), input.port(), input.database(), input.url(), input.user(), input.password() != null && !input.password().isEmpty(),
                input.folder(), input.connectionType(), Boolean.TRUE.equals(input.readOnly()), input.ssh() != null && input.ssh().enabled(),
                names.contains(input.name().toLowerCase(Locale.ROOT)))));
        return new Preview(folder.toString(), out, parsed.warnings);
    }

    /** Creates the chosen connections; a name already in use gets " (DBeaver)" appended. */
    public Result importConnections(String dir, List<String> ids) throws IOException {
        Parsed parsed = read(resolve(dir));
        Set<String> names = store.all().stream().map(c -> c.name().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        List<String> created = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (String id : ids) {
            ConnectionInput input = parsed.inputs.get(id);
            if (input == null) {
                skipped.add(new Skipped(id, "not in data-sources.json"));
                continue;
            }
            if (!known(input.driverId())) {
                skipped.add(new Skipped(input.name(), "driver " + input.driverId() + " is not available here"));
                continue;
            }
            String name = input.name();
            while (names.contains(name.toLowerCase(Locale.ROOT))) {
                name = name + " (DBeaver)";
            }
            names.add(name.toLowerCase(Locale.ROOT));
            store.create(renamed(input, name));
            created.add(name);
        }
        return new Result(created, skipped);
    }

    private boolean known(String driverId) {
        try {
            registry.driver(driverId);
            return true;
        } catch (UnknownDriverException e) {
            return false;
        }
    }

    private record Parsed(Map<String, ConnectionInput> inputs, List<String> warnings) {
    }

    private Parsed read(Path folder) throws IOException {
        Path sources = folder.resolve("data-sources.json");
        if (!Files.isRegularFile(sources)) {
            throw new IllegalArgumentException("No DBeaver data-sources.json in " + folder);
        }
        JsonNode root = json.readTree(sources.toFile());
        List<String> warnings = new ArrayList<>();
        JsonNode credentials = json.createObjectNode();
        Path credentialsFile = folder.resolve("credentials-config.json");
        if (Files.isRegularFile(credentialsFile)) {
            try {
                credentials = json.readTree(decrypt(Files.readAllBytes(credentialsFile)));
            } catch (IOException | GeneralSecurityException e) {
                warnings.add("Saved passwords could not be read (" + e.getMessage() + "): connections are imported without them");
            }
        }
        Map<String, ConnectionInput> inputs = new LinkedHashMap<>();
        var fields = root.path("connections").fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            inputs.put(entry.getKey(), toInput(entry.getValue(), credentials.path(entry.getKey())));
        }
        return new Parsed(inputs, warnings);
    }

    static byte[] decrypt(byte[] data) throws GeneralSecurityException {
        if (data.length < 32) {
            throw new GeneralSecurityException("file too short");
        }
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(DBEAVER_KEY, "AES"), new IvParameterSpec(data, 0, 16));
        return cipher.doFinal(data, 16, data.length - 16);
    }

    static ConnectionInput toInput(JsonNode c, JsonNode secrets) {
        JsonNode cfg = c.path("configuration");
        boolean byUrl = "URL".equalsIgnoreCase(cfg.path("configurationType").asText(""));
        Map<String, String> properties = new LinkedHashMap<>();
        cfg.path("properties").fields().forEachRemaining(p -> properties.put(p.getKey(), p.getValue().asText()));
        JsonNode auth = secrets.path("#connection");
        String user = text(auth.path("user"), text(cfg.path("user"), null));
        String password = text(auth.path("password"), text(cfg.path("password"), null));
        String type = cfg.path("type").asText("dev").toLowerCase(Locale.ROOT);
        ConnectionType connectionType = type.contains("prod") ? ConnectionType.PROD : type.contains("test") ? ConnectionType.TEST
                : ConnectionType.DEV;
        boolean showAll = "true".equals(cfg.path("provider-properties").path("@dbeaver-show-non-default-db@").asText());
        String driver = c.path("driver").asText();
        return new ConnectionInput(c.path("name").asText("Imported"), DRIVER_ALIASES.getOrDefault(driver, driver),
                byUrl ? null : text(cfg.path("host"), null), byUrl ? null : port(cfg.path("port")),
                byUrl ? null : text(cfg.path("database"), null), user, password,
                byUrl ? text(cfg.path("url"), null) : null, properties,
                cfg.has("auto-commit") ? cfg.path("auto-commit").asBoolean(true) : null,
                ssh(cfg.path("handlers").path("ssh_tunnel"), secrets.path("network/ssh_tunnel")), null, connectionType,
                c.path("read-only").asBoolean(false), showAll, text(c.path("folder"), null));
    }

    private static SshConfig ssh(JsonNode handler, JsonNode secrets) {
        if (handler.isMissingNode() || !handler.path("enabled").asBoolean(false)) {
            return null;
        }
        JsonNode p = handler.path("properties");
        boolean key = "PUBLIC_KEY".equalsIgnoreCase(p.path("authType").asText(""));
        return new SshConfig(true, text(p.path("host"), null), port(p.path("port")),
                text(secrets.path("user"), text(handler.path("user"), null)), key ? SshConfig.Auth.KEY : SshConfig.Auth.PASSWORD,
                key ? null : text(secrets.path("password"), null), key ? text(p.path("keyPath"), null) : null,
                key ? text(secrets.path("password"), null) : null, true);
    }

    private static ConnectionInput renamed(ConnectionInput in, String name) {
        return new ConnectionInput(name, in.driverId(), in.host(), in.port(), in.database(), in.user(), in.password(), in.url(),
                in.properties(), in.autoCommit(), in.ssh(), in.ssl(), in.connectionType(), in.readOnly(), in.showAllDatabases(),
                in.folder());
    }

    private static String text(JsonNode node, String fallback) {
        return node.isMissingNode() || node.isNull() || node.asText().isEmpty() ? fallback : node.asText();
    }

    private static Integer port(JsonNode node) {
        try {
            return node.isMissingNode() || node.asText().isBlank() ? null : Integer.valueOf(node.asText().strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
