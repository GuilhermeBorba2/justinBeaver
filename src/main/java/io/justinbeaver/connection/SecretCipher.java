package io.justinbeaver.connection;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import io.justinbeaver.config.StorageProperties;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM for saved passwords. The key is a random file next to the connections file
 * (mode 600 where the OS supports it). This protects against the connections file being copied or
 * backed up on its own, not against someone who can read the whole data directory as the same
 * user; moving the key to the OS keychain is the next step.
 */
@Component
public class SecretCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Path keyFile;
    private final SecureRandom random = new SecureRandom();
    private volatile SecretKey key;

    public SecretCipher(StorageProperties storage) {
        this.keyFile = storage.dirOrDefault().resolve("secret.key");
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot encrypt secret", e);
        }
    }

    public String decrypt(String encrypted) {
        try {
            byte[] all = Base64.getDecoder().decode(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot decrypt secret (wrong or missing key file?)", e);
        }
    }

    private SecretKey key() {
        SecretKey local = key;
        if (local == null) {
            synchronized (this) {
                local = key;
                if (local == null) {
                    key = local = new SecretKeySpec(loadOrCreateKey(), "AES");
                }
            }
        }
        return local;
    }

    private byte[] loadOrCreateKey() {
        try {
            Files.createDirectories(keyFile.getParent());
            try {
                createPrivateFile(keyFile);
                byte[] fresh = new byte[32];
                random.nextBytes(fresh);
                Files.writeString(keyFile, Base64.getEncoder().encodeToString(fresh));
                return fresh;
            } catch (FileAlreadyExistsException exists) {
                return Base64.getDecoder().decode(Files.readString(keyFile).trim());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot access key file " + keyFile, e);
        }
    }

    static void createPrivateFile(Path file) throws IOException {
        try {
            Files.createFile(file, java.nio.file.attribute.PosixFilePermissions
                    .asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException notPosix) {
            Files.createFile(file);
        }
    }
}
