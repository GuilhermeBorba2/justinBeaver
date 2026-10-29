package io.justinbeaver.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import io.justinbeaver.config.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectionStoreTest {

    private ConnectionStore store(Path dir) {
        StorageProperties storage = new StorageProperties(dir);
        return new ConnectionStore(storage, new SecretCipher(storage));
    }

    private ConnectionInput input(String password) {
        return new ConnectionInput("local pg", "postgres-jdbc", "localhost", 5432, "app", "me", password, null,
                Map.of("sslmode", "require"), null);
    }

    @Test
    void passwordIsEncryptedAtRestAndRoundTrips(@TempDir Path dir) throws Exception {
        ConnectionStore store = store(dir);

        SavedConnection saved = store.create(input("pw-123"));

        assertThat(Files.readString(dir.resolve("connections.json"))).doesNotContain("pw-123");
        assertThat(store(dir).get(saved.id()).password()).isEqualTo("pw-123");
        assertThat(saved.autoCommit()).isTrue();
        assertThat(saved.properties()).containsEntry("sslmode", "require");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("secret.key"))))
                .isEqualTo("rw-------");
    }

    @Test
    void updateWithoutPasswordKeepsTheStoredOne(@TempDir Path dir) {
        ConnectionStore store = store(dir);
        SavedConnection saved = store.create(input("pw-123"));

        SavedConnection updated = store.update(saved.id(), new ConnectionInput("renamed", "postgres-jdbc",
                "db", 5432, "app", "me", null, null, Map.of(), false));

        assertThat(updated.name()).isEqualTo("renamed");
        assertThat(updated.password()).isEqualTo("pw-123");
        assertThat(updated.autoCommit()).isFalse();
    }

    @Test
    void sshSecretsAreEncryptedAndKeptOnUpdate(@TempDir Path dir) throws Exception {
        ConnectionStore store = store(dir);
        io.justinbeaver.network.SshConfig ssh = new io.justinbeaver.network.SshConfig(true, "bastion", 2222, "ops",
                io.justinbeaver.network.SshConfig.Auth.KEY, "ssh-pw", "/home/me/.ssh/id_ed25519", "key-pass", true);
        SavedConnection saved = store.create(new ConnectionInput("via ssh", "postgres-jdbc", "db", 5432, "app", "me", "db-pw",
                null, Map.of(), true, ssh, null));

        String onDisk = Files.readString(dir.resolve("connections.json"));
        assertThat(onDisk).doesNotContain("ssh-pw").doesNotContain("key-pass").contains("bastion");
        assertThat(store(dir).get(saved.id()).ssh().passphrase()).isEqualTo("key-pass");

        SavedConnection updated = store.update(saved.id(), new ConnectionInput("via ssh", "postgres-jdbc", "db", 5432, "app",
                "me", null, null, Map.of(), true, ssh.withSecrets(null, null), null));
        assertThat(updated.ssh().password()).isEqualTo("ssh-pw");
        assertThat(updated.ssh().passphrase()).isEqualTo("key-pass");
        assertThat(updated.ssh().port()).isEqualTo(2222);

        ConnectionView view = ConnectionView.of(updated, false);
        assertThat(view.ssh().hasPassphrase()).isTrue();
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(view))
                .doesNotContain("ssh-pw").doesNotContain("key-pass").doesNotContain("db-pw");
    }

    @Test
    void deleteAndMissingIds(@TempDir Path dir) {
        ConnectionStore store = store(dir);
        SavedConnection saved = store.create(input("x"));

        store.delete(saved.id());

        assertThat(store.all()).isEmpty();
        assertThatThrownBy(() -> store.get(saved.id())).isInstanceOf(ConnectionNotFoundException.class);
        assertThatThrownBy(() -> store.delete(saved.id())).isInstanceOf(ConnectionNotFoundException.class);
    }

    @Test
    void wrongKeyCannotDecrypt(@TempDir Path dir, @TempDir Path otherDir) {
        SavedConnection saved = store(dir).create(input("pw"));
        // same connections file, different key
        ConnectionStore wrong = new ConnectionStore(new StorageProperties(dir),
                new SecretCipher(new StorageProperties(otherDir)));

        assertThatThrownBy(() -> wrong.get(saved.id())).isInstanceOf(IllegalStateException.class);
    }
}
