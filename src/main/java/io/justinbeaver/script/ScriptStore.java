package io.justinbeaver.script;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.justinbeaver.config.StorageProperties;
import org.springframework.stereotype.Component;

/**
 * SQL scripts as plain {@code .sql} files in {@code <storage>/scripts} (DBeaver's "Scripts" folder), so they
 * survive closing the editor and can be opened in any other tool. The connection a script runs on is kept in
 * {@code scripts/.index.json}, next to the files.
 */
@Component
public class ScriptStore {

    public record ScriptInfo(String name, String connectionId, long size, long modified) {
    }

    public record Script(String name, String connectionId, String content) {
    }

    /** A script as synchronized between machines: content and the moment it last changed. */
    public record Synced(String name, String connectionId, String content, long modified) {
    }

    private static final String EXTENSION = ".sql";
    private static final Pattern VALID_NAME = Pattern.compile("[^/\\\\:*?\"<>|\\p{Cntrl}]{1,120}");
    private static final Pattern NUMBERED = Pattern.compile("(?i)Script-(\\d{1,9})");
    private static final long MAX_CONTENT_CHARS = 20_000_000;

    private final Path dir;
    private final Path index;
    private final Path deletedFile;
    private final ObjectMapper json = new ObjectMapper();

    public ScriptStore(StorageProperties storage) {
        this.dir = storage.dirOrDefault().resolve("scripts");
        this.index = dir.resolve(".index.json");
        this.deletedFile = dir.resolve(".deleted.json");
    }

    public synchronized List<ScriptInfo> list() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        Map<String, String> connections = readIndex();
        List<ScriptInfo> scripts = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(EXTENSION) && Files.isRegularFile(f)).toList()) {
                String name = nameOf(file);
                scripts.add(new ScriptInfo(name, connections.get(name), Files.size(file), Files.getLastModifiedTime(file).toMillis()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + dir, e);
        }
        scripts.sort(Comparator.comparingLong(ScriptInfo::modified).reversed());
        return scripts;
    }

    public synchronized Script get(String name) {
        Path file = existing(name);
        try {
            return new Script(name, readIndex().get(name), Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    /** A new script named {@code Script-N}, one more than the highest number in use. */
    public synchronized Script create(String connectionId, String content) {
        int highest = 0;
        for (ScriptInfo script : list()) {
            Matcher m = NUMBERED.matcher(script.name());
            if (m.matches()) {
                highest = Math.max(highest, Integer.parseInt(m.group(1)));
            }
        }
        return save("Script-" + (highest + 1), connectionId, content);
    }

    /** Creates or overwrites the script. */
    public synchronized Script save(String name, String connectionId, String content) {
        Path file = fileOf(name);
        String text = content == null ? "" : content;
        if (text.length() > MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException("Script is too large (" + text.length() + " characters)");
        }
        writeAtomically(file, text);
        Map<String, String> connections = readIndex();
        if (connectionId == null) {
            connections.remove(name);
        } else {
            connections.put(name, connectionId);
        }
        writeIndex(connections);
        return new Script(name, connectionId, text);
    }

    public synchronized ScriptInfo rename(String name, String newName) {
        Path from = existing(name);
        Path to = fileOf(newName);
        if (Files.exists(to) && !to.equals(from)) {
            throw new IllegalArgumentException("A script named \"" + newName + "\" already exists");
        }
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            Map<String, String> connections = readIndex();
            String connectionId = connections.remove(name);
            if (connectionId != null) {
                connections.put(newName, connectionId);
            }
            writeIndex(connections);
            return new ScriptInfo(newName, connectionId, Files.size(to), Files.getLastModifiedTime(to).toMillis());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot rename " + from, e);
        }
    }

    public synchronized void delete(String name) {
        Path file = existing(name);
        try {
            Files.delete(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete " + file, e);
        }
        Map<String, String> connections = readIndex();
        if (connections.remove(name) != null) {
            writeIndex(connections);
        }
        Map<String, Long> deleted = readDeleted();
        deleted.put(name, System.currentTimeMillis());
        writeDeleted(deleted);
    }

    /** Every script with its content, for the encrypted cloud copy. */
    public synchronized List<Synced> exportAll() {
        List<Synced> out = new ArrayList<>();
        for (ScriptInfo info : list()) {
            Script script = get(info.name());
            out.add(new Synced(info.name(), script.connectionId(), script.content(), info.modified()));
        }
        return out;
    }

    /** Names of scripts deleted here (or elsewhere) and when. */
    public synchronized Map<String, Long> tombstones() {
        return readDeleted();
    }

    /** Applies the merged state; a deletion does not remove a script edited after it. */
    public synchronized void applyMerged(List<Synced> upserts, Map<String, Long> deletions) {
        Map<String, String> connections = readIndex();
        Map<String, Long> deleted = readDeleted();
        for (Synced up : upserts) {
            Path file = fileOf(up.name());
            writeAtomically(file, up.content());
            try {
                Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(up.modified()));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot date " + file, e);
            }
            if (up.connectionId() == null) {
                connections.remove(up.name());
            } else {
                connections.put(up.name(), up.connectionId());
            }
            deleted.remove(up.name());
        }
        deletions.forEach((name, at) -> {
            Path file = fileOf(name);
            try {
                if (Files.isRegularFile(file) && Files.getLastModifiedTime(file).toMillis() <= at) {
                    Files.delete(file);
                    connections.remove(name);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot delete " + file, e);
            }
            deleted.merge(name, at, Math::max);
        });
        writeIndex(connections);
        writeDeleted(deleted);
    }

    private Map<String, Long> readDeleted() {
        if (!Files.exists(deletedFile)) {
            return new TreeMap<>();
        }
        try {
            return new TreeMap<>(json.readValue(deletedFile.toFile(), new TypeReference<Map<String, Long>>() { }));
        } catch (IOException e) {
            return new TreeMap<>();
        }
    }

    private void writeDeleted(Map<String, Long> deleted) {
        try {
            writeAtomically(deletedFile, json.writerWithDefaultPrettyPrinter().writeValueAsString(deleted));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + deletedFile, e);
        }
    }

    private Path existing(String name) {
        Path file = fileOf(name);
        if (!Files.isRegularFile(file)) {
            throw new ScriptNotFoundException(name);
        }
        return file;
    }

    /** The file of a script name; rejects names that are not a plain file name inside the scripts folder. */
    private Path fileOf(String name) {
        if (name == null || !VALID_NAME.matcher(name).matches() || !name.equals(name.strip()) || name.startsWith(".")) {
            throw new IllegalArgumentException("Invalid script name: \"" + name + "\"");
        }
        Path file = dir.resolve(name + EXTENSION).normalize();
        if (!dir.equals(file.getParent())) {
            throw new IllegalArgumentException("Invalid script name: \"" + name + "\"");
        }
        return file;
    }

    private static String nameOf(Path file) {
        String fileName = file.getFileName().toString();
        return fileName.substring(0, fileName.length() - EXTENSION.length());
    }

    private Map<String, String> readIndex() {
        if (!Files.exists(index)) {
            return new TreeMap<>();
        }
        try {
            return new TreeMap<>(json.readValue(index.toFile(), new TypeReference<Map<String, String>>() { }));
        } catch (IOException e) {
            // a broken index only loses the script/connection links, never the scripts
            return new TreeMap<>();
        }
    }

    private void writeIndex(Map<String, String> connections) {
        try {
            writeAtomically(index, json.writerWithDefaultPrettyPrinter().writeValueAsString(connections));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + index, e);
        }
    }

    private void writeAtomically(Path file, String text) {
        try {
            Files.createDirectories(dir);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }
}
