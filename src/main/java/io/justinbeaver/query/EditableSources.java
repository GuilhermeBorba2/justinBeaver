package io.justinbeaver.query;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.justinbeaver.meta.MetadataService;
import io.justinbeaver.meta.RowKeys;
import io.justinbeaver.query.QueryModels.EditableSource;

/**
 * Decides whether a query result can be edited in place (DBeaver edits result sets of single-table
 * queries): every column that comes from a table must come from the same one, and that table's primary
 * key must be in the result. Uses the driver's result-set metadata (base table of each column), so it
 * only works where the driver reports it (PostgreSQL, MySQL/MariaDB, H2, SQLite, SQL Server...).
 */
final class EditableSources {

    private static final int MAX_COLUMNS = 500;

    private EditableSources() {
    }

    /** Either an editable source, or the reason the result is read-only. */
    record Outcome(EditableSource source, String reason) {
        static Outcome no(String reason) {
            return new Outcome(null, reason);
        }
    }

    static Outcome find(Connection conn, ResultSetMetaData rsmd) {
        try {
            return resolve(conn, rsmd);
        } catch (SQLException | RuntimeException | AbstractMethodError e) {
            return Outcome.no("the driver does not report where the columns come from");
        }
    }

    private record TableKey(String catalog, String schema, String table) {
    }

    private static Outcome resolve(Connection conn, ResultSetMetaData rsmd) throws SQLException {
        int count = rsmd.getColumnCount();
        if (count == 0 || count > MAX_COLUMNS) {
            return Outcome.no("too many columns to edit");
        }
        Set<TableKey> tables = new LinkedHashSet<>();
        for (int i = 1; i <= count; i++) {
            String table = blankToNull(rsmd.getTableName(i));
            if (table != null) {
                tables.add(new TableKey(blankToNull(rsmd.getCatalogName(i)), blankToNull(rsmd.getSchemaName(i)), table));
            }
        }
        if (tables.isEmpty()) {
            return Outcome.no("no column comes straight from a table (expressions, functions or a driver without this metadata)");
        }
        if (tables.size() > 1) {
            return Outcome.no("the columns come from more than one table");
        }
        TableKey key = tables.iterator().next();
        DatabaseMetaData meta = conn.getMetaData();
        TableKey resolved = locate(conn, meta, key);
        if (resolved == null) {
            return Outcome.no("table %s exists in more than one schema; qualify it with its schema".formatted(key.table()));
        }
        Map<String, Integer> types = new LinkedHashMap<>();
        try (ResultSet rs = meta.getColumns(resolved.catalog(), MetadataService.escapePattern(meta, resolved.schema()),
                MetadataService.escapePattern(meta, resolved.table()), "%")) {
            while (rs.next()) {
                if (resolved.table().equals(rs.getString("TABLE_NAME"))) {
                    types.put(rs.getString("COLUMN_NAME"), rs.getInt("DATA_TYPE"));
                }
            }
        }
        if (types.isEmpty()) {
            return Outcome.no("%s is not a table that can be changed (a view or a function?)".formatted(resolved.table()));
        }
        RowKeys.RowKey rowKey = RowKeys.find(meta, resolved.catalog(), resolved.schema(), resolved.table(), types);
        if (rowKey == null) {
            return Outcome.no("%s has no primary key, unique index or comparable column".formatted(resolved.table()));
        }
        List<String> mapped = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            mapped.add(blankToNull(rsmd.getTableName(i)) == null ? null : baseColumn(rsmd, i, types.keySet()));
        }
        List<String> missing = rowKey.columns().stream().filter(c -> !mapped.contains(c)).toList();
        if (!missing.isEmpty()) {
            String what = switch (rowKey.kind()) {
                case PRIMARY_KEY -> "primary key";
                case UNIQUE_INDEX -> "unique key";
                case ALL_COLUMNS -> "columns (the table has no key, so rows are matched by all their values)";
            };
            return Outcome.no("add the %s %s to the SELECT to edit".formatted(what, String.join(", ", missing)));
        }
        return new Outcome(new EditableSource(resolved.catalog(), resolved.schema(), resolved.table(), mapped, rowKey.columns(),
                rowKey.kind().name()), null);
    }

    /** The table column a result column shows: its base name, else its label, matched exactly then ignoring case. */
    private static String baseColumn(ResultSetMetaData rsmd, int i, Set<String> tableColumns) throws SQLException {
        for (String candidate : new String[]{rsmd.getColumnName(i), rsmd.getColumnLabel(i)}) {
            if (candidate != null && tableColumns.contains(candidate)) {
                return candidate;
            }
        }
        String label = rsmd.getColumnLabel(i);
        return tableColumns.stream().filter(c -> c.equalsIgnoreCase(label)).findFirst().orElse(null);
    }

    /** Fill in the schema when the driver does not report it (PostgreSQL): the only match, or the current one. */
    private static TableKey locate(Connection conn, DatabaseMetaData meta, TableKey key) throws SQLException {
        if (key.schema() != null || !meta.supportsSchemasInTableDefinitions()) {
            return key;
        }
        Set<String> schemas = new LinkedHashSet<>();
        try (ResultSet rs = meta.getTables(key.catalog(), null, MetadataService.escapePattern(meta, key.table()), null)) {
            while (rs.next()) {
                if (key.table().equals(rs.getString("TABLE_NAME"))) {
                    schemas.add(rs.getString("TABLE_SCHEM"));
                }
            }
        }
        if (schemas.size() == 1) {
            return new TableKey(key.catalog(), schemas.iterator().next(), key.table());
        }
        String current = null;
        try {
            current = conn.getSchema();
        } catch (SQLException | AbstractMethodError ignored) {
            // unknown
        }
        return current != null && schemas.contains(current) ? new TableKey(key.catalog(), current, key.table()) : null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
