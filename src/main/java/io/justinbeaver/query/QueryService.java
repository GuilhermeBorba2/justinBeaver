package io.justinbeaver.query;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import io.justinbeaver.query.QueryModels.Column;
import io.justinbeaver.query.QueryModels.QueryResult;
import io.justinbeaver.query.QueryModels.StatementResult;
import io.justinbeaver.session.DbSession;
import org.springframework.stereotype.Service;

/** Runs user SQL: one statement ({@link #execute}) or a script ({@link #executeScript}). */
@Service
public class QueryService {

    public QueryResult execute(DbSession session, String sql, int maxRows, int timeoutSeconds) throws SQLException {
        io.justinbeaver.session.ReadOnlyGuard.check(session, sql);
        return session.call(conn -> run(session, conn, sql, maxRows, timeoutSeconds));
    }

    /**
     * Each statement runs in order on the same connection. With {@code stopOnError} the first failure
     * ends the script; its error is the last entry returned.
     */
    public List<StatementResult> executeScript(DbSession session, String script, int maxRows, int timeoutSeconds,
                                               boolean stopOnError) throws SQLException {
        List<StatementResult> results = new ArrayList<>();
        for (String sql : ScriptSplitter.split(script)) {
            try {
                results.add(new StatementResult(sql, execute(session, sql, maxRows, timeoutSeconds), null));
            } catch (SQLException e) {
                results.add(new StatementResult(sql, null, e.getMessage()));
                if (stopOnError) {
                    break;
                }
            }
        }
        return results;
    }

    /**
     * "Explain execution plan": the database's own EXPLAIN, returned as a result set. Databases whose plans
     * need extra setup (Oracle PLAN_TABLE, SQL Server SHOWPLAN sessions) are not supported yet.
     */
    public QueryResult explain(DbSession session, String sql, int maxRows, int timeoutSeconds) throws SQLException {
        io.justinbeaver.session.ReadOnlyGuard.check(session, sql); // "EXPLAIN ANALYZE DELETE ..." would run the delete
        return session.call(conn -> {
            String product = conn.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT);
            String prefix;
            if (product.contains("sqlite")) {
                prefix = "EXPLAIN QUERY PLAN ";
            } else if (product.contains("postgresql") || product.contains("mysql") || product.contains("mariadb")
                    || product.contains("h2") || product.contains("duckdb") || product.contains("clickhouse")
                    || product.contains("hsql")) {
                prefix = product.contains("hsql") ? "EXPLAIN PLAN FOR " : "EXPLAIN ";
            } else {
                throw new IllegalArgumentException("Explain plan is not supported for " + conn.getMetaData().getDatabaseProductName());
            }
            String statement = sql.strip();
            if (statement.endsWith(";")) {
                statement = statement.substring(0, statement.length() - 1);
            }
            return run(session, conn, prefix + statement, maxRows, timeoutSeconds);
        });
    }

    private QueryResult run(DbSession session, Connection conn, String sql, int maxRows, int timeoutSeconds)
            throws SQLException {
        long started = System.nanoTime();
        boolean oracle = conn.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("oracle");
        if (oracle) {
            enableDbmsOutput(conn);
        }
        conn.clearWarnings();
        try (Statement statement = conn.createStatement()) {
            statement.setMaxRows(maxRows + 1); // one extra row tells us whether the result was cut
            statement.setQueryTimeout(timeoutSeconds);
            session.setRunning(statement);
            QueryResult result;
            try {
                boolean hasResultSet = statement.execute(sql);
                if (!hasResultSet) {
                    long count = statement.getLargeUpdateCount();
                    result = new QueryResult(List.of(), List.of(), false, Math.max(count, 0), elapsedMs(started));
                } else {
                    try (ResultSet rs = statement.getResultSet()) {
                        result = read(rs, maxRows, started);
                        if (session.isReadOnly()) {
                            result = result.withSource(null, "the connection is read-only");
                        } else {
                            EditableSources.Outcome editable = EditableSources.find(conn, rs.getMetaData());
                            result = result.withSource(editable.source(), editable.reason());
                        }
                    }
                }
            } finally {
                session.setRunning(null);
            }
            List<String> output = new ArrayList<>();
            collect(statement.getWarnings(), output);
            collect(conn.getWarnings(), output);
            conn.clearWarnings();
            if (oracle) {
                drainDbmsOutput(conn, output);
            }
            return result.withMessages(output);
        }
    }

    private static final int MAX_MESSAGES = 1000;

    /** "Show server output": the SQLWarning chain carries notices and PRINT output on most drivers. */
    private static void collect(java.sql.SQLWarning warning, List<String> out) {
        for (java.sql.SQLWarning w = warning; w != null && out.size() < MAX_MESSAGES; w = w.getNextWarning()) {
            if (w.getMessage() != null && !out.contains(w.getMessage())) {
                out.add(w.getMessage());
            }
        }
    }

    private static void enableDbmsOutput(Connection conn) {
        try (Statement st = conn.createStatement()) {
            st.execute("BEGIN DBMS_OUTPUT.ENABLE(NULL); END;");
        } catch (SQLException ignored) {
            // no DBMS_OUTPUT privilege: skip server output
        }
    }

    private static void drainDbmsOutput(Connection conn, List<String> out) {
        try (java.sql.CallableStatement call = conn.prepareCall("BEGIN DBMS_OUTPUT.GET_LINE(?, ?); END;")) {
            call.registerOutParameter(1, java.sql.Types.VARCHAR);
            call.registerOutParameter(2, java.sql.Types.INTEGER);
            while (out.size() < MAX_MESSAGES) {
                call.execute();
                if (call.getInt(2) != 0) {
                    break;
                }
                out.add(call.getString(1) == null ? "" : call.getString(1));
            }
        } catch (SQLException ignored) {
            // DBMS_OUTPUT unavailable
        }
    }

    static QueryResult read(ResultSet rs, int maxRows, long startedNanos) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int count = meta.getColumnCount();
        List<Column> columns = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            columns.add(ValueMapper.column(meta, i));
        }
        List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (rows.size() == maxRows) {
                truncated = true;
                break;
            }
            List<Object> row = new ArrayList<>(count);
            for (int i = 1; i <= count; i++) {
                row.add(ValueMapper.read(rs, i));
            }
            rows.add(row);
        }
        return new QueryResult(columns, rows, truncated, null, elapsedMs(startedNanos));
    }

    static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
