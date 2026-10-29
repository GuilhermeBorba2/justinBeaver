package io.justinbeaver.script;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.justinbeaver.config.StorageProperties;
import org.springframework.stereotype.Component;

/**
 * The open editor tabs, saved by the UI in {@code workspace.json} so they come back on the next start, whatever
 * port or browser the UI is opened from. The server does not interpret the document.
 */
@Component
public class WorkspaceStore {

    private static final int MAX_BYTES = 50_000_000;

    private final Path file;
    private final ObjectMapper json = new ObjectMapper();

    public WorkspaceStore(StorageProperties storage) {
        this.file = storage.dirOrDefault().resolve("workspace.json");
    }

    /** The saved document, or null when nothing was saved yet (or the file is unreadable). */
    public synchronized JsonNode get() {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return json.readTree(file.toFile());
        } catch (IOException e) {
            return null;
        }
    }

    public synchronized void save(JsonNode workspace) {
        if (workspace == null || !workspace.isObject()) {
            throw new IllegalArgumentException("The workspace must be a JSON object");
        }
        try {
            byte[] bytes = json.writeValueAsBytes(workspace);
            if (bytes.length > MAX_BYTES) {
                throw new IllegalArgumentException("Workspace is too large (" + bytes.length + " bytes)");
            }
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }
}
