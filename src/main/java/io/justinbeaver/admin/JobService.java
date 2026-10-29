package io.justinbeaver.admin;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

/**
 * Background native-tool runs (DBeaver "tasks"): one process per job, output kept in memory
 * (last 1000 lines), never through a shell. {@code cleanup} runs when the process ends (closes SSH
 * tunnels opened for the job).
 */
@Service
public class JobService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    private static final int MAX_LINES = 1000;
    private static final int MAX_JOBS = 50;

    public enum Status { RUNNING, SUCCEEDED, FAILED, CANCELLED }

    public record JobView(String id, String label, Status status, Instant startedAt, Instant endedAt, Integer exitCode,
                          List<String> log) {
    }

    private static final class Job {
        final String id = UUID.randomUUID().toString();
        final String label;
        final Instant startedAt = Instant.now();
        final Deque<String> lines = new ArrayDeque<>();
        volatile Process process;
        volatile Status status = Status.RUNNING;
        volatile Instant endedAt;
        volatile Integer exitCode;

        Job(String label) {
            this.label = label;
        }

        synchronized void line(String text) {
            lines.addLast(text);
            while (lines.size() > MAX_LINES) {
                lines.removeFirst();
            }
        }

        synchronized JobView view() {
            return new JobView(id, label, status, startedAt, endedAt, exitCode, List.copyOf(lines));
        }
    }

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    /**
     * @param env       extra environment (passwords go here, never on the command line)
     * @param stdinFile file fed to the process's standard input, or null
     */
    public JobView start(String label, List<String> command, Map<String, String> env, File stdinFile, AutoCloseable cleanup) {
        prune();
        Job job = new Job(label);
        jobs.put(job.id, job);
        job.line("$ " + String.join(" ", command));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(env);
        if (stdinFile != null) {
            builder.redirectInput(stdinFile);
        }
        try {
            job.process = builder.start();
        } catch (IOException e) {
            finish(job, Status.FAILED, null, "Cannot start %s: %s".formatted(command.get(0), e.getMessage()), cleanup);
            return job.view();
        }
        Thread reader = new Thread(() -> {
            try (BufferedReader out = new BufferedReader(new InputStreamReader(job.process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = out.readLine()) != null; ) {
                    job.line(line);
                }
                int code = job.process.waitFor();
                finish(job, job.status == Status.CANCELLED ? Status.CANCELLED : code == 0 ? Status.SUCCEEDED : Status.FAILED, code,
                        code == 0 ? "Finished" : "Exited with code " + code, cleanup);
            } catch (IOException | InterruptedException e) {
                finish(job, Status.FAILED, null, "Error: " + e.getMessage(), cleanup);
            }
        }, "job-" + job.id);
        reader.setDaemon(true);
        reader.start();
        return job.view();
    }

    private void finish(Job job, Status status, Integer code, String message, AutoCloseable cleanup) {
        if (job.status != Status.CANCELLED) {
            job.status = status;
        }
        job.exitCode = code;
        job.endedAt = Instant.now();
        job.line(message);
        if (cleanup != null) {
            try {
                cleanup.close();
            } catch (Exception e) {
                log.warn("Job {} cleanup failed: {}", job.id, e.getMessage());
            }
        }
    }

    public JobView get(String id) {
        Job job = jobs.get(id);
        if (job == null) {
            throw new IllegalArgumentException("Unknown job " + id);
        }
        return job.view();
    }

    public List<JobView> list() {
        List<JobView> views = new ArrayList<>();
        jobs.values().forEach(j -> views.add(j.view()));
        views.sort(Comparator.comparing(JobView::startedAt).reversed());
        return views;
    }

    public JobView cancel(String id) {
        Job job = jobs.get(id);
        if (job == null) {
            throw new IllegalArgumentException("Unknown job " + id);
        }
        if (job.status == Status.RUNNING && job.process != null) {
            job.status = Status.CANCELLED;
            job.process.destroy();
        }
        return job.view();
    }

    private void prune() {
        if (jobs.size() < MAX_JOBS) {
            return;
        }
        jobs.values().stream()
                .filter(j -> j.status != Status.RUNNING)
                .min(Comparator.comparing(j -> j.startedAt))
                .ifPresent(j -> jobs.remove(j.id));
    }

    @Override
    public void destroy() {
        jobs.values().forEach(j -> {
            if (j.process != null && j.process.isAlive()) {
                j.process.destroy();
            }
        });
    }
}
