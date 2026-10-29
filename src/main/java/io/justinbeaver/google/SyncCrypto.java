package io.justinbeaver.google;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The cloud copy is encrypted here, before it leaves the machine: AES-256-GCM with a key derived from the user's sync
 * passphrase (PBKDF2-HMAC-SHA256). Google only ever sees ciphertext, so it cannot read the saved database passwords,
 * and a wrong passphrase is detected by the GCM tag.
 */
final class SyncCrypto {

    static final int ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private SyncCrypto() {
    }

    /** The envelope stored in Drive: a JSON document with the parameters and the base64 ciphertext. */
    static byte[] encrypt(String passphrase, byte[] plain, int iterations) {
        try {
            byte[] salt = new byte[SALT_BYTES];
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(salt);
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, iterations), new GCMParameterSpec(TAG_BITS, iv));
            byte[] data = cipher.doFinal(plain);
            Base64.Encoder b64 = Base64.getEncoder();
            return JSON.writeValueAsBytes(new java.util.LinkedHashMap<String, Object>() {
                {
                    put("app", "justinbeaver");
                    put("v", 1);
                    put("kdf", "pbkdf2-hmac-sha256");
                    put("iter", iterations);
                    put("salt", b64.encodeToString(salt));
                    put("iv", b64.encodeToString(iv));
                    put("data", b64.encodeToString(data));
                }
            });
        } catch (GeneralSecurityException | java.io.IOException e) {
            throw new IllegalStateException("Cannot encrypt the cloud copy", e);
        }
    }

    static byte[] encrypt(String passphrase, byte[] plain) {
        return encrypt(passphrase, plain, ITERATIONS);
    }

    static byte[] decrypt(String passphrase, byte[] envelope) {
        try {
            JsonNode node = JSON.readTree(envelope);
            if (!"justinbeaver".equals(node.path("app").asText()) || node.path("v").asInt() != 1) {
                throw new GoogleException(422, "The cloud copy is not from this version of JustinBeaver");
            }
            int iterations = node.path("iter").asInt(ITERATIONS);
            if (iterations < 10_000 || iterations > 5_000_000) {
                throw new GoogleException(422, "The cloud copy has unusable encryption settings");
            }
            Base64.Decoder b64 = Base64.getDecoder();
            byte[] salt = b64.decode(node.path("salt").asText());
            byte[] iv = b64.decode(node.path("iv").asText());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt, iterations), new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(b64.decode(node.path("data").asText()));
        } catch (AEADBadTagException e) {
            throw new GoogleException(422, "Wrong sync passphrase (or the cloud copy was damaged)");
        } catch (GeneralSecurityException | java.io.IOException | IllegalArgumentException e) {
            throw new GoogleException(422, "Cannot read the cloud copy: " + e.getMessage(), e);
        }
    }

    private static SecretKeySpec key(String passphrase, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256);
        try {
            return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(), "AES");
        } finally {
            spec.clearPassword();
        }
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
