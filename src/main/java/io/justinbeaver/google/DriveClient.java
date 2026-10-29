package io.justinbeaver.google;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Google Drive's hidden "app data" folder: files only this app can see, invisible in the user's Drive. Needs only the
 * {@code drive.appdata} scope, so JustinBeaver can never read the user's own files.
 */
@Component
public class DriveClient {

    public record DriveFile(String id, String name, String modifiedTime) {
    }

    private final GoogleProperties properties;
    private final GoogleAuthService auth;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public DriveClient(GoogleProperties properties, GoogleAuthService auth) {
        this.properties = properties;
        this.auth = auth;
    }

    public Optional<DriveFile> find(String name) {
        String q = "name = '" + name.replace("\\", "\\\\").replace("'", "\\'") + "' and trashed = false";
        JsonNode body = send(HttpRequest.newBuilder(URI.create(properties.driveUrlOrDefault() + "/files?spaces=appDataFolder&pageSize=1&fields="
                + enc("files(id,name,modifiedTime)") + "&q=" + enc(q))).GET());
        JsonNode first = body.path("files").path(0);
        return first.isMissingNode() ? Optional.empty()
                : Optional.of(new DriveFile(first.path("id").asText(), first.path("name").asText(), first.path("modifiedTime").asText(null)));
    }

    public byte[] download(String id) {
        return sendBytes(HttpRequest.newBuilder(URI.create(properties.driveUrlOrDefault() + "/files/" + enc(id) + "?alt=media")).GET());
    }

    /** Creates the file, or replaces the content of {@code existingId}. */
    public DriveFile upload(String name, byte[] content, String existingId) {
        String boundary = "jb-" + UUID.randomUUID();
        String metadata = existingId == null
                ? "{\"name\":" + quote(name) + ",\"parents\":[\"appDataFolder\"]}"
                : "{\"name\":" + quote(name) + "}";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try {
            body.write(("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + metadata + "\r\n--" + boundary
                    + "\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(content);
            body.write(("\r\n--" + boundary + "--").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        String url = properties.uploadUrlOrDefault() + "/files" + (existingId == null ? "" : "/" + enc(existingId)) + "?uploadType=multipart&fields="
                + enc("id,name,modifiedTime");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "multipart/related; boundary=" + boundary);
        JsonNode result = send(existingId == null ? request.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                : request.method("PATCH", HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())));
        return new DriveFile(result.path("id").asText(), result.path("name").asText(), result.path("modifiedTime").asText(null));
    }

    public void delete(String id) {
        sendBytes(HttpRequest.newBuilder(URI.create(properties.driveUrlOrDefault() + "/files/" + enc(id))).DELETE());
    }

    private JsonNode send(HttpRequest.Builder request) {
        byte[] bytes = sendBytes(request);
        try {
            return bytes.length == 0 ? json.createObjectNode() : json.readTree(bytes);
        } catch (IOException e) {
            throw new GoogleException(502, "Google Drive sent something unexpected", e);
        }
    }

    private byte[] sendBytes(HttpRequest.Builder request) {
        try {
            HttpResponse<byte[]> response = http.send(request.timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + auth.accessToken()).build(), HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status / 100 != 2) {
                String message = "Google Drive: HTTP " + status;
                try {
                    String detail = json.readTree(response.body()).path("error").path("message").asText(null);
                    if (detail != null) {
                        message = "Google Drive: " + detail;
                    }
                } catch (IOException ignored) {
                    // body was not JSON
                }
                if (status == 403 && message.contains("has not been used") ) {
                    message += " (enable the Google Drive API in the Google Cloud project of this app)";
                }
                throw new GoogleException(status == 401 ? 401 : 502, message);
            }
            return response.body();
        } catch (IOException e) {
            throw new GoogleException(502, "Cannot reach Google Drive: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GoogleException(502, "Interrupted while talking to Google Drive", e);
        }
    }

    private static String enc(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    private String quote(String text) {
        try {
            return json.writeValueAsString(text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
