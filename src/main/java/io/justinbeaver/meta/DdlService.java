package io.justinbeaver.meta;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import io.justinbeaver.query.DataService;
import io.justinbeaver.session.DbSession;
import org.springframework.stereotype.Service;

/**
 * The "DDL" / "Source" tab: the database's own definition when it exposes one (SQLite, MySQL,
 * PostgreSQL views), otherwise a CREATE TABLE generated from JDBC metadata (columns, primary key,
 * foreign keys, indexes). Generated DDL is portable-ish, not a byte-exact dump.
 */
@Service
public class DdlService {

    private static final Set<String> SIZED_TYPES = Set.of("char", "varchar", "nchar", "nvarchar", "character",
            "character varying", "varbinary", "binary", "bit varying", "varchar2", "nvarchar2", "raw");
    private static final Set<String> PRECISION_TYPES = Set.of("decimal", "numeric", "number");

    private final MetadataService metadata;

    public DdlService(MetadataService metadata) {
        this.metadata = metadata;
    }

    public String ddl(DbSession session, String catalog, String schema, String table) throws SQLException {
        return session.call(conn -> {
            String cat = MetadataService.blankToNull(catalog);
            String sch = MetadataService.blankToNull(schema);
            if (!metadata.tableExists(conn, cat, sch, table)) {
                throw new IllegalArgumentException("Table '%s' not found".formatted(table));
            }
            DatabaseMetaData meta = conn.getMetaData();
            String product = meta.getDatabaseProductName().toLowerCase(Locale.ROOT);
            boolean view = isView(meta, cat, sch, table);
            String quote = meta.getIdentifierQuoteString();
            String qualified = DataService.qualified(meta, quote, cat, sch, table);

            String nativeDdl = nativeDdl(conn, product, qualified, table, view);
            if (nativeDdl != null) {
                return nativeDdl;
            }
            return view ? viewDdl(conn, qualified, sch, table) : generatedTableDdl(meta, quote, qualified, cat, sch, table);
        });
    }

    private static boolean isView(DatabaseMetaData meta, String catalog, String schema, String table) throws SQLException {
        try (ResultSet rs = meta.getTables(catalog, MetadataService.escapePattern(meta, schema),
                MetadataService.escapePattern(meta, table), null)) {
            while (rs.next()) {
                if (table.equals(rs.getString("TABLE_NAME"))) {
                    return rs.getString("TABLE_TYPE").toUpperCase(Locale.ROOT).contains("VIEW");
                }
            }
        }
        return false;
    }

    /** Native definitions; null when the database has no cheap way to produce one. */
    private static String nativeDdl(Connection conn, String product, String qualified, String table, boolean view)
            throws SQLException {
        if (product.contains("sqlite")) {
            List<String> parts = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL AND (name = ? OR (tbl_name = ? AND type IN ('index', 'trigger'))) "
                            + "ORDER BY CASE type WHEN 'table' THEN 0 WHEN 'view' THEN 0 WHEN 'index' THEN 1 ELSE 2 END")) {
                ps.setString(1, table);
                ps.setString(2, table);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        parts.add(rs.getString(1).trim() + ";");
                    }
                }
            }
            return parts.isEmpty() ? null : String.join("\n\n", parts);
        }
        if (product.contains("mysql") || product.contains("mariadb")) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery((view ? "SHOW CREATE VIEW " : "SHOW CREATE TABLE ") + qualified)) {
                return rs.next() ? rs.getString(2) + ";" : null;
            }
        }
        if (product.contains("postgresql") && view) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT pg_get_viewdef(?::regclass, true)")) {
                ps.setString(1, qualified);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? "CREATE OR REPLACE VIEW " + qualified + " AS\n" + rs.getString(1).trim() : null;
                }
            }
        }
        return null;
    }

    /** INFORMATION_SCHEMA.VIEWS is standard (H2, PostgreSQL, SQL Server, MySQL); fall back to a comment. */
    private static String viewDdl(Connection conn, String qualified, String schema, String table) {
        String sql = "SELECT VIEW_DEFINITION FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_NAME = ?"
                + (schema != null ? " AND TABLE_SCHEMA = ?" : "");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            if (schema != null) {
                ps.setString(2, schema);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getString(1) != null) {
                    return "CREATE VIEW " + qualified + " AS\n" + rs.getString(1).trim() + ";";
                }
            }
        } catch (SQLException ignored) {
            // no INFORMATION_SCHEMA on this database
        }
        return "-- The view definition of " + qualified + " is not available through JDBC on this database.";
    }

    private static String generatedTableDdl(DatabaseMetaData meta, String quote, String qualified, String catalog,
                                            String schema, String table) throws SQLException {
        List<String> lines = new ArrayList<>();
        try (ResultSet rs = meta.getColumns(catalog, MetadataService.escapePattern(meta, schema),
                MetadataService.escapePattern(meta, table), "%")) {
            while (rs.next()) {
                if (!table.equals(rs.getString("TABLE_NAME"))) {
                    continue;
                }
                StringBuilder line = new StringBuilder("    ")
                        .append(DataService.quoteIdentifier(quote, rs.getString("COLUMN_NAME"))).append(' ')
                        .append(typeOf(rs.getString("TYPE_NAME"), rs.getInt("COLUMN_SIZE"), rs.getInt("DECIMAL_DIGITS")));
                String typeName = rs.getString("TYPE_NAME");
                if (rs.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls) {
                    line.append(" NOT NULL");
                }
                String def = rs.getString("COLUMN_DEF");
                if (def != null && !def.isBlank() && !impliedBySerial(typeName, def)) {
                    line.append(" DEFAULT ").append(def.trim());
                }
                lines.add(line.toString());
            }
        }

        Map<Short, String> pk = new TreeMap<>();
        String pkName = null;
        try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                pk.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
                pkName = rs.getString("PK_NAME");
            }
        }
        if (!pk.isEmpty()) {
            lines.add("    " + constraint(quote, pkName) + "PRIMARY KEY (" + columnList(quote, pk.values()) + ")");
        }

        Map<String, ForeignKey> fks = new LinkedHashMap<>();
        try (ResultSet rs = meta.getImportedKeys(catalog, schema, table)) {
            while (rs.next()) {
                String name = rs.getString("FK_NAME");
                // qualify the target the same way as the table itself: no catalog when the table has none
                String target = DataService.qualified(meta, quote, catalog == null ? null : rs.getString("PKTABLE_CAT"),
                        rs.getString("PKTABLE_SCHEM"), rs.getString("PKTABLE_NAME"));
                ForeignKey fk = fks.computeIfAbsent(name == null ? target : name, n -> new ForeignKey(name, target));
                fk.columns.add(rs.getString("FKCOLUMN_NAME"));
                fk.refColumns.add(rs.getString("PKCOLUMN_NAME"));
            }
        }
        for (ForeignKey fk : fks.values()) {
            lines.add("    " + constraint(quote, fk.name) + "FOREIGN KEY (" + columnList(quote, fk.columns) + ") REFERENCES "
                    + fk.target + " (" + columnList(quote, fk.refColumns) + ")");
        }

        StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(qualified).append(" (\n")
                .append(String.join(",\n", lines)).append("\n);");

        Map<String, Index> indexes = new LinkedHashMap<>();
        try (ResultSet rs = meta.getIndexInfo(catalog, schema, table, false, true)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (name == null) {
                    continue;
                }
                indexes.computeIfAbsent(name, n -> new Index()).add(rs.getShort("ORDINAL_POSITION"), rs.getString("COLUMN_NAME"),
                        !rs.getBoolean("NON_UNIQUE"));
            }
        }
        List<String> pkColumns = List.copyOf(pk.values());
        for (Map.Entry<String, Index> e : indexes.entrySet()) {
            Index index = e.getValue();
            List<String> columns = List.copyOf(index.columns.values());
            if (index.unique && columns.equals(pkColumns)) {
                continue; // the index backing the primary key
            }
            ddl.append("\n\nCREATE ").append(index.unique ? "UNIQUE " : "").append("INDEX ")
                    .append(DataService.quoteIdentifier(quote, e.getKey())).append(" ON ").append(qualified)
                    .append(" (").append(columnList(quote, columns)).append(");");
        }
        return ddl.toString();
    }

    /** PostgreSQL's {@code serial} types already create the sequence default; repeating it makes the DDL invalid. */
    static boolean impliedBySerial(String typeName, String def) {
        return typeName != null && typeName.toLowerCase(Locale.ROOT).endsWith("serial") && def.trim().toLowerCase(Locale.ROOT).startsWith("nextval(");
    }

    static String typeOf(String typeName, int size, int decimals) {
        if (typeName.length() > 1 && typeName.charAt(0) == '_') {
            return typeName.substring(1) + "[]"; // PostgreSQL array types are reported as _text, _int4 …
        }
        String lower = typeName.toLowerCase(Locale.ROOT);
        if (typeName.contains("(") || size <= 0) {
            return typeName;
        }
        if (SIZED_TYPES.contains(lower) && size < 1_000_000) {
            return typeName + "(" + size + ")";
        }
        if (PRECISION_TYPES.contains(lower) && size < 1000) {
            return typeName + "(" + size + (decimals > 0 ? ", " + decimals : "") + ")";
        }
        return typeName;
    }

    private static String constraint(String quote, String name) {
        return name == null || name.isBlank() ? "" : "CONSTRAINT " + DataService.quoteIdentifier(quote, name) + " ";
    }

    private static String columnList(String quote, Iterable<String> columns) {
        List<String> quoted = new ArrayList<>();
        columns.forEach(c -> quoted.add(DataService.quoteIdentifier(quote, c)));
        return String.join(", ", quoted);
    }

    private static final class ForeignKey {
        final String name;
        final String target;
        final List<String> columns = new ArrayList<>();
        final List<String> refColumns = new ArrayList<>();

        ForeignKey(String name, String target) {
            this.name = name;
            this.target = target;
        }
    }

    private static final class Index {
        final Map<Short, String> columns = new TreeMap<>();
        boolean unique;

        void add(short position, String column, boolean isUnique) {
            columns.put(position, column);
            unique = isUnique;
        }
    }
}
