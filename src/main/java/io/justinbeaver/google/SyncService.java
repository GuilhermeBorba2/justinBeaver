package io.justinbeaver.google;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.justinbeaver.config.StorageProperties;
import io.justinbeaver.connection.ConnectionStore;
import io.justinbeaver.connection.ConnectionStore.Synced;
import io.justinbeaver.connection.SecretCipher;
import io.justinbeaver.script.ScriptStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Keeps the connections, saved scripts and preferences identical on every machine signed in to the same Google
 * account, through one encrypted file in the app's hidden Drive folder. {@link #sync} pulls, merges by most recent
 * change per item, writes what changed locally, and pushes when the merge differs from the cloud copy.
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    static final String FILE_NAME = "justinbeaver-sync.v1.json";
    private static final long MAX_BYTES = 20L * 1024 * 1024;

    public record Status(boolean enabled, Long lastSyncAt, String lastError) {
    }

    /** What a sync did, for the page: counts of local changes pulled, whether it pushed, and the merged preferences. */
    public record Result(int connectionsChanged, int scriptsChanged, boolean pushed, boolean settingsChanged,
                         Map<String, String> settings, long settingsUpdatedAt, long at) {
    }

    private final GoogleAuthService auth;
    private final DriveClient drive;
    private final ConnectionStore connections;
    private final ScriptStore scripts;
    private final SecretCipher cipher;
    private final Path stateFile;
    private final int iterations;
    private final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** Local sync state: the passphrase (encrypted with the machine's key), the preferences copy and the last outcome. */
    private final Map<String, Object> state = new LinkedHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public SyncService(GoogleAuthService auth, DriveClient drive, ConnectionStore connections, ScriptStore scripts,
                       SecretCipher cipher, StorageProperties storage) {
        this(auth, drive, connections, scripts, cipher, storage, SyncCrypto.ITERATIONS);
    }

    SyncService(GoogleAuthService auth, DriveClient drive, ConnectionStore connections, ScriptStore scripts,
                SecretCipher cipher, StorageProperties storage, int iterations) {
        this.auth = auth;
        this.drive = drive;
        this.connections = connections;
        this.scripts = scripts;
        this.cipher = cipher;
        this.iterations = iterations;
        this.stateFile = storage.dirOrDefault().resolve("sync-state.json");
        loadState();
    }

    public synchronized Status status() {
        return new Status(passphrase() != null && auth.signedIn(), state.get("lastSyncAt") instanceof Number n ? n.longValue() : null, (String) state.get("lastError"));
    }

    /**
     * Turns sync on for this machine. When the cloud already has a copy the passphrase must open it (it is the one set
     * on the first machine); otherwise it becomes the passphrase of a new copy.
     *
     * @return whether a cloud copy already existed
     */
    public synchronized boolean enable(String passphrase) {
        requireSignedIn();
        if (passphrase == null || passphrase.length() < 8) {
            throw new GoogleException(400, "The sync passphrase needs at least 8 characters");
        }
        Optional<DriveClient.DriveFile> remote = drive.find(FILE_NAME);
        if (remote.isPresent()) {
            SyncCrypto.decrypt(passphrase, drive.download(remote.get().id()));
        }
        state.put("passphrase", cipher.encrypt(passphrase));
        state.remove("lastError");
        saveState();
        return remote.isPresent();
    }

    /** Whether the account already has a cloud copy (then the passphrase to enter is the one set on the first computer). */
    public synchronized boolean cloudCopyExists() {
        requireSignedIn();
        return drive.find(FILE_NAME).isPresent();
    }

    /** Stops syncing on this machine (the cloud copy stays). */
    public synchronized void disable() {
        state.remove("passphrase");
        state.remove("lastSyncAt");
        state.remove("lastError");
        saveState();
    }

    /** Deletes the cloud copy; every machine's own data stays. */
    public synchronized void deleteCloudCopy() {
        requireSignedIn();
        drive.find(FILE_NAME).ifPresent(f -> drive.delete(f.id()));
    }

    @SuppressWarnings("unchecked")
    public synchronized Result sync(Map<String, String> settings, long settingsUpdatedAt) {
        requireSignedIn();
        String passphrase = passphrase();
        if (passphrase == null) {
            throw new GoogleException(409, "Turn sync on first: choose a sync passphrase");
        }
        try {
            Map<String, String> storedSettings = state.get("settings") instanceof Map<?, ?> m ? (Map<String, String>) m : Map.of();
            long storedUpdatedAt = state.get("settingsUpdatedAt") instanceof Number n ? n.longValue() : 0L;
            // the page's copy wins when it is newer than what the last sync left here
            boolean pageIsNewer = settings != null && settingsUpdatedAt > storedUpdatedAt;
            Snapshot local = new Snapshot(1, connections.exportAll(), connections.tombstones(), scripts.exportAll(), scripts.tombstones(),
                    pageIsNewer ? settings : storedSettings, pageIsNewer ? settingsUpdatedAt : storedUpdatedAt).normalized();

            Optional<DriveClient.DriveFile> remoteFile = drive.find(FILE_NAME);
            Snapshot remote = null;
            if (remoteFile.isPresent()) {
                byte[] plain = SyncCrypto.decrypt(passphrase, drive.download(remoteFile.get().id()));
                remote = json.readValue(plain, Snapshot.class).normalized();
            }
            Snapshot merged = remote == null ? local : Snapshot.merge(local, remote);

            int connectionsChanged = 0;
            int scriptsChanged = 0;
            if (remote != null) {
                Map<String, Long> localConnections = new HashMap<>();
                local.connections().forEach(c -> localConnections.put(c.connection().id(), c.updatedAt()));
                List<Synced> upserts = merged.connections().stream()
                        .filter(c -> !Long.valueOf(c.updatedAt()).equals(localConnections.get(c.connection().id()))).toList();
                Map<String, Long> localScripts = new HashMap<>();
                local.scripts().forEach(s -> localScripts.put(s.name(), s.modified()));
                List<ScriptStore.Synced> scriptUpserts = merged.scripts().stream()
                        .filter(s -> !Long.valueOf(s.modified()).equals(localScripts.get(s.name()))).toList();
                connectionsChanged = upserts.size() + countNew(merged.deletedConnections(), local.deletedConnections());
                scriptsChanged = scriptUpserts.size() + countNew(merged.deletedScripts(), local.deletedScripts());
                if (!upserts.isEmpty() || !merged.deletedConnections().equals(local.deletedConnections())) {
                    connections.applyMerged(upserts, merged.deletedConnections());
                }
                if (!scriptUpserts.isEmpty() || !merged.deletedScripts().equals(local.deletedScripts())) {
                    scripts.applyMerged(scriptUpserts, merged.deletedScripts());
                }
            }

            boolean push = remote == null || !digest(merged).equals(digest(remote));
            if (push) {
                byte[] plain = json.writeValueAsBytes(merged);
                if (plain.length > MAX_BYTES) {
                    throw new GoogleException(413, "Too much to sync (" + plain.length / 1024 / 1024 + " MB): delete large scripts");
                }
                drive.upload(FILE_NAME, SyncCrypto.encrypt(passphrase, plain, iterations), remoteFile.map(DriveClient.DriveFile::id).orElse(null));
            }

            boolean settingsChanged = remote != null && !merged.settings().equals(local.settings());
            state.put("settings", merged.settings());
            state.put("settingsUpdatedAt", merged.settingsUpdatedAt());
            long now = System.currentTimeMillis();
            state.put("lastSyncAt", now);
            state.remove("lastError");
            saveState();
            return new Result(connectionsChanged, scriptsChanged, push, settingsChanged, merged.settings(), merged.settingsUpdatedAt(), now);
        } catch (IOException e) {
            throw fail(new GoogleException(500, "Cannot sync: " + e.getMessage(), e));
        } catch (GoogleException e) {
            throw fail(e);
        }
    }

    /** What an explicit export or import moves: any of the three kinds, chosen by the user. */
    public record Transfer(boolean connections, boolean scripts, boolean preferences) {
        boolean any() {
            return connections || scripts || preferences;
        }
    }

    /**
     * What an export or import did: how many connections and scripts it moved, whether anything was uploaded, and the
     * preferences now in effect (an import hands the cloud's to the page).
     */
    public record TransferResult(String direction, int connections, int scripts, boolean preferences, boolean uploaded,
                                 Map<String, String> settings, long settingsUpdatedAt, long at) {
    }

    /**
     * "Save to Google Drive": puts this computer's connections, scripts and/or preferences in the cloud copy, replacing
     * the cloud's version of the same items whatever their dates. Items only the cloud has stay; nothing is deleted
     * there, and nothing here changes.
     */
    @SuppressWarnings("unchecked")
    public synchronized TransferResult export(Transfer what, Map<String, String> settings, long settingsUpdatedAt) {
        String passphrase = requireReady(what);
        try {
            Map<String, String> storedSettings = state.get("settings") instanceof Map<?, ?> m ? (Map<String, String>) m : Map.of();
            long storedUpdatedAt = state.get("settingsUpdatedAt") instanceof Number n ? n.longValue() : 0L;
            boolean page = settings != null;
            Snapshot local = new Snapshot(1, connections.exportAll(), connections.tombstones(), scripts.exportAll(), scripts.tombstones(),
                    page ? settings : storedSettings, page ? Math.max(settingsUpdatedAt, System.currentTimeMillis()) : storedUpdatedAt).normalized();
            Optional<DriveClient.DriveFile> remoteFile = drive.find(FILE_NAME);
            Snapshot remote = remoteFile.isPresent() ? readRemote(passphrase, remoteFile.get()) : new Snapshot(1, null, null, null, null, null, 0L);
            Snapshot merged = Snapshot.overlay(remote, local, what.connections(), what.scripts(), what.preferences());
            boolean upload = remoteFile.isEmpty() || !digest(merged).equals(digest(remote));
            if (upload) {
                byte[] plain = json.writeValueAsBytes(merged);
                if (plain.length > MAX_BYTES) {
                    throw new GoogleException(413, "Too much to save (" + plain.length / 1024 / 1024 + " MB): delete large scripts");
                }
                drive.upload(FILE_NAME, SyncCrypto.encrypt(passphrase, plain, iterations), remoteFile.map(DriveClient.DriveFile::id).orElse(null));
            }
            if (what.preferences()) {
                state.put("settings", merged.settings());
                state.put("settingsUpdatedAt", merged.settingsUpdatedAt());
            }
            long now = System.currentTimeMillis();
            state.put("lastSyncAt", now);
            state.remove("lastError");
            saveState();
            return new TransferResult("export", what.connections() ? local.connections().size() : 0, what.scripts() ? local.scripts().size() : 0,
                    what.preferences(), upload, merged.settings(), merged.settingsUpdatedAt(), now);
        } catch (IOException e) {
            throw fail(new GoogleException(500, "Cannot save to Google Drive: " + e.getMessage(), e));
        } catch (GoogleException e) {
            throw fail(e);
        }
    }

    /**
     * "Import from Google Drive": brings the cloud's connections, scripts and/or preferences here, replacing the local
     * version of the same items whatever their dates. Items only this computer has stay; nothing is deleted here.
     */
    @SuppressWarnings("unchecked")
    public synchronized TransferResult importFrom(Transfer what) {
        String passphrase = requireReady(what);
        try {
            Optional<DriveClient.DriveFile> remoteFile = drive.find(FILE_NAME);
            if (remoteFile.isEmpty()) {
                throw new GoogleException(404, "Nothing is saved on Google Drive yet: save from a computer first");
            }
            Snapshot remote = readRemote(passphrase, remoteFile.get());
            Map<String, String> storedSettings = state.get("settings") instanceof Map<?, ?> m ? (Map<String, String>) m : Map.of();
            long storedUpdatedAt = state.get("settingsUpdatedAt") instanceof Number n ? n.longValue() : 0L;
            Snapshot local = new Snapshot(1, connections.exportAll(), connections.tombstones(), scripts.exportAll(), scripts.tombstones(),
                    storedSettings, storedUpdatedAt).normalized();
            Snapshot merged = Snapshot.overlay(local, remote, what.connections(), what.scripts(), what.preferences());

            List<Synced> connectionUpserts = what.connections()
                    ? merged.connections().stream().filter(c -> !local.connections().contains(c)).toList() : List.of();
            List<ScriptStore.Synced> scriptUpserts = what.scripts()
                    ? merged.scripts().stream().filter(x -> !local.scripts().contains(x)).toList() : List.of();
            if (!connectionUpserts.isEmpty()) {
                connections.applyMerged(connectionUpserts, Map.of());
            }
            if (!scriptUpserts.isEmpty()) {
                scripts.applyMerged(scriptUpserts, Map.of());
            }
            if (what.preferences()) {
                state.put("settings", merged.settings());
                state.put("settingsUpdatedAt", merged.settingsUpdatedAt());
            }
            long now = System.currentTimeMillis();
            state.put("lastSyncAt", now);
            state.remove("lastError");
            saveState();
            return new TransferResult("import", connectionUpserts.size(), scriptUpserts.size(), what.preferences(), false,
                    merged.settings(), merged.settingsUpdatedAt(), now);
        } catch (IOException e) {
            throw fail(new GoogleException(500, "Cannot import from Google Drive: " + e.getMessage(), e));
        } catch (GoogleException e) {
            throw fail(e);
        }
    }

    private String requireReady(Transfer what) {
        requireSignedIn();
        if (what == null || !what.any()) {
            throw new GoogleException(400, "Choose what to move: connections, scripts or preferences");
        }
        String passphrase = passphrase();
        if (passphrase == null) {
            throw new GoogleException(409, "Turn sync on first: choose a sync passphrase");
        }
        return passphrase;
    }

    private Snapshot readRemote(String passphrase, DriveClient.DriveFile file) throws IOException {
        return json.readValue(SyncCrypto.decrypt(passphrase, drive.download(file.id())), Snapshot.class).normalized();
    }

    private GoogleException fail(GoogleException e) {
        state.put("lastError", e.getMessage());
        saveState();
        log.warn("Sync failed: {}", e.getMessage());
        return e;
    }

    private static int countNew(Map<String, Long> merged, Map<String, Long> local) {
        return (int) merged.entrySet().stream().filter(e -> !e.getValue().equals(local.get(e.getKey()))).count();
    }

    /** Hash of the content, ignoring order, to tell whether the cloud copy already holds this state. */
    private String digest(Snapshot snapshot) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(snapshot.normalized())));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void requireSignedIn() {
        if (!auth.signedIn()) {
            throw new GoogleException(401, "Sign in with Google first");
        }
    }

    private String passphrase() {
        Object enc = state.get("passphrase");
        if (enc == null) {
            return null;
        }
        try {
            return cipher.decrypt((String) enc);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private void loadState() {
        try {
            if (Files.isRegularFile(stateFile)) {
                JsonNode node = json.readTree(stateFile.toFile());
                state.putAll(json.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<TreeMap<String, Object>>() { }));
            }
        } catch (IOException e) {
            log.warn("Cannot read {}: {}", stateFile, e.getMessage());
        }
    }

    private void saveState() {
        try {
            Files.createDirectories(stateFile.getParent());
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            Files.write(tmp, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(state));
            try {
                Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException notPosix) {
                // Windows
            }
            Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Cannot save {}: {}", stateFile, e.getMessage());
        }
    }

    static Instant at(long millis) {
        return Instant.ofEpochMilli(millis);
    }
}
