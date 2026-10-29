package io.justinbeaver.api;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import io.justinbeaver.admin.AdminService;
import io.justinbeaver.admin.BackupService;
import io.justinbeaver.admin.JobService;
import io.justinbeaver.connection.ConnectionStore;
import io.justinbeaver.query.QueryModels.QueryResult;
import io.justinbeaver.session.SessionManager;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Session manager, locks, dashboard metrics, native backup/restore and their jobs. */
@RestController
@RequestMapping("/api")
public class AdminController {

    public record SessionAction(@NotBlank String id, AdminService.Action action) {
    }

    public record BackupRequest(@NotBlank String file, BackupService.Format format) {
    }

    public record RestoreRequest(@NotBlank String file) {
    }

    private final SessionManager sessions;
    private final AdminService admin;
    private final BackupService backups;
    private final JobService jobs;
    private final ConnectionStore store;

    public AdminController(SessionManager sessions, AdminService admin, BackupService backups, JobService jobs,
                           ConnectionStore store) {
        this.sessions = sessions;
        this.admin = admin;
        this.backups = backups;
        this.jobs = jobs;
        this.store = store;
    }

    @GetMapping("/sessions/{connectionId}/admin/sessions")
    public QueryResult serverSessions(@PathVariable String connectionId) throws SQLException {
        return admin.sessions(sessions.require(connectionId));
    }

    @GetMapping("/sessions/{connectionId}/admin/locks")
    public QueryResult locks(@PathVariable String connectionId) throws SQLException {
        return admin.locks(sessions.require(connectionId));
    }

    @PostMapping("/sessions/{connectionId}/admin/session-action")
    public Map<String, Boolean> sessionAction(@PathVariable String connectionId, @RequestBody SessionAction request)
            throws SQLException {
        admin.act(sessions.require(connectionId), request.id(),
                request.action() == null ? AdminService.Action.CANCEL : request.action());
        return Map.of("ok", true);
    }

    @GetMapping("/sessions/{connectionId}/admin/metrics")
    public List<AdminService.Metric> metrics(@PathVariable String connectionId) throws SQLException {
        return admin.metrics(sessions.require(connectionId));
    }

    @PostMapping("/connections/{connectionId}/backup")
    public JobService.JobView backup(@PathVariable String connectionId, @RequestBody BackupRequest request) throws SQLException {
        return backups.backup(store.get(connectionId), request.file(),
                request.format() == null ? BackupService.Format.CUSTOM : request.format());
    }

    @PostMapping("/connections/{connectionId}/restore")
    public JobService.JobView restore(@PathVariable String connectionId, @RequestBody RestoreRequest request) throws SQLException {
        return backups.restore(store.get(connectionId), request.file());
    }

    @GetMapping("/jobs")
    public List<JobService.JobView> jobs() {
        return jobs.list();
    }

    @GetMapping("/jobs/{jobId}")
    public JobService.JobView job(@PathVariable String jobId) {
        return jobs.get(jobId);
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public JobService.JobView cancel(@PathVariable String jobId) {
        return jobs.cancel(jobId);
    }
}
