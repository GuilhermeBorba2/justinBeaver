package io.justinbeaver.google;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.justinbeaver.config.StorageProperties;
import io.justinbeaver.connection.SecretCipher;
import org.springframework.stereotype.Service;

/**
 * "Sign in with Google" for an installed app: authorization code with PKCE, the redirect coming back to this server on
 * loopback. Only the refresh token is kept (encrypted with the local key); access tokens live in memory.
 */
@Service
public class GoogleAuthService {

    public static final String SCOPE = "openid email profile https://www.googleapis.com/auth/drive.appdata";
    private static final Duration LOGIN_TIMEOUT = Duration.ofMinutes(10);

    public record Account(String email, String name, String picture) {
    }

    private record Pending(String verifier, String redirectUri, long createdAt) {
    }

    private record Token(String value, long expiresAt) {
    }

    private final GoogleProperties properties;
    private final SecretCipher cipher;
    private final Path accountFile;
    private final Path clientFile;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private volatile Token accessToken;
    private volatile Account account;

    public GoogleAuthService(GoogleProperties properties, StorageProperties storage, SecretCipher cipher) {
        this.properties = properties;
        this.cipher = cipher;
        this.accountFile = storage.dirOrDefault().resolve("google-account.json");
        this.clientFile = storage.dirOrDefault().resolve("google-client.json");
        this.account = readAccount();
    }

    // ---- the OAuth client (id and secret of a Google Cloud "Desktop app") ----------------------

    private record Client(String id, String secret) {
    }

    private Client client() {
        if (properties.clientId() != null && !properties.clientId().isBlank()) {
            return new Client(properties.clientId().strip(), properties.clientSecret());
        }
        try {
            if (Files.isRegularFile(clientFile)) {
                JsonNode node = json.readTree(clientFile.toFile());
                String id = node.path("clientId").asText("").strip();
                if (!id.isEmpty()) {
                    return new Client(id, node.path("clientSecret").asText(null));
                }
            }
        } catch (IOException e) {
            // unreadable file: treated as not configured
        }
        return null;
    }

    public boolean configured() {
        return client() != null;
    }

    public void saveClient(String clientId, String clientSecret) {
        if (clientId == null || !clientId.strip().matches("[\\w.-]+\\.apps\\.googleusercontent\\.com")) {
            throw new IllegalArgumentException("A Google client id ends with .apps.googleusercontent.com");
        }
        try {
            Files.createDirectories(clientFile.getParent());
            Map<String, String> content = new LinkedHashMap<>();
            content.put("clientId", clientId.strip());
            if (clientSecret != null && !clientSecret.isBlank()) {
                content.put("clientSecret", clientSecret.strip());
            }
            writeAtomically(clientFile, json.writerWithDefaultPrettyPrinter().writeValueAsString(content));
        } catch (IOException e) {
            throw new GoogleException(500, "Cannot save the Google client: " + e.getMessage(), e);
        }
    }

    // ---- sign in ---------------------------------------------------------------------------

    /** The page of Google's consent screen to open in the browser; the code comes back to {@code redirectUri}. */
    public String startLogin(String redirectUri) {
        Client client = client();
        if (client == null) {
            throw new GoogleException(400, "Google sign-in is not available in this build yet. You can keep using JustinBeaver locally.");
        }
        long now = System.currentTimeMillis();
        pending.values().removeIf(p -> now - p.createdAt() > LOGIN_TIMEOUT.toMillis());
        String verifier = randomToken(48);
        String state = randomToken(24);
        pending.put(state, new Pending(verifier, redirectUri, now));
        return properties.authUrlOrDefault() + "?" + form(Map.of(
                "client_id", client.id(),
                "redirect_uri", redirectUri,
                "response_type", "code",
                "scope", SCOPE,
                "code_challenge", challenge(verifier),
                "code_challenge_method", "S256",
                "state", state,
                "access_type", "offline",
                "prompt", "consent"));
    }

    /** Called with what Google put in the redirect; exchanges the code and remembers who signed in. */
    public Account complete(String code, String state) {
        Pending login = state == null ? null : pending.remove(state);
        if (login == null || System.currentTimeMillis() - login.createdAt() > LOGIN_TIMEOUT.toMillis()) {
            throw new GoogleException(400, "This sign-in link expired or was not started here: start again");
        }
        Client client = client();
        if (client == null) {
            throw new GoogleException(400, "Google sign-in is not set up");
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "authorization_code");
        body.put("code", code);
        body.put("redirect_uri", login.redirectUri());
        body.put("client_id", client.id());
        body.put("code_verifier", login.verifier());
        if (client.secret() != null && !client.secret().isBlank()) {
            body.put("client_secret", client.secret());
        }
        JsonNode token = postForm(properties.tokenUrlOrDefault(), body);
        String refresh = token.path("refresh_token").asText(null);
        if (refresh == null) {
            throw new GoogleException(400, "Google did not grant offline access: remove JustinBeaver from your Google account's connected apps and sign in again");
        }
        if (!hasDriveScope(token.path("scope").asText(""))) {
            throw new GoogleException(400, "Allow \"See and download its own configuration data in your Google Drive\" to sync, then sign in again");
        }
        accessToken = new Token(token.path("access_token").asText(), expiry(token));
        JsonNode info = getJson(properties.userInfoUrlOrDefault(), accessToken.value());
        Account signedIn = new Account(info.path("email").asText(""), info.path("name").asText(info.path("email").asText("")),
                info.path("picture").asText(null));
        saveAccount(signedIn, refresh);
        account = signedIn;
        return signedIn;
    }

    private static boolean hasDriveScope(String granted) {
        return granted.isEmpty() || granted.contains("drive.appdata");
    }

    public Account account() {
        return account;
    }

    public boolean signedIn() {
        return account != null;
    }

    /** A valid access token, refreshed when it is about to expire. */
    public synchronized String accessToken() {
        Token current = accessToken;
        if (current != null && System.currentTimeMillis() < current.expiresAt() - 60_000) {
            return current.value();
        }
        String refresh = readRefreshToken();
        Client client = client();
        if (refresh == null || client == null) {
            throw new GoogleException(401, "Sign in with Google first");
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", refresh);
        body.put("client_id", client.id());
        if (client.secret() != null && !client.secret().isBlank()) {
            body.put("client_secret", client.secret());
        }
        try {
            JsonNode token = postForm(properties.tokenUrlOrDefault(), body);
            accessToken = new Token(token.path("access_token").asText(), expiry(token));
            return accessToken.value();
        } catch (GoogleException e) {
            if (e.status() == 400 || e.status() == 401) {
                // the refresh token was revoked or expired (Google test-mode apps: after 7 days)
                forget();
                throw new GoogleException(401, "Your Google session ended: sign in again");
            }
            throw e;
        }
    }

    public void signOut() {
        String refresh = readRefreshToken();
        forget();
        if (refresh != null) {
            try {
                postForm(properties.revokeUrlOrDefault(), Map.of("token", refresh));
            } catch (GoogleException e) {
                // already revoked or offline: the local sign-out is what matters
            }
        }
    }

    private void forget() {
        accessToken = null;
        account = null;
        try {
            Files.deleteIfExists(accountFile);
        } catch (IOException e) {
            // best effort
        }
    }

    // ---- storage -----------------------------------------------------------------------------

    private Account readAccount() {
        try {
            if (!Files.isRegularFile(accountFile)) {
                return null;
            }
            JsonNode node = json.readTree(accountFile.toFile());
            return node.hasNonNull("email")
                    ? new Account(node.path("email").asText(), node.path("name").asText(), node.path("picture").asText(null)) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private String readRefreshToken() {
        try {
            if (!Files.isRegularFile(accountFile)) {
                return null;
            }
            String enc = json.readTree(accountFile.toFile()).path("refreshToken").asText(null);
            return enc == null ? null : cipher.decrypt(enc);
        } catch (IOException | IllegalStateException e) {
            return null;
        }
    }

    private void saveAccount(Account who, String refresh) {
        try {
            Files.createDirectories(accountFile.getParent());
            Map<String, String> content = new LinkedHashMap<>();
            content.put("email", who.email());
            content.put("name", who.name());
            if (who.picture() != null) {
                content.put("picture", who.picture());
            }
            content.put("refreshToken", cipher.encrypt(refresh));
            writeAtomically(accountFile, json.writeValueAsString(content));
        } catch (IOException e) {
            throw new GoogleException(500, "Cannot save the Google session: " + e.getMessage(), e);
        }
    }

    private static void writeAtomically(Path file, String text) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException notPosix) {
            // Windows: the user's profile folder is already private
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ---- HTTP --------------------------------------------------------------------------------

    private static long expiry(JsonNode token) {
        return System.currentTimeMillis() + token.path("expires_in").asLong(3600) * 1000;
    }

    private JsonNode postForm(String url, Map<String, String> body) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
            return parse(response);
        } catch (IOException e) {
            throw new GoogleException(502, "Cannot reach Google: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GoogleException(502, "Interrupted while talking to Google", e);
        }
    }

    private JsonNode getJson(String url, String token) {
        try {
            return parse(http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString()));
        } catch (IOException e) {
            throw new GoogleException(502, "Cannot reach Google: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GoogleException(502, "Interrupted while talking to Google", e);
        }
    }

    private JsonNode parse(HttpResponse<String> response) throws IOException {
        JsonNode body = response.body() == null || response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
        if (response.statusCode() / 100 != 2) {
            String detail = body.path("error_description").asText(body.path("error").isTextual() ? body.path("error").asText() : "HTTP " + response.statusCode());
            throw new GoogleException(response.statusCode() == 400 || response.statusCode() == 401 ? response.statusCode() : 502, "Google: " + detail);
        }
        return body;
    }

    static String form(Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        values.forEach((k, v) -> {
            if (v != null) {
                out.append(out.isEmpty() ? "" : "&").append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                        .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
            }
        });
        return out.toString();
    }

    private String randomToken(int bytes) {
        byte[] raw = new byte[bytes];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
