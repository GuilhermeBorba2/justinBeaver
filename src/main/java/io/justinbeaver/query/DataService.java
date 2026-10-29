package io.justinbeaver.query;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.justinbeaver.meta.MetadataService;
import io.justinbeaver.query.QueryModels.Column;
import io.justinbeaver.query.QueryModels.DataPage;
import io.justinbeaver.session.DbSession;
import org.springframework.stereotype.Service;

/**
 * The "Data" tab of a table: {@code SELECT *} with optional filter and sort, paged client-side
 * over JDBC so it works on every database regardless of LIMIT/OFFSET/FETCH dialect.
 * Table and sort column are checked against the database metadata and then quoted; the optional
 * WHERE text is the user's own SQL fragment, executed with the user's own privileges.
 */
@Service
public class DataService {

    private final MetadataService metadata;

    public DataService(MetadataService metadata) {
        this.metadata = metadata;
    }

    public DataPage page(DbSession session, String catalog, String schema, String table, int limit, int offset,
                         String orderBy, boolean descending, String where, int timeoutSeconds) throws SQLException {
        if (hasSeparator(where)) {
            throw new IllegalArgumentException("The filter must not contain ';'");
        }
        return session.call(conn -> {
            if (!metadata.tableExists(conn, catalog, schema, table)) {
                throw new IllegalArgumentException("Table '%s' not found".formatted(table));
            }
            DatabaseMetaData meta = conn.getMetaData();
            String quote = meta.getIdentifierQuoteString();
            StringBuilder sql = new StringBuilder("SELECT * FROM ").append(qualified(meta, quote, catalog, schema, table));
            if (where != null && !where.isBlank()) {
                sql.append(" WHERE ").append(where);
            }
            boolean ordered = orderBy != null && !orderBy.isBlank();
            if (ordered) {
                requireColumn(meta, catalog, schema, table, orderBy);
                sql.append(" ORDER BY ").append(quoteIdentifier(quote, orderBy)).append(descending ? " DESC" : " ASC");
            }
            // Page on the server when the dialect is known; otherwise read and skip rows over JDBC.
            String paging = pagingClause(meta.getDatabaseProductName(), ordered, offset, limit + 1);
            if (paging != null) {
                sql.append(paging);
            }
            long started = System.nanoTime();
            try (Statement statement = conn.createStatement()) {
                statement.setMaxRows(paging != null ? limit + 1 : offset + limit + 1);
                statement.setQueryTimeout(timeoutSeconds);
                session.setRunning(statement);
                try (ResultSet rs = statement.executeQuery(sql.toString())) {
                    return readPage(rs, limit, offset, paging != null ? 0 : offset, started);
                } finally {
                    session.setRunning(null);
                }
            }
        });
    }

    /** "Calculate total rows": {@code SELECT COUNT(*)} with the same filter as the data tab. */
    public long count(DbSession session, String catalog, String schema, String table, String where, int timeoutSeconds)
            throws SQLException {
        if (hasSeparator(where)) {
            throw new IllegalArgumentException("The filter must not contain ';'");
        }
        return session.call(conn -> {
            if (!metadata.tableExists(conn, catalog, schema, table)) {
                throw new IllegalArgumentException("Table '%s' not found".formatted(table));
            }
            DatabaseMetaData meta = conn.getMetaData();
            String sql = "SELECT COUNT(*) FROM " + qualified(meta, meta.getIdentifierQuoteString(), catalog, schema, table)
                    + (where != null && !where.isBlank() ? " WHERE " + where : "");
            try (Statement statement = conn.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds);
                session.setRunning(statement);
                try (ResultSet rs = statement.executeQuery(sql)) {
                    rs.next();
                    return rs.getLong(1);
                } finally {
                    session.setRunning(null);
                }
            }
        });
    }

    /** True when the filter holds more than one statement; a ';' inside a quoted string or comment is fine. */
    static boolean hasSeparator(String where) {
        return where != null && where.contains(";") && ScriptSplitter.split(where).size() > 1;
    }

    /** LIMIT/OFFSET in the database's syntax; null for databases without a known form. */
    static String pagingClause(String product, boolean ordered, int offset, int fetch) {
        String p = product.toLowerCase(java.util.Locale.ROOT);
        if (p.contains("postgres") || p.contains("mysql") || p.contains("mariadb") || p.contains("sqlite")
                || p.equals("h2") || p.contains("hsql") || p.contains("duckdb") || p.contains("clickhouse")) {
            return " LIMIT " + fetch + " OFFSET " + offset;
        }
        if (p.contains("microsoft sql server")) {
            return (ordered ? "" : " ORDER BY (SELECT NULL)") + " OFFSET " + offset + " ROWS FETCH NEXT " + fetch + " ROWS ONLY";
        }
        if (p.contains("oracle") || p.contains("db2")) {
            return " OFFSET " + offset + " ROWS FETCH NEXT " + fetch + " ROWS ONLY";
        }
        return null;
    }

    private static DataPage readPage(ResultSet rs, int limit, int offset, int skip, long started) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int count = meta.getColumnCount();
        List<Column> columns = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            columns.add(ValueMapper.column(meta, i));
        }
        for (int skipped = 0; skipped < skip; skipped++) {
            if (!rs.next()) {
                return new DataPage(columns, List.of(), offset, false, QueryService.elapsedMs(started));
            }
        }
        List<List<Object>> rows = new ArrayList<>();
        boolean hasMore = false;
        while (rs.next()) {
            if (rows.size() == limit) {
                hasMore = true;
                break;
            }
            List<Object> row = new ArrayList<>(count);
            for (int i = 1; i <= count; i++) {
                row.add(ValueMapper.read(rs, i));
            }
            rows.add(row);
        }
        return new DataPage(columns, rows, offset, hasMore, QueryService.elapsedMs(started));
    }

    public static String qualified(DatabaseMetaData meta, String quote, String catalog, String schema, String table)
            throws SQLException {
        List<String> parts = new ArrayList<>();
        if (notBlank(catalog) && meta.supportsCatalogsInTableDefinitions()) {
            parts.add(quoteIdentifier(quote, catalog));
        }
        if (notBlank(schema) && meta.supportsSchemasInTableDefinitions()) {
            parts.add(quoteIdentifier(quote, schema));
        }
        parts.add(quoteIdentifier(quote, table));
        return String.join(".", parts);
    }

    private static void requireColumn(DatabaseMetaData meta, String catalog, String schema, String table, String column)
            throws SQLException {
        Set<String> names = new HashSet<>();
        try (ResultSet rs = meta.getColumns(notBlank(catalog) ? catalog : null, notBlank(schema) ? schema : null,
                table, "%")) {
            while (rs.next()) {
                names.add(rs.getString("COLUMN_NAME"));
            }
        }
        if (!names.contains(column)) {
            throw new IllegalArgumentException("Column '%s' not found in '%s'".formatted(column, table));
        }
    }

    /** Quote per the driver's identifier quote string; a blank quote string means quoting is unsupported. */
    public static String quoteIdentifier(String quote, String identifier) {
        if (quote == null || quote.isBlank()) {
            return identifier;
        }
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
