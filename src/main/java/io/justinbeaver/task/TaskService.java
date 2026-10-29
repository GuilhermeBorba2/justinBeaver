package io.justinbeaver.task;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.justinbeaver.admin.BackupService;
import io.justinbeaver.admin.JobService;
import io.justinbeaver.connection.ConnectionStore;
import io.justinbeaver.connection.SavedConnection;
import io.justinbeaver.jdbc.ConnectionService;
import io.justinbeaver.jdbc.JdbcDataSource;
import io.justinbeaver.query.QueryModels.QueryResult;
import io.justinbeaver.query.QueryModels.StatementResult;
import io.justinbeaver.query.QueryService;
import io.justinbeaver.session.DbSession;
import io.justinbeaver.session.SessionManager;
import io.justinbeaver.task.TaskStore.Run;
import io.justinbeaver.task.TaskStore.Schedule;
import io.justinbeaver.task.TaskStore.Task;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

/**
 * Runs saved tasks now or on their schedule (DBeaver's Task Scheduler): a SQL script, a query exported to a
 * file, or a native backup. Each run opens its own connection, so it never touches the transaction of an open
 * editor, and honours the connection's read-only flag.
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    private static final int TIMEOUT_SECONDS = 3600;
    private static final int MAX_EXPORT_ROWS = 1_000_000;
    private static final Pattern HH_MM = Pattern.compile("([01]?\\d|2[0-3]):([0-5]\\d)");

    private final TaskStore store;
    private final ConnectionStore connections;
    private final ConnectionService jdbc;
    private final QueryService queries;
    private final BackupService backups;
    private final JobService jobs;
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final Map<String, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final ObjectMapper json = new ObjectMapper();

    public TaskService(TaskStore store, ConnectionStore connections, ConnectionService jdbc, QueryService queries,
                       BackupService backups, JobService jobs) {
        this.store = store;
        this.connections = connections;
        this.jdbc = jdbc;
        this.queries = queries;
        this.backups = backups;
        this.jobs = jobs;
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("task-");
    }

    @PostConstruct
    void start() {
        scheduler.initialize();
        for (Task task : store.all()) {
            try {
                schedule(task);
            } catch (RuntimeException e) {
                log.warn("Task {} not scheduled: {}", task.name(), e.getMessage());
            }
        }
    }

    @PreDestroy
    void stop() {
        scheduler.shutdown();
    }

    public List<Task> all() {
        return store.all();
    }

    public Task save(Task task) {
        validate(task);
        Task saved = store.save(task);
        schedule(saved);
        return saved;
    }

    public void delete(String id) {
        cancel(id);
        store.delete(id);
    }

    public boolean isRunning(String id) {
        return running.contains(id);
    }

    public Set<String> runningIds() {
        return Set.copyOf(running);
    }

    /** Starts a run in the background; refused while the previous run of the same task is still going. */
    public void runNow(String id) {
        Task task = store.get(id);
        if (running.contains(id)) {
            throw new IllegalArgumentException("Task " + task.name() + " is already running");
        }
        scheduler.execute(() -> run(id));
    }

    static void validate(Task task) {
        if (task.name() == null || task.name().isBlank()) {
            throw new IllegalArgumentException("Give the task a name");
        }
        if (task.type() == null || task.connectionId() == null || task.connectionId().isBlank()) {
            throw new IllegalArgumentException("Pick the task type and connection");
        }
        if (task.type() != TaskStore.Type.BACKUP && (task.sql() == null || task.sql().isBlank())) {
            throw new IllegalArgumentException("Write the SQL the task runs");
        }
        if (task.type() != TaskStore.Type.SQL && (task.file() == null || task.file().isBlank())) {
            throw new IllegalArgumentException("Give the file the task writes");
        }
        Schedule s = task.schedule();
        switch (s.kind()) {
            case INTERVAL -> {
                if (s.everyMinutes() == null || s.everyMinutes() < 1) {
                    throw new IllegalArgumentException("Run every at least 1 minute");
                }
            }
            case DAILY -> {
                if (s.at() == null || !HH_MM.matcher(s.at().strip()).matches()) {
                    throw new IllegalArgumentException("Daily time as HH:mm, e.g. 02:30");
                }
            }
            case CRON -> {
                if (s.cron() == null || !CronExpression.isValidExpression(s.cron().strip())) {
                    throw new IllegalArgumentException("Not a valid cron expression (six fields: sec min hour day month weekday)");
                }
            }
            case MANUAL -> {
                // run only from the UI
            }
        }
    }

    private void cancel(String id) {
        ScheduledFuture<?> previous = scheduled.remove(id);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    private void schedule(Task task) {
        cancel(task.id());
        if (!task.enabled()) {
            return;
        }
        Schedule s = task.schedule();
        Runnable work = () -> {
            if (!running.contains(task.id())) {
                run(task.id());
            }
        };
        ScheduledFuture<?> future = switch (s.kind()) {
            case MANUAL -> null;
            case INTERVAL -> scheduler.scheduleAtFixedRate(work, Instant.now().plus(Duration.ofMinutes(s.everyMinutes())),
                    Duration.ofMinutes(s.everyMinutes()));
            case DAILY -> {
                var m = HH_MM.matcher(s.at().strip());
                if (!m.matches()) {
                    throw new IllegalArgumentException("Daily time as HH:mm");
                }
                yield scheduler.schedule(work, new CronTrigger("0 %d %d * * *".formatted(Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(1)))));
            }
            case CRON -> scheduler.schedule(work, new CronTrigger(s.cron().strip()));
        };
        if (future != null) {
            scheduled.put(task.id(), future);
        }
    }

    /** The next time a scheduled task runs, or null (manual, disabled). */
    public Instant nextRun(Task task) {
        if (!task.enabled()) {
            return null;
        }
        Schedule s = task.schedule();
        try {
            return switch (s.kind()) {
                case MANUAL -> null;
                case INTERVAL -> {
                    ScheduledFuture<?> f = scheduled.get(task.id());
                    yield f == null ? null : Instant.now().plusMillis(f.getDelay(java.util.concurrent.TimeUnit.MILLISECONDS));
                }
                case DAILY -> {
                    var m = HH_MM.matcher(s.at().strip());
                    m.matches();
                    yield next("0 %d %d * * *".formatted(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1))));
                }
                case CRON -> next(s.cron().strip());
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Instant next(String cron) {
        LocalDateTime next = CronExpression.parse(cron).next(LocalDateTime.now());
        return next == null ? null : next.atZone(java.time.ZoneId.systemDefault()).toInstant();
    }

    private void run(String id) {
        if (!running.add(id)) {
            return;
        }
        Instant started = Instant.now();
        String message;
        boolean ok;
        try {
            Task task = store.get(id);
            message = switch (task.type()) {
                case SQL -> runScript(task);
                case EXPORT -> runExport(task);
                case BACKUP -> runBackup(task);
            };
            ok = !message.startsWith("FAILED");
        } catch (Exception e) {
            ok = false;
            message = "FAILED: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            running.remove(id);
        }
        try {
            store.addRun(id, new Run(started, Instant.now(), ok, message.length() > 2000 ? message.substring(0, 2000) + "…" : message));
        } catch (RuntimeException e) {
            log.warn("Cannot record the run of task {}: {}", id, e.getMessage());
        }
    }

    private SavedConnection saved(String connectionId) {
        int separator = connectionId.indexOf(SessionManager.DATABASE_SEPARATOR);
        if (separator > 0) {
            SavedConnection base = connections.get(connectionId.substring(0, separator));
            return base.onDatabase(connectionId, connectionId.substring(separator + SessionManager.DATABASE_SEPARATOR.length()));
        }
        return connections.get(connectionId);
    }

    /** A connection of its own, auto-commit on, closed after the run. */
    private DbSession open(SavedConnection saved) throws SQLException {
        JdbcDataSource dataSource = jdbc.dataSource(saved.toConfig());
        JdbcDataSource.Opened opened = dataSource.open();
        opened.connection().setAutoCommit(true);
        DbSession session = new DbSession(saved.id(), opened.connection(), dataSource.driverClassLoader(), opened.tunnel());
        session.setReadOnly(saved.readOnly());
        return session;
    }

    private String runScript(Task task) throws SQLException {
        try (DbSession session = open(saved(task.connectionId()))) {
            List<StatementResult> results = queries.executeScript(session, task.sql(), 10, TIMEOUT_SECONDS, true);
            StatementResult failed = results.stream().filter(r -> r.error() != null).findFirst().orElse(null);
            long affected = results.stream().filter(r -> r.result() != null && r.result().updateCount() != null)
                    .mapToLong(r -> r.result().updateCount()).sum();
            if (failed != null) {
                return "FAILED on: " + failed.sql() + "\n" + failed.error();
            }
            return "%d statement(s) ran, %d row(s) affected".formatted(results.size(), affected);
        }
    }

    static String expandFile(String pattern, String name) {
        LocalDateTime now = LocalDateTime.now();
        String file = pattern.strip()
                .replace("{date}", now.format(DateTimeFormatter.ISO_LOCAL_DATE))
                .replace("{time}", now.format(DateTimeFormatter.ofPattern("HHmmss")))
                .replace("{name}", name.replaceAll("[^\\w.-]+", "_"));
        return file.startsWith("~") ? System.getProperty("user.home") + file.substring(1) : file;
    }

    private String runExport(Task task) throws SQLException, IOException {
        QueryResult result;
        try (DbSession session = open(saved(task.connectionId()))) {
            result = queries.execute(session, task.sql(), MAX_EXPORT_ROWS, TIMEOUT_SECONDS);
        }
        Path file = Path.of(expandFile(task.file(), task.name()));
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        String format = task.format() == null ? "CSV" : task.format().toUpperCase(java.util.Locale.ROOT);
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            switch (format) {
                case "JSON" -> {
                    List<Map<String, Object>> rows = result.rows().stream().map(row -> {
                        Map<String, Object> m = new java.util.LinkedHashMap<>();
                        for (int i = 0; i < result.columns().size(); i++) {
                            m.put(result.columns().get(i).name(), row.get(i));
                        }
                        return m;
                    }).toList();
                    json.writerWithDefaultPrettyPrinter().writeValue(out, rows);
                }
                case "TSV" -> writeDelimited(out, result, '\t');
                default -> writeDelimited(out, result, ',');
            }
        }
        return "%d row(s) written to %s%s".formatted(result.rows().size(), file, result.truncated() ? " (truncated at 1,000,000)" : "");
    }

    static void writeDelimited(Writer out, QueryResult result, char delimiter) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < result.columns().size(); i++) {
            if (i > 0) {
                line.append(delimiter);
            }
            line.append(cell(result.columns().get(i).name(), delimiter));
        }
        out.write(line.append("\r\n").toString());
        for (List<Object> row : result.rows()) {
            line.setLength(0);
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    line.append(delimiter);
                }
                line.append(row.get(i) == null ? "" : cell(String.valueOf(row.get(i)), delimiter));
            }
            out.write(line.append("\r\n").toString());
        }
    }

    private static String cell(String text, char delimiter) {
        if (delimiter == '\t') {
            return text.replaceAll("[\\t\\r\\n]+", " ");
        }
        return text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0
                ? '"' + text.replace("\"", "\"\"") + '"' : text;
    }

    private String runBackup(Task task) throws SQLException, InterruptedException {
        BackupService.Format format = "PLAIN".equalsIgnoreCase(task.format()) ? BackupService.Format.PLAIN : BackupService.Format.CUSTOM;
        String file = expandFile(task.file(), task.name());
        JobService.JobView job = backups.backup(saved(task.connectionId()), file, format);
        while (job.status() == JobService.Status.RUNNING) {
            Thread.sleep(1000);
            job = jobs.get(job.id());
        }
        List<String> tail = job.log().subList(Math.max(0, job.log().size() - 5), job.log().size());
        return (job.status() == JobService.Status.SUCCEEDED ? "Backup written to " + file : "FAILED: backup " + job.status())
                + (tail.isEmpty() ? "" : "\n" + String.join("\n", tail));
    }
}
