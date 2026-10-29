package io.justinbeaver.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.justinbeaver.config.StorageProperties;
import io.justinbeaver.connection.ConnectionInput;
import io.justinbeaver.connection.ConnectionStore;
import io.justinbeaver.connection.SavedConnection;
import io.justinbeaver.connection.SecretCipher;
import io.justinbeaver.script.ScriptStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Two machines, one Google account: a local stand-in for Google's token, userinfo and Drive endpoints. */
class SyncFlowTest {

    private HttpServer google;
    private String base;
    private final AtomicReference<byte[]> driveFile = new AtomicReference<>();
    private final Map<String, String> challenges = new HashMap<>();
    private final StringBuilder tokenRequests = new StringBuilder();

    @BeforeEach
    void startGoogle() throws IOException {
        google = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        google.createContext("/token", this::token);
        google.createContext("/userinfo", x -> json(x, 200, "{\"email\":\"ana@example.com\",\"name\":\"Ana\",\"picture\":\"http://x/p.png\"}"));
        google.createContext("/revoke", x -> json(x, 200, "{}"));
        google.createContext("/drive/v3/files", this::drive);
        google.createContext("/upload/drive/v3/files", this::upload);
        google.start();
        base = "http://127.0.0.1:" + google.getAddress().getPort();
    }

    @AfterEach
    void stopGoogle() {
        google.stop(0);
    }

    private Map<String, String> form(HttpExchange x) throws IOException {
        Map<String, String> out = new HashMap<>();
        for (String pair : new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
            String[] kv = pair.split("=", 2);
            out.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return out;
    }

    private void token(HttpExchange x) throws IOException {
        Map<String, String> f = form(x);
        tokenRequests.append(f.get("grant_type")).append(';');
        try {
            if ("authorization_code".equals(f.get("grant_type"))) {
                String expected = challenges.get(f.get("code"));
                String actual = Base64.getUrlEncoder().withoutPadding().encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(f.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));
                if (!actual.equals(expected)) {
                    json(x, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"bad verifier\"}");
                    return;
                }
                json(x, 200, "{\"access_token\":\"at-1\",\"refresh_token\":\"rt-1\",\"expires_in\":3600,\"scope\":\"openid email profile https://www.googleapis.com/auth/drive.appdata\"}");
            } else if ("rt-1".equals(f.get("refresh_token"))) {
                json(x, 200, "{\"access_token\":\"at-2\",\"expires_in\":3600}");
            } else {
                json(x, 400, "{\"error\":\"invalid_grant\"}");
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private void drive(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getPath();
        if (path.equals("/drive/v3/files") && x.getRequestMethod().equals("GET")) {
            json(x, 200, driveFile.get() == null ? "{\"files\":[]}" : "{\"files\":[{\"id\":\"f1\",\"name\":\"" + SyncService.FILE_NAME + "\"}]}");
        } else if (path.equals("/drive/v3/files/f1") && x.getRequestMethod().equals("GET")) {
            byte[] bytes = driveFile.get();
            x.sendResponseHeaders(200, bytes.length);
            x.getResponseBody().write(bytes);
            x.close();
        } else if (path.equals("/drive/v3/files/f1") && x.getRequestMethod().equals("DELETE")) {
            driveFile.set(null);
            x.sendResponseHeaders(204, -1);
            x.close();
        } else {
            json(x, 404, "{}");
        }
    }

    /** Multipart/related: metadata part, then the content between the second blank line and the closing boundary. */
    private void upload(HttpExchange x) throws IOException {
        String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
        String marker = "Content-Type: application/octet-stream\r\n\r\n";
        int start = body.indexOf(marker) + marker.length();
        int end = body.lastIndexOf("\r\n--");
        driveFile.set(body.substring(start, end).getBytes(StandardCharsets.ISO_8859_1));
        json(x, 200, "{\"id\":\"f1\",\"name\":\"" + SyncService.FILE_NAME + "\"}");
    }

    private static void json(HttpExchange x, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        x.getResponseBody().write(bytes);
        x.close();
    }

    /** One installation of the app: its own data folder and keys. */
    private final class Machine {
        final ConnectionStore connections;
        final ScriptStore scripts;
        final GoogleAuthService auth;
        final SyncService sync;

        Machine(Path dir) {
            StorageProperties storage = new StorageProperties(dir);
            SecretCipher cipher = new SecretCipher(storage);
            connections = new ConnectionStore(storage, cipher);
            scripts = new ScriptStore(storage);
            GoogleProperties properties = new GoogleProperties("test-client.apps.googleusercontent.com", "secret", base + "/auth", base + "/token",
                    base + "/userinfo", base + "/drive/v3", base + "/upload/drive/v3", base + "/revoke");
            auth = new GoogleAuthService(properties, storage, cipher);
            sync = new SyncService(auth, new DriveClient(properties, auth), connections, scripts, cipher, storage, 10_000);
        }

        /** The browser step: Google's page is opened, the user agrees, and the code comes back with the state. */
        void signIn() {
            String url = auth.startLogin("http://127.0.0.1:8080/api/google/callback");
            Map<String, String> query = new HashMap<>();
            for (String pair : URI.create(url).getRawQuery().split("&")) {
                String[] kv = pair.split("=", 2);
                query.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
            assertThat(query).containsEntry("code_challenge_method", "S256").containsEntry("access_type", "offline");
            assertThat(query.get("scope")).contains("drive.appdata");
            challenges.put("code-" + query.get("state"), query.get("code_challenge"));
            assertThat(auth.complete("code-" + query.get("state"), query.get("state")).email()).isEqualTo("ana@example.com");
        }
    }

    private static ConnectionInput connection(String name, String password) {
        return new ConnectionInput(name, "postgres-jdbc", "db.local", 5432, "shop", "me", password, null, Map.of(), null);
    }

    @Test
    void connectionsScriptsAndSettingsFollowTheAccountAndStayEncrypted(@TempDir Path dirA, @TempDir Path dirB) throws Exception {
        Machine a = new Machine(dirA.resolve("a"));
        Machine b = new Machine(dirB.resolve("b"));
        a.signIn();
        assertThat(a.sync.enable("correct horse battery")).isFalse();

        SavedConnection created = a.connections.create(connection("prod pg", "s3cret-pw"));
        a.scripts.save("report", created.id(), "select 1");
        SyncService.Result first = a.sync.sync(Map.of("justinbeaver.prefs", "{\"theme\":\"dark\"}"), 1000L);
        assertThat(first.pushed()).isTrue();

        // Google only holds ciphertext: neither the password nor the connection name is in the file
        String cloud = new String(driveFile.get(), StandardCharsets.UTF_8);
        assertThat(cloud).doesNotContain("s3cret-pw").doesNotContain("prod pg").doesNotContain("select 1").contains("pbkdf2");

        b.signIn();
        assertThatThrownBy(() -> b.sync.enable("not the passphrase")).isInstanceOf(GoogleException.class).hasMessageContaining("Wrong sync passphrase");
        assertThat(b.sync.enable("correct horse battery")).isTrue();
        SyncService.Result pulled = b.sync.sync(null, 0L);

        assertThat(pulled.connectionsChanged()).isEqualTo(1);
        assertThat(pulled.scriptsChanged()).isEqualTo(1);
        assertThat(pulled.settings()).containsEntry("justinbeaver.prefs", "{\"theme\":\"dark\"}");
        assertThat(pulled.settingsChanged()).isTrue();
        SavedConnection onB = b.connections.get(created.id());
        assertThat(onB.name()).isEqualTo("prod pg");
        assertThat(onB.password()).isEqualTo("s3cret-pw");
        assertThat(b.scripts.get("report").content()).isEqualTo("select 1");
        assertThat(b.scripts.get("report").connectionId()).isEqualTo(created.id());

        // a second sync with nothing new neither changes nor pushes
        SyncService.Result idle = b.sync.sync(null, 0L);
        assertThat(idle.pushed()).isFalse();
        assertThat(idle.connectionsChanged()).isZero();
    }

    @Test
    void deletionsAndNewerEditsMergeAcrossMachines(@TempDir Path dirA, @TempDir Path dirB) throws Exception {
        Machine a = new Machine(dirA.resolve("a"));
        Machine b = new Machine(dirB.resolve("b"));
        a.signIn();
        a.sync.enable("correct horse battery");
        SavedConnection keep = a.connections.create(connection("keep", "p1"));
        SavedConnection drop = a.connections.create(connection("drop", "p2"));
        a.sync.sync(null, 0L);
        b.signIn();
        b.sync.enable("correct horse battery");
        b.sync.sync(null, 0L);
        assertThat(b.connections.all()).hasSize(2);

        Thread.sleep(5);
        b.connections.delete(drop.id());
        b.connections.update(keep.id(), connection("keep renamed", "p1"));
        b.sync.sync(null, 0L);

        SyncService.Result onA = a.sync.sync(null, 0L);
        assertThat(onA.connectionsChanged()).isEqualTo(2);
        assertThat(a.connections.all()).extracting(SavedConnection::name).containsExactly("keep renamed");
        assertThat(a.connections.tombstones()).containsKey(drop.id());

        // a machine that edits after the other deleted brings the connection back
        Thread.sleep(5);
        a.connections.update(keep.id(), connection("keep again", "p1"));
        a.sync.sync(null, 0L);
        b.sync.sync(null, 0L);
        assertThat(b.connections.get(keep.id()).name()).isEqualTo("keep again");
    }

    @Test
    void signingOutForgetsTheSessionAndAWrongClientIsRefused(@TempDir Path dir) throws Exception {
        Machine a = new Machine(dir);
        a.signIn();
        assertThat(a.auth.accessToken()).isEqualTo("at-1");
        a.auth.signOut();
        assertThat(a.auth.signedIn()).isFalse();
        assertThatThrownBy(() -> a.auth.accessToken()).isInstanceOf(GoogleException.class);
        assertThatThrownBy(() -> a.sync.enable("correct horse battery")).isInstanceOf(GoogleException.class).hasMessageContaining("Sign in");
        // a code that Google did not issue for this sign-in (wrong state) is refused
        assertThatThrownBy(() -> a.auth.complete("x", "unknown-state")).isInstanceOf(GoogleException.class).hasMessageContaining("expired");
        assertThat(Files.exists(dir.resolve("google-account.json"))).isFalse();
    }

    @Test
    void mergeKeepsTheMostRecentChangePerItem() {
        SavedConnection c1 = new SavedConnection("c1", "old", "postgres-jdbc", "h", 1, "d", "u", "p", null, Map.of(), true, null, null);
        SavedConnection c1New = new SavedConnection("c1", "new", "postgres-jdbc", "h", 1, "d", "u", "p", null, Map.of(), true, null, null);
        Snapshot local = new Snapshot(1, List.of(new ConnectionStore.Synced(c1, 100)), Map.of(), List.of(), Map.of("gone", 50L), Map.of("a", "1"), 10);
        Snapshot remote = new Snapshot(1, List.of(new ConnectionStore.Synced(c1New, 200)), Map.of(), List.of(), Map.of(), Map.of("a", "2"), 20);

        Snapshot merged = Snapshot.merge(local, remote);

        assertThat(merged.connections()).singleElement().extracting(s -> s.connection().name()).isEqualTo("new");
        assertThat(merged.deletedScripts()).containsEntry("gone", 50L);
        assertThat(merged.settings()).containsEntry("a", "2");

        Snapshot deletedLater = Snapshot.merge(local, new Snapshot(1, List.of(), Map.of("c1", 150L), List.of(), Map.of(), Map.of(), 0));
        assertThat(deletedLater.connections()).isEmpty();
        Snapshot editedLater = Snapshot.merge(local, new Snapshot(1, List.of(), Map.of("c1", 50L), List.of(), Map.of(), Map.of(), 0));
        assertThat(editedLater.connections()).hasSize(1);
    }

    // ---- explicit "save to Drive" / "import from Drive" ---------------------------------------------------------

    private static SyncService.Transfer all() {
        return new SyncService.Transfer(true, true, true);
    }

    @Test
    void exportSendsOnlyTheChosenKindsAndImportBringsThemBack(@TempDir Path dirA, @TempDir Path dirB) {
        Machine a = new Machine(dirA.resolve("a"));
        Machine b = new Machine(dirB.resolve("b"));
        a.signIn();
        a.sync.enable("correct horse battery");
        SavedConnection created = a.connections.create(connection("prod pg", "s3cret-pw"));
        a.scripts.save("report", created.id(), "select 1");

        SyncService.TransferResult sent = a.sync.export(new SyncService.Transfer(false, true, false), null, 0L);
        assertThat(sent.direction()).isEqualTo("export");
        assertThat(sent.scripts()).isEqualTo(1);
        assertThat(sent.connections()).isZero();
        assertThat(sent.uploaded()).isTrue();
        String cloud = new String(driveFile.get(), StandardCharsets.UTF_8);
        assertThat(cloud).doesNotContain("select 1").doesNotContain("s3cret-pw");

        b.signIn();
        b.sync.enable("correct horse battery");
        SyncService.TransferResult got = b.sync.importFrom(all());
        assertThat(got.scripts()).isEqualTo(1);
        assertThat(got.connections()).isZero();
        assertThat(b.scripts.get("report").content()).isEqualTo("select 1");
        assertThat(b.connections.all()).isEmpty();

        // saving the connection later makes it importable too, password included
        a.sync.export(new SyncService.Transfer(true, false, false), null, 0L);
        SyncService.TransferResult second = b.sync.importFrom(new SyncService.Transfer(true, false, false));
        assertThat(second.connections()).isEqualTo(1);
        assertThat(b.connections.get(created.id()).password()).isEqualTo("s3cret-pw");
    }

    @Test
    void exportReplacesTheCloudVersionAndKeepsWhatOnlyTheCloudHas(@TempDir Path dirA, @TempDir Path dirB) {
        Machine a = new Machine(dirA.resolve("a"));
        Machine b = new Machine(dirB.resolve("b"));
        a.signIn();
        a.sync.enable("correct horse battery");
        a.scripts.save("shared", null, "version A1");
        a.scripts.save("only-a", null, "from a");
        a.sync.export(new SyncService.Transfer(false, true, false), null, 0L);

        b.signIn();
        b.sync.enable("correct horse battery");
        b.scripts.save("shared", null, "version B (newer)");
        b.scripts.save("only-b", null, "from b");
        b.sync.export(new SyncService.Transfer(false, true, false), null, 0L);

        // B's export replaced "shared" and added "only-b", and did not delete "only-a"
        SyncService.TransferResult onA = a.sync.importFrom(new SyncService.Transfer(false, true, false));
        assertThat(onA.scripts()).isEqualTo(2);
        assertThat(a.scripts.get("shared").content()).isEqualTo("version B (newer)");
        assertThat(a.scripts.get("only-a").content()).isEqualTo("from a");
        assertThat(a.scripts.get("only-b").content()).isEqualTo("from b");
    }

    @Test
    void importReplacesTheSameNamedScriptEvenWhenLocalIsNewerAndNeverDeletes(@TempDir Path dirA, @TempDir Path dirB) throws Exception {
        Machine a = new Machine(dirA.resolve("a"));
        Machine b = new Machine(dirB.resolve("b"));
        a.signIn();
        a.sync.enable("correct horse battery");
        a.scripts.save("report", null, "from the cloud");
        a.sync.export(new SyncService.Transfer(false, true, false), null, 0L);

        b.signIn();
        b.sync.enable("correct horse battery");
        Thread.sleep(20);
        b.scripts.save("report", null, "local and newer");
        b.scripts.save("local-only", null, "keep me");

        SyncService.TransferResult got = b.sync.importFrom(new SyncService.Transfer(false, true, false));
        assertThat(got.scripts()).isEqualTo(1);
        assertThat(b.scripts.get("report").content()).isEqualTo("from the cloud");
        assertThat(b.scripts.get("local-only").content()).isEqualTo("keep me");

        // importing again changes nothing
        assertThat(b.sync.importFrom(new SyncService.Transfer(false, true, false)).scripts()).isZero();
    }

    @Test
    void preferencesTravelWithAnExportAndComeBackWithAnImport(@TempDir Path dirA, @TempDir Path dirB) {
        Machine a = new Machine(dirA.resolve("a"));
        Machine b = new Machine(dirB.resolve("b"));
        a.signIn();
        a.sync.enable("correct horse battery");
        a.sync.export(new SyncService.Transfer(false, false, true), Map.of("justinbeaver.prefs", "{\"accentColor\":\"#3b82f6\"}"), 5L);

        b.signIn();
        b.sync.enable("correct horse battery");
        SyncService.TransferResult got = b.sync.importFrom(new SyncService.Transfer(false, false, true));
        assertThat(got.preferences()).isTrue();
        assertThat(got.settings()).containsEntry("justinbeaver.prefs", "{\"accentColor\":\"#3b82f6\"}");
    }

    @Test
    void importNeedsACloudCopyAndSomethingChosen(@TempDir Path dirA) {
        Machine a = new Machine(dirA.resolve("a"));
        a.signIn();
        a.sync.enable("correct horse battery");
        assertThatThrownBy(() -> a.sync.importFrom(all())).isInstanceOf(GoogleException.class).hasMessageContaining("Nothing is saved");
        assertThatThrownBy(() -> a.sync.export(new SyncService.Transfer(false, false, false), null, 0L))
                .isInstanceOf(GoogleException.class).hasMessageContaining("Choose what to move");
        assertThatThrownBy(() -> a.sync.export(null, null, 0L)).isInstanceOf(GoogleException.class);
    }

    @Test
    void anotherPassphraseCannotImport(@TempDir Path dirA) {
        Machine a = new Machine(dirA.resolve("a"));
        a.signIn();
        a.sync.enable("correct horse battery");
        a.sync.export(all(), null, 0L);
        a.sync.disable();
        assertThatThrownBy(() -> a.sync.importFrom(all())).isInstanceOf(GoogleException.class).hasMessageContaining("Turn sync on first");
    }
}
