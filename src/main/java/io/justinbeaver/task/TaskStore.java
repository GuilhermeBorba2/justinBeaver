package io.justinbeaver.task;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.justinbeaver.config.StorageProperties;
import org.springframework.stereotype.Component;

/** Saved tasks (DBeaver's Task Manager) in {@code tasks.json}, with the last runs of each. */
@Component
public class TaskStore {

    public enum Type { SQL, EXPORT, BACKUP }

    public enum ScheduleKind { MANUAL, INTERVAL, DAILY, CRON }

    /**
     * @param everyMinutes INTERVAL only
     * @param at           DAILY only, "HH:mm" in the server's time zone
     * @param cron         CRON only: Spring's six fields, "0 30 2 * * MON-FRI"
     */
    public record Schedule(ScheduleKind kind, Integer everyMinutes, String at, String cron) {
        public Schedule {
            kind = kind == null ? ScheduleKind.MANUAL : kind;
        }
    }

    public record Run(Instant startedAt, Instant endedAt, boolean ok, String message) {
    }

    /**
     * @param sql          SQL: the script; EXPORT: the query
     * @param file         EXPORT / BACKUP target; {date}, {time} and {name} are replaced at run time
     * @param format       EXPORT: CSV, TSV or JSON; BACKUP: CUSTOM or PLAIN
     */
    public record Task(String id, String name, Type type, String connectionId, String sql, String file, String format,
                       Schedule schedule, boolean enabled, List<Run> runs) {
        public Task {
            runs = runs == null ? List.of() : List.copyOf(runs);
            schedule = schedule == null ? new Schedule(ScheduleKind.MANUAL, null, null, null) : schedule;
        }

        Task withId(String newId) {
            return new Task(newId, name, type, connectionId, sql, file, format, schedule, enabled, runs);
        }

        Task withRuns(List<Run> newRuns) {
            return new Task(id, name, type, connectionId, sql, file, format, schedule, enabled, newRuns);
        }
    }

    static final int KEPT_RUNS = 20;

    private final Path file;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public TaskStore(StorageProperties storage) {
        this.file = storage.dirOrDefault().resolve("tasks.json");
    }

    public synchronized List<Task> all() {
        return read();
    }

    public synchronized Task get(String id) {
        return read().stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No task " + id));
    }

    /** Creates (null or unknown id) or replaces a task; its run history is kept. */
    public synchronized Task save(Task task) {
        List<Task> all = read();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(task.id())) {
                Task updated = task.withRuns(all.get(i).runs());
                all.set(i, updated);
                write(all);
                return updated;
            }
        }
        Task created = task.withId(task.id() == null || task.id().isBlank() ? UUID.randomUUID().toString() : task.id()).withRuns(List.of());
        all.add(created);
        write(all);
        return created;
    }

    public synchronized void delete(String id) {
        List<Task> all = read();
        if (!all.removeIf(t -> t.id().equals(id))) {
            throw new IllegalArgumentException("No task " + id);
        }
        write(all);
    }

    /** Newest run first, the oldest beyond {@link #KEPT_RUNS} dropped. */
    public synchronized void addRun(String id, Run run) {
        List<Task> all = read();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(id)) {
                List<Run> runs = new ArrayList<>();
                runs.add(run);
                runs.addAll(all.get(i).runs());
                all.set(i, all.get(i).withRuns(runs.subList(0, Math.min(KEPT_RUNS, runs.size()))));
                write(all);
                return;
            }
        }
    }

    private List<Task> read() {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(List.of(json.readValue(file.toFile(), Task[].class)));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    private void write(List<Task> all) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            json.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), all);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }
}
