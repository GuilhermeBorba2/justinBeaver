package io.justinbeaver.admin;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import io.justinbeaver.query.QueryModels.QueryResult;
import io.justinbeaver.query.QueryService;
import io.justinbeaver.session.DbSession;
import org.springframework.stereotype.Service;

/**
 * Session manager, lock viewer and dashboard metrics (DBeaver's ui.editors.session, ui.editors.locks
 * and ui.dashboard). Each needs the database's own catalog views, so support is per dialect and
 * usually requires a monitoring privilege on the server.
 */
@Service
public class AdminService {

    public enum Action { CANCEL, TERMINATE }

    public enum MetricKind { GAUGE, COUNTER }

    /** A COUNTER is cumulative; the client turns successive samples into a rate per second. */
    public record Metric(String name, double value, MetricKind kind, String unit) {
    }

    private static final Pattern NUMBER = Pattern.compile("\\d{1,19}");
    private static final Pattern ORACLE_SESSION = Pattern.compile("\\d{1,10},\\d{1,10}");

    private final QueryService queries;

    public AdminService(QueryService queries) {
        this.queries = queries;
    }

    static String dialect(DbSession session) throws SQLException {
        return session.call(conn -> io.justinbeaver.meta.MetadataService.dialectOf(conn.getMetaData().getDatabaseProductName()));
    }

    public QueryResult sessions(DbSession session) throws SQLException {
        String sql = switch (dialect(session)) {
            case "postgresql" -> "SELECT pid, usename AS user_name, datname AS database, client_addr::text AS client, application_name, "
                    + "state, backend_start, query_start, wait_event_type, left(query, 2000) AS query "
                    + "FROM pg_stat_activity WHERE backend_type = 'client backend' ORDER BY pid";
            case "mysql", "mariadb" -> "SELECT ID AS id, USER AS user_name, HOST AS client, DB AS `database`, COMMAND AS command, "
                    + "TIME AS seconds, STATE AS state, LEFT(INFO, 2000) AS query FROM information_schema.PROCESSLIST ORDER BY ID";
            case "sqlserver" -> "SELECT s.session_id AS id, s.login_name AS user_name, s.host_name AS client, DB_NAME(s.database_id) AS [database], "
                    + "s.program_name, s.status, r.command, r.wait_type, SUBSTRING(t.text, 1, 2000) AS query "
                    + "FROM sys.dm_exec_sessions s LEFT JOIN sys.dm_exec_requests r ON r.session_id = s.session_id "
                    + "OUTER APPLY sys.dm_exec_sql_text(r.sql_handle) t WHERE s.is_user_process = 1 ORDER BY s.session_id";
            case "oracle" -> "SELECT sid || ',' || serial# AS id, username AS user_name, machine AS client, program, status, "
                    + "logon_time, sql_id FROM v$session WHERE type = 'USER' ORDER BY sid";
            case "h2" -> "SELECT SESSION_ID AS id, USER_NAME, SESSION_START, ISOLATION_LEVEL, EXECUTING_STATEMENT AS query "
                    + "FROM INFORMATION_SCHEMA.SESSIONS ORDER BY SESSION_ID";
            default -> throw unsupported(session, "Session manager");
        };
        return queries.execute(session, sql, 5000, 30);
    }

    public QueryResult locks(DbSession session) throws SQLException {
        String sql = switch (dialect(session)) {
            case "postgresql" -> "SELECT l.pid, a.usename AS user_name, l.locktype, l.relation::regclass::text AS relation, l.mode, l.granted, "
                    + "l.transactionid::text AS transaction_id, left(a.query, 500) AS query "
                    + "FROM pg_locks l LEFT JOIN pg_stat_activity a ON a.pid = l.pid WHERE l.pid <> pg_backend_pid() ORDER BY l.granted, l.pid";
            case "mysql" -> "SELECT THREAD_ID AS thread, OBJECT_SCHEMA AS `schema`, OBJECT_NAME AS `table`, INDEX_NAME, LOCK_TYPE, "
                    + "LOCK_MODE, LOCK_STATUS, LOCK_DATA FROM performance_schema.data_locks";
            case "mariadb" -> "SELECT * FROM information_schema.INNODB_LOCKS";
            case "sqlserver" -> "SELECT request_session_id AS id, resource_type, DB_NAME(resource_database_id) AS [database], "
                    + "resource_description, request_mode, request_status FROM sys.dm_tran_locks WHERE request_session_id <> @@SPID";
            case "oracle" -> "SELECT l.sid, s.username, l.type, l.lmode, l.request, l.block, o.object_name FROM v$lock l "
                    + "JOIN v$session s ON s.sid = l.sid LEFT JOIN all_objects o ON o.object_id = l.id1 WHERE s.type = 'USER'";
            case "h2" -> "SELECT TABLE_SCHEMA, TABLE_NAME, SESSION_ID AS id, LOCK_TYPE FROM INFORMATION_SCHEMA.LOCKS";
            default -> throw unsupported(session, "Lock viewer");
        };
        return queries.execute(session, sql, 5000, 30);
    }

    /** Cancel the running statement of a server session, or end the session. */
    public void act(DbSession session, String id, Action action) throws SQLException {
        String dialect = dialect(session);
        if (dialect.equals("oracle") ? !ORACLE_SESSION.matcher(id).matches() : !NUMBER.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid session id '%s'".formatted(id));
        }
        String sql = switch (dialect) {
            case "postgresql" -> action == Action.CANCEL ? "SELECT pg_cancel_backend(?)" : "SELECT pg_terminate_backend(?)";
            case "h2" -> action == Action.CANCEL ? "CALL CANCEL_SESSION(?)" : "CALL ABORT_SESSION(?)";
            // KILL takes no bind parameters; the id was validated as digits above
            case "mysql", "mariadb" -> (action == Action.CANCEL ? "KILL QUERY " : "KILL ") + id;
            case "sqlserver" -> {
                if (action == Action.CANCEL) {
                    throw new IllegalArgumentException("SQL Server can only end the whole session (KILL)");
                }
                yield "KILL " + id;
            }
            case "oracle" -> (action == Action.CANCEL ? "ALTER SYSTEM CANCEL SQL '" : "ALTER SYSTEM KILL SESSION '") + id + "'";
            default -> throw unsupported(session, "Session manager");
        };
        boolean bind = sql.contains("?");
        session.call(conn -> {
            if (bind) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, Long.parseLong(id));
                    ps.execute();
                }
            } else {
                try (Statement st = conn.createStatement()) {
                    st.execute(sql);
                }
            }
            return null;
        });
    }

    public List<Metric> metrics(DbSession session) throws SQLException {
        String dialect = dialect(session);
        return switch (dialect) {
            case "postgresql" -> row(session, """
                    SELECT (SELECT count(*) FROM pg_stat_activity WHERE backend_type = 'client backend') AS connections,
                           (SELECT count(*) FROM pg_stat_activity WHERE state = 'active' AND backend_type = 'client backend') AS active,
                           sum(xact_commit + xact_rollback) AS transactions,
                           sum(tup_returned + tup_fetched) AS rows_read,
                           sum(tup_inserted + tup_updated + tup_deleted) AS rows_written,
                           round(sum(blks_hit) * 100.0 / nullif(sum(blks_hit + blks_read), 0), 2) AS cache_hit,
                           (SELECT pg_database_size(current_database()) / 1048576.0) AS database_size
                    FROM pg_stat_database""",
                    new String[]{"connections", "active", "transactions", "rows_read", "rows_written", "cache_hit", "database_size"},
                    new MetricKind[]{MetricKind.GAUGE, MetricKind.GAUGE, MetricKind.COUNTER, MetricKind.COUNTER, MetricKind.COUNTER,
                            MetricKind.GAUGE, MetricKind.GAUGE},
                    new String[]{"", "", "/s", "/s", "/s", "%", "MB"});
            case "mysql", "mariadb" -> mysqlStatus(session);
            case "sqlserver" -> row(session, """
                    SELECT (SELECT COUNT(*) FROM sys.dm_exec_sessions WHERE is_user_process = 1) AS connections,
                           (SELECT COUNT(*) FROM sys.dm_exec_requests WHERE session_id > 50) AS active,
                           (SELECT cntr_value FROM sys.dm_os_performance_counters WHERE counter_name = 'Batch Requests/sec') AS batches""",
                    new String[]{"connections", "active", "batches"},
                    new MetricKind[]{MetricKind.GAUGE, MetricKind.GAUGE, MetricKind.COUNTER}, new String[]{"", "", "/s"});
            case "h2" -> row(session, "SELECT (SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS) AS connections, MEMORY_USED() / 1024.0 AS memory",
                    new String[]{"connections", "memory"}, new MetricKind[]{MetricKind.GAUGE, MetricKind.GAUGE}, new String[]{"", "MB"});
            case "sqlite" -> session.call(conn -> {
                double pages = pragma(conn, "PRAGMA page_count");
                double size = pragma(conn, "PRAGMA page_size");
                return List.of(new Metric("database_size", pages * size / 1048576.0, MetricKind.GAUGE, "MB"));
            });
            default -> throw unsupported(session, "Dashboard");
        };
    }

    private List<Metric> row(DbSession session, String sql, String[] names, MetricKind[] kinds, String[] units) throws SQLException {
        return session.call(conn -> {
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                List<Metric> out = new ArrayList<>();
                if (rs.next()) {
                    for (int i = 0; i < names.length; i++) {
                        double v = rs.getDouble(i + 1);
                        if (!rs.wasNull()) {
                            out.add(new Metric(names[i], v, kinds[i], units[i]));
                        }
                    }
                }
                return out;
            }
        });
    }

    private List<Metric> mysqlStatus(DbSession session) throws SQLException {
        return session.call(conn -> {
            List<Metric> out = new ArrayList<>();
            double requests = 0;
            double reads = 0;
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                    "SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected', 'Threads_running', 'Questions', 'Bytes_received', "
                            + "'Bytes_sent', 'Innodb_buffer_pool_read_requests', 'Innodb_buffer_pool_reads')")) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    double value = rs.getDouble(2);
                    switch (name.toLowerCase(Locale.ROOT)) {
                        case "threads_connected" -> out.add(new Metric("connections", value, MetricKind.GAUGE, ""));
                        case "threads_running" -> out.add(new Metric("active", value, MetricKind.GAUGE, ""));
                        case "questions" -> out.add(new Metric("queries", value, MetricKind.COUNTER, "/s"));
                        case "bytes_received" -> out.add(new Metric("received", value / 1024, MetricKind.COUNTER, "KB/s"));
                        case "bytes_sent" -> out.add(new Metric("sent", value / 1024, MetricKind.COUNTER, "KB/s"));
                        case "innodb_buffer_pool_read_requests" -> requests = value;
                        case "innodb_buffer_pool_reads" -> reads = value;
                        default -> {
                        }
                    }
                }
            }
            if (requests > 0) {
                out.add(new Metric("cache_hit", Math.round((1 - reads / requests) * 10000) / 100.0, MetricKind.GAUGE, "%"));
            }
            return out;
        });
    }

    private static double pragma(java.sql.Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getDouble(1) : 0;
        }
    }

    private static IllegalArgumentException unsupported(DbSession session, String what) throws SQLException {
        String product = session.call(conn -> conn.getMetaData().getDatabaseProductName());
        return new IllegalArgumentException("%s is not available for %s".formatted(what, product));
    }
}
