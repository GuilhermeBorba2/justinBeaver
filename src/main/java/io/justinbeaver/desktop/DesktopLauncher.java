package io.justinbeaver.desktop;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/** What happens before Spring starts and around the browser: single instance, port file, opening the page. */
public final class DesktopLauncher {

    private DesktopLauncher() {
    }

    public static Path dataDir() {
        String override = System.getProperty("justinbeaver.storage.dir");
        return override != null && !override.isBlank() ? Path.of(override) : Path.of(System.getProperty("user.home"), ".justinbeaver");
    }

    static Path portFile() {
        return dataDir().resolve("desktop.port");
    }

    /**
     * When another JustinBeaver of this user is already running, opens its page and returns true: the caller exits
     * instead of starting a second server.
     */
    public static boolean handOverToRunningInstance() {
        Integer port = readPort();
        if (port == null || !answers(port)) {
            return false;
        }
        openBrowser("http://127.0.0.1:" + port + "/");
        return true;
    }

    private static Integer readPort() {
        try {
            Path file = portFile();
            return Files.isRegularFile(file) ? Integer.valueOf(Files.readString(file).strip()) : null;
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    /** True when something on {@code port} is a JustinBeaver (not a stale port file of a crashed run). */
    static boolean answers(int port) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/app/info")).timeout(Duration.ofSeconds(2)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains("\"app\":\"justinbeaver\"");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static void writePort(int port) {
        try {
            Files.createDirectories(portFile().getParent());
            Files.writeString(portFile(), Integer.toString(port));
        } catch (IOException e) {
            // single-instance is best effort
        }
    }

    static void deletePort() {
        try {
            Files.deleteIfExists(portFile());
        } catch (IOException e) {
            // nothing to do
        }
    }

    /** The system's default browser, without AWT (works headless and on any desktop). */
    public static void openBrowser(String url) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[][] attempts = os.contains("win")
                ? new String[][] {{"rundll32", "url.dll,FileProtocolHandler", url}}
                : os.contains("mac")
                        ? new String[][] {{"open", url}}
                        : new String[][] {{"xdg-open", url}, {"gio", "open", url}, {"sensible-browser", url}};
        for (String[] command : attempts) {
            try {
                new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                return;
            } catch (IOException e) {
                // try the next opener
            }
        }
        System.err.println("Open " + url + " in your browser");
    }
}
