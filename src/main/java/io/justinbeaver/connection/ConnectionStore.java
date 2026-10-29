package io.justinbeaver.connection;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.justinbeaver.config.StorageProperties;
import io.justinbeaver.network.SshConfig;
import io.justinbeaver.network.SslConfig;
import org.springframework.stereotype.Component;

/**
 * Saved connections in {@code connections.json}; passwords are AES-GCM encrypted at rest.
 * Small file, whole-file read/write under a lock.
 */
@Component
public class ConnectionStore {

    private final Path file;
    private final SecretCipher cipher;
    private final ObjectMapper json = new ObjectMapper();

    public ConnectionStore(StorageProperties storage, SecretCipher cipher) {
        this.file = storage.dirOrDefault().resolve("connections.json");
        this.cipher = cipher;
    }

    public synchronized List<SavedConnection> all() {
        return read().stream().map(this::decrypt).toList();
    }

    public synchronized SavedConnection get(String id) {
        return read().stream().filter(s -> s.id().equals(id)).findFirst().map(this::decrypt)
                .orElseThrow(() -> new ConnectionNotFoundException(id));
    }

    public synchronized SavedConnection create(ConnectionInput in) {
        FileModel model = readModel();
        Stored created = encrypt(UUID.randomUUID().toString(), in, null);
        model.connections().add(created);
        write(model);
        return decrypt(created);
    }

    public synchronized SavedConnection update(String id, ConnectionInput in) {
        FileModel model = readModel();
        List<Stored> all = model.connections();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(id)) {
                Stored updated = encrypt(id, in, all.get(i));
                all.set(i, updated);
                write(model);
                return decrypt(updated);
            }
        }
        throw new ConnectionNotFoundException(id);
    }

    public synchronized void delete(String id) {
        FileModel model = readModel();
        if (!model.connections().removeIf(s -> s.id().equals(id))) {
            throw new ConnectionNotFoundException(id);
        }
        model.deleted().put(id, System.currentTimeMillis());
        write(model);
    }

    /** A connection with the moment it last changed, as synchronized between machines. */
    public record Synced(SavedConnection connection, long updatedAt) {
    }

    /** Every connection with its secrets in the clear, for the encrypted cloud copy; never send this to the UI. */
    public synchronized List<Synced> exportAll() {
        long fallback = fileModified();
        return read().stream().map(s -> new Synced(decrypt(s), s.updatedAt() == null ? fallback : s.updatedAt())).toList();
    }

    /** Ids of connections deleted here (or elsewhere) and when, so a deletion survives the merge. */
    public synchronized Map<String, Long> tombstones() {
        return new java.util.TreeMap<>(readModel().deleted());
    }

    /**
     * Applies the merged state: each upsert replaces or adds the connection with the given id and time; each deletion
     * removes it, unless the local copy changed after it was deleted.
     */
    public synchronized void applyMerged(List<Synced> upserts, Map<String, Long> deletions) {
        FileModel model = readModel();
        List<Stored> all = model.connections();
        for (Synced up : upserts) {
            Stored stored = toStored(up.connection(), up.updatedAt());
            boolean replaced = false;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).id().equals(stored.id())) {
                    all.set(i, stored);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                all.add(stored);
            }
            model.deleted().remove(stored.id());
        }
        deletions.forEach((id, at) -> {
            all.removeIf(s -> s.id().equals(id) && (s.updatedAt() == null || s.updatedAt() <= at));
            model.deleted().merge(id, at, Math::max);
        });
        write(model);
    }

    private long fileModified() {
        try {
            return Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    private Stored toStored(SavedConnection c, long updatedAt) {
        SshConfig ssh = c.ssh() == null ? null : c.ssh().withSecrets(encryptOrNull(c.ssh().password()), encryptOrNull(c.ssh().passphrase()));
        return new Stored(c.id(), c.name(), c.driverId(), c.host(), c.port(), c.database(), c.user(), encryptOrNull(c.password()),
                c.url(), c.properties(), c.autoCommit(), ssh, c.ssl(), c.connectionType(), c.readOnly(), c.showAllDatabases(), c.folder(), updatedAt);
    }

    private Stored encrypt(String id, ConnectionInput in, Stored existing) {
        String passwordEnc = in.password() == null ? (existing == null ? null : existing.passwordEnc()) : cipher.encrypt(in.password());
        SshConfig ssh = null;
        if (in.ssh() != null) {
            SshConfig old = existing == null ? null : existing.ssh();
            // the stored SshConfig carries ciphertext in its secret fields; null input keeps the old secret
            String sshPassword = in.ssh().password() == null ? (old == null ? null : old.password()) : encryptOrNull(in.ssh().password());
            String passphrase = in.ssh().passphrase() == null ? (old == null ? null : old.passphrase()) : encryptOrNull(in.ssh().passphrase());
            ssh = in.ssh().withSecrets(sshPassword, passphrase);
        }
        return new Stored(id, in.name(), in.driverId(), in.host(), in.port(), in.database(), in.user(), passwordEnc,
                in.url(), in.properties(), in.autoCommit() == null || in.autoCommit(), ssh, in.ssl(),
                in.connectionType() == null ? ConnectionType.DEV : in.connectionType(), in.readOnly() != null && in.readOnly(),
                in.showAllDatabases() != null && in.showAllDatabases(), blankToNull(in.folder()), System.currentTimeMillis());
    }

    /** Folder path in the navigator ("Clients/ACME"); blank means top level. */
    private static String blankToNull(String folder) {
        if (folder == null) {
            return null;
        }
        String trimmed = folder.strip().replaceAll("\\s*/\\s*", "/").replaceAll("^/+|/+$", "");
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String encryptOrNull(String plain) {
        return plain == null || plain.isEmpty() ? null : cipher.encrypt(plain);
    }

    private String decryptOrNull(String encrypted) {
        return encrypted == null ? null : cipher.decrypt(encrypted);
    }

    private SavedConnection decrypt(Stored s) {
        SshConfig ssh = s.ssh() == null ? null : s.ssh().withSecrets(decryptOrNull(s.ssh().password()), decryptOrNull(s.ssh().passphrase()));
        return new SavedConnection(s.id(), s.name(), s.driverId(), s.host(), s.port(), s.database(), s.user(),
                decryptOrNull(s.passwordEnc()), s.url(), s.properties() == null ? Map.of() : s.properties(), s.autoCommit(),
                ssh, s.ssl(), s.connectionType(), s.readOnly() != null && s.readOnly(),
                s.showAllDatabases() != null && s.showAllDatabases(), s.folder());
    }

    private List<Stored> read() {
        return readModel().connections();
    }

    private FileModel readModel() {
        if (!Files.exists(file)) {
            return new FileModel(null, null);
        }
        try {
            FileModel model = json.readValue(file.toFile(), FileModel.class);
            // mutable copies: callers edit them and write the model back
            return new FileModel(new ArrayList<>(model.connections()), new java.util.TreeMap<>(model.deleted()));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    private void write(FileModel model) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.deleteIfExists(tmp);
            SecretCipher.createPrivateFile(tmp);
            json.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), model);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    record FileModel(List<Stored> connections, Map<String, Long> deleted) {
        FileModel {
            connections = connections == null ? new ArrayList<>() : connections;
            deleted = deleted == null ? new java.util.TreeMap<>() : deleted;
        }
    }

    /** On disk. {@code ssh.password} and {@code ssh.passphrase} hold ciphertext here, like {@code passwordEnc}. */
    record Stored(String id, String name, String driverId, String host, Integer port, String database, String user,
                  String passwordEnc, String url, Map<String, String> properties, boolean autoCommit, SshConfig ssh,
                  SslConfig ssl, ConnectionType connectionType, Boolean readOnly, Boolean showAllDatabases, String folder,
                  Long updatedAt) {
    }
}
