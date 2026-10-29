package io.justinbeaver.connection;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.justinbeaver.network.SshConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DbeaverImporterTest {

    private final ObjectMapper json = new ObjectMapper();

    /** Encrypts like DBeaver: random IV first, then AES/CBC with its fixed key. */
    private static byte[] encrypt(String text) throws Exception {
        byte[] iv = new byte[16];
        new java.security.SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(HexFormat.of().parseHex("babb4a9f774ab853c96c2d653dfe544a"), "AES"),
                new IvParameterSpec(iv));
        byte[] body = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[16 + body.length];
        System.arraycopy(iv, 0, out, 0, 16);
        System.arraycopy(body, 0, out, 16, body.length);
        return out;
    }

    @Test
    void readsDbeaverCredentials() throws Exception {
        String secrets = "{\"pg-1\":{\"#connection\":{\"user\":\"app\",\"password\":\"s3cret\"}}}";
        assertThat(new String(DbeaverImporter.decrypt(encrypt(secrets)), StandardCharsets.UTF_8)).isEqualTo(secrets);
    }

    @Test
    void mapsAConnectionWithFolderTypeSshAndShowAllDatabases() throws Exception {
        var connection = json.readTree("""
                {"provider":"postgresql","driver":"postgres-jdbc","name":"Prod DB","folder":"Clients/ACME","read-only":true,
                 "configuration":{"host":"db.acme","port":"5433","database":"shop","type":"prod","configurationType":"MANUAL",
                   "properties":{"connectTimeout":"20"},
                   "provider-properties":{"@dbeaver-show-non-default-db@":"true"},
                   "handlers":{"ssh_tunnel":{"enabled":true,"properties":{"host":"bastion","port":"2222","authType":"PASSWORD"}}}}}
                """);
        var secrets = json.readTree("""
                {"#connection":{"user":"app","password":"pw"},"network/ssh_tunnel":{"user":"jump","password":"sshpw"}}""");

        ConnectionInput in = DbeaverImporter.toInput(connection, secrets);

        assertThat(in.name()).isEqualTo("Prod DB");
        assertThat(in.host()).isEqualTo("db.acme");
        assertThat(in.port()).isEqualTo(5433);
        assertThat(in.user()).isEqualTo("app");
        assertThat(in.password()).isEqualTo("pw");
        assertThat(in.connectionType()).isEqualTo(ConnectionType.PROD);
        assertThat(in.readOnly()).isTrue();
        assertThat(in.showAllDatabases()).isTrue();
        assertThat(in.folder()).isEqualTo("Clients/ACME");
        assertThat(in.properties()).containsEntry("connectTimeout", "20");
        assertThat(in.ssh()).isNotNull();
        assertThat(in.ssh().host()).isEqualTo("bastion");
        assertThat(in.ssh().port()).isEqualTo(2222);
        assertThat(in.ssh().user()).isEqualTo("jump");
        assertThat(in.ssh().auth()).isEqualTo(SshConfig.Auth.PASSWORD);
        assertThat(in.ssh().password()).isEqualTo("sshpw");
    }

    @Test
    void urlConnectionsKeepOnlyTheUrl() throws Exception {
        var connection = json.readTree("""
                {"driver":"oracle_thin","name":"ora","configuration":{"host":"h","url":"jdbc:oracle:thin:@h:1521/x","configurationType":"URL"}}""");
        ConnectionInput in = DbeaverImporter.toInput(connection, json.createObjectNode());
        assertThat(in.url()).isEqualTo("jdbc:oracle:thin:@h:1521/x");
        assertThat(in.host()).isNull();
        assertThat(in.password()).isNull();
        assertThat(in.connectionType()).isEqualTo(ConnectionType.DEV);
    }

    @Test
    void acceptsTheProjectFolderOrTheFileItself(@TempDir Path dir) throws Exception {
        Path dbeaver = Files.createDirectories(dir.resolve("General/.dbeaver"));
        Files.writeString(dbeaver.resolve("data-sources.json"), "{}");
        assertThat(DbeaverImporter.resolve(dir.resolve("General").toString())).isEqualTo(dbeaver);
        assertThat(DbeaverImporter.resolve(dbeaver.resolve("data-sources.json").toString())).isEqualTo(dbeaver);
    }
}
