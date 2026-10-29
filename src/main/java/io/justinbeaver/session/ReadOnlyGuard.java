package io.justinbeaver.session;

import java.util.regex.Pattern;

/**
 * Second line of defence for read-only connections (the first is {@code Connection.setReadOnly},
 * enforced by the database where supported): only statements that start like a query may run.
 * A data-modifying CTE ({@code WITH x AS (DELETE ...)}) can slip past this check, which is why the
 * database-side flag matters.
 */
public final class ReadOnlyGuard {

    private static final Pattern READ = Pattern.compile(
            "^\\s*(?:(?:--[^\\n]*\\n|/\\*[\\s\\S]*?\\*/)\\s*)*(select|with|show|explain|describe|desc|pragma|values|table)\\b",
            Pattern.CASE_INSENSITIVE);

    private ReadOnlyGuard() {
    }

    public static boolean looksReadOnly(String sql) {
        return READ.matcher(sql).find();
    }

    public static void check(DbSession session, String sql) {
        if (session.isReadOnly() && !looksReadOnly(sql)) {
            throw new IllegalArgumentException("This connection is read-only: only queries are allowed");
        }
        // "SELECT 1; DROP TABLE t" is one execute() call on drivers that accept several statements
        if (session.isReadOnly() && sql.contains(";") && io.justinbeaver.query.ScriptSplitter.split(sql).size() > 1) {
            throw new IllegalArgumentException("This connection is read-only: run one statement at a time");
        }
    }

    public static void checkWrite(DbSession session) {
        if (session.isReadOnly()) {
            throw new IllegalArgumentException("This connection is read-only");
        }
    }
}
