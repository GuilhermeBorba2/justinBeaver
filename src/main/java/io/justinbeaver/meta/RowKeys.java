package io.justinbeaver.meta;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * How rows of a table are addressed for UPDATE/DELETE from the grid. Like DBeaver: the primary key;
 * without one, the smallest unique index; without that, a "virtual key" made of every comparable
 * column. With the last two, NULL key values match with IS NULL, and the edit service still refuses
 * any change that does not hit exactly one row, so duplicate rows can never be changed together.
 */
public final class RowKeys {

    public enum Kind { PRIMARY_KEY, UNIQUE_INDEX, ALL_COLUMNS }

    public record RowKey(Kind kind, List<String> columns) {

        /** Primary key columns are never NULL; the fallbacks may be. */
        public boolean allowsNulls() {
            return kind != Kind.PRIMARY_KEY;
        }
    }

    /** Types without a usable "=" (LOBs, binary, json/xml/arrays) or with unreliable equality (floating point). */
    private static final Set<Integer> NOT_COMPARABLE = Set.of(Types.BLOB, Types.CLOB, Types.NCLOB, Types.BINARY, Types.VARBINARY,
            Types.LONGVARBINARY, Types.SQLXML, Types.ARRAY, Types.STRUCT, Types.OTHER, Types.JAVA_OBJECT, Types.REAL, Types.FLOAT,
            Types.DOUBLE, Types.REF, Types.DATALINK, Types.ROWID);

    private RowKeys() {
    }

    /**
     * @param columnTypes the table's columns in order, with their java.sql.Types
     * @return the key to use, or null when no column can identify a row
     */
    public static RowKey find(DatabaseMetaData meta, String catalog, String schema, String table, Map<String, Integer> columnTypes)
            throws SQLException {
        Map<Short, String> pk = new TreeMap<>();
        try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                pk.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        if (!pk.isEmpty()) {
            return new RowKey(Kind.PRIMARY_KEY, List.copyOf(pk.values()));
        }
        List<String> unique = smallestUniqueIndex(meta, catalog, schema, table, columnTypes);
        if (unique != null) {
            return new RowKey(Kind.UNIQUE_INDEX, unique);
        }
        List<String> all = new ArrayList<>();
        columnTypes.forEach((name, type) -> {
            if (!NOT_COMPARABLE.contains(type)) {
                all.add(name);
            }
        });
        return all.isEmpty() ? null : new RowKey(Kind.ALL_COLUMNS, List.copyOf(all));
    }

    private static List<String> smallestUniqueIndex(DatabaseMetaData meta, String catalog, String schema, String table,
                                                    Map<String, Integer> columnTypes) {
        Map<String, Map<Short, String>> indexes = new LinkedHashMap<>();
        Set<String> unusable = new java.util.HashSet<>();
        try (ResultSet rs = meta.getIndexInfo(catalog, schema, table, true, true)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (name == null) {
                    continue;
                }
                String column = rs.getString("COLUMN_NAME");
                String filter = columnOrNull(rs, "FILTER_CONDITION");
                // expression indexes and partial indexes do not identify rows by plain column values
                if (column == null || !columnTypes.containsKey(column) || (filter != null && !filter.isBlank())) {
                    unusable.add(name);
                    continue;
                }
                indexes.computeIfAbsent(name, n -> new TreeMap<>()).put(rs.getShort("ORDINAL_POSITION"), column);
            }
        } catch (SQLException | RuntimeException e) {
            return null;
        }
        return indexes.entrySet().stream()
                .filter(e -> !unusable.contains(e.getKey()))
                .map(e -> List.copyOf(e.getValue().values()))
                .min(Comparator.comparingInt(List::size))
                .orElse(null);
    }

    private static String columnOrNull(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException e) {
            return null;
        }
    }
}
