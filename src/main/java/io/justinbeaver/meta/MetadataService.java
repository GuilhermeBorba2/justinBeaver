package io.justinbeaver.meta;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.justinbeaver.meta.MetaModels.ColumnInfo;
import io.justinbeaver.meta.MetaModels.Completion;
import io.justinbeaver.meta.MetaModels.Container;
import io.justinbeaver.meta.MetaModels.DbInfo;
import io.justinbeaver.meta.MetaModels.SearchHit;
import io.justinbeaver.meta.MetaModels.ForeignKeyInfo;
import io.justinbeaver.meta.MetaModels.IndexInfo;
import io.justinbeaver.meta.MetaModels.TableDetails;
import io.justinbeaver.meta.MetaModels.TableInfo;
import io.justinbeaver.session.DbSession;
import org.springframework.stereotype.Service;

/**
 * Navigator metadata through the portable {@link DatabaseMetaData} API. Per-database
 * refinements (system schemas, DDL, materialized views...) belong in the provider later.
 */
@Service
public class MetadataService {

    public List<Container> containers(DbSession session) throws SQLException {
        return session.call(conn -> {
            DatabaseMetaData meta = conn.getMetaData();
            List<Container> schemas = new ArrayList<>();
            try (ResultSet rs = meta.getSchemas()) {
                while (rs.next()) {
                    String schema = rs.getString("TABLE_SCHEM");
                    String catalog = columnOrNull(rs, "TABLE_CATALOG");
                    schemas.add(new Container(catalog, schema, schema));
                }
            }
            if (!schemas.isEmpty()) {
                return schemas;
            }
            List<Container> catalogs = new ArrayList<>();
            try (ResultSet rs = meta.getCatalogs()) {
                while (rs.next()) {
                    String catalog = rs.getString("TABLE_CAT");
                    catalogs.add(new Container(catalog, null, catalog));
                }
            }
            if (!catalogs.isEmpty()) {
                return catalogs;
            }
            return List.of(new Container(null, null, "(default)"));
        });
    }

    public List<TableInfo> tables(DbSession session, String catalog, String schema) throws SQLException {
        return session.call(conn -> {
            List<TableInfo> tables = new ArrayList<>();
            try (ResultSet rs = conn.getMetaData().getTables(blankToNull(catalog), blankToNull(schema), "%", null)) {
                while (rs.next()) {
                    if (isIndex(rs.getString("TABLE_TYPE"))) {
                        continue; // PostgreSQL lists indexes as "tables"
                    }
                    tables.add(new TableInfo(rs.getString("TABLE_NAME"), rs.getString("TABLE_TYPE"),
                            rs.getString("REMARKS")));
                }
            }
            return tables;
        });
    }

    public TableDetails details(DbSession session, String catalog, String schema, String table) throws SQLException {
        return session.call(conn -> {
            DatabaseMetaData meta = conn.getMetaData();
            String cat = blankToNull(catalog);
            String sch = blankToNull(schema);
            Set<String> primaryKeys = new HashSet<>();
            try (ResultSet rs = meta.getPrimaryKeys(cat, sch, table)) {
                while (rs.next()) {
                    primaryKeys.add(rs.getString("COLUMN_NAME"));
                }
            }
            java.util.Map<String, Integer> types = new java.util.LinkedHashMap<>();
            List<ColumnInfo> columns = columns(meta, cat, sch, table, primaryKeys, types);
            RowKeys.RowKey rowKey = RowKeys.find(meta, cat, sch, table, types);
            return new TableDetails(table, columns, foreignKeys(meta, cat, sch, table), referencedBy(meta, cat, sch, table),
                    indexes(meta, cat, sch, table), rowKey == null ? List.of() : rowKey.columns(),
                    rowKey == null ? null : rowKey.kind().name());
        });
    }

    /** True when {@code table} exists in the container; used before building SQL from path input. */
    public boolean tableExists(Connection conn, String catalog, String schema, String table) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(blankToNull(catalog), escapePattern(meta, blankToNull(schema)),
                escapePattern(meta, table), null)) {
            while (rs.next()) {
                if (table.equals(rs.getString("TABLE_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<ColumnInfo> columns(DatabaseMetaData meta, String catalog, String schema, String table,
                                     Set<String> primaryKeys, java.util.Map<String, Integer> types) throws SQLException {
        List<ColumnInfo> columns = new ArrayList<>();
        try (ResultSet rs = meta.getColumns(catalog, escapePattern(meta, schema), escapePattern(meta, table), "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                types.put(name, rs.getInt("DATA_TYPE"));
                int decimals = rs.getInt("DECIMAL_DIGITS");
                boolean noDecimals = rs.wasNull();
                columns.add(new ColumnInfo(name, rs.getString("TYPE_NAME"), rs.getInt("COLUMN_SIZE"),
                        noDecimals ? null : decimals, rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        rs.getString("COLUMN_DEF"), rs.getInt("ORDINAL_POSITION"), primaryKeys.contains(name),
                        "YES".equalsIgnoreCase(columnOrNull(rs, "IS_AUTOINCREMENT")), rs.getString("REMARKS")));
            }
        }
        columns.sort((a, b) -> Integer.compare(a.position(), b.position()));
        return columns;
    }

    private List<ForeignKeyInfo> foreignKeys(DatabaseMetaData meta, String catalog, String schema, String table)
            throws SQLException {
        List<ForeignKeyInfo> keys = new ArrayList<>();
        try (ResultSet rs = meta.getImportedKeys(catalog, schema, table)) {
            while (rs.next()) {
                keys.add(new ForeignKeyInfo(rs.getString("FK_NAME"), rs.getString("FKCOLUMN_NAME"),
                        rs.getString("PKTABLE_CAT"), rs.getString("PKTABLE_SCHEM"), rs.getString("PKTABLE_NAME"),
                        rs.getString("PKCOLUMN_NAME")));
            }
        }
        return keys;
    }

    private List<ForeignKeyInfo> referencedBy(DatabaseMetaData meta, String catalog, String schema, String table)
            throws SQLException {
        List<ForeignKeyInfo> keys = new ArrayList<>();
        try (ResultSet rs = meta.getExportedKeys(catalog, schema, table)) {
            while (rs.next()) {
                keys.add(new ForeignKeyInfo(rs.getString("FK_NAME"), rs.getString("PKCOLUMN_NAME"),
                        rs.getString("FKTABLE_CAT"), rs.getString("FKTABLE_SCHEM"), rs.getString("FKTABLE_NAME"),
                        rs.getString("FKCOLUMN_NAME")));
            }
        }
        return keys;
    }

    private static final int COMPLETION_TABLE_LIMIT = 3000;
    private static final java.util.regex.Pattern SYSTEM_CONTAINER = java.util.regex.Pattern.compile(
            "(?i)information_schema|pg_catalog|pg_toast.*|pg_temp.*|sys|mysql|performance_schema|system");

    /** Table and column names of the user's containers, for the SQL editor's autocomplete. */
    public Completion completion(DbSession session) throws SQLException {
        List<Container> containers = containers(session);
        return session.call(conn -> {
            DatabaseMetaData meta = conn.getMetaData();
            java.util.Map<String, java.util.Map<String, List<String>>> schemas = new java.util.LinkedHashMap<>();
            int tables = 0;
            boolean truncated = false;
            outer:
            for (Container container : containers) {
                if (SYSTEM_CONTAINER.matcher(container.label()).matches()) {
                    continue;
                }
                java.util.Map<String, List<String>> byTable = new java.util.LinkedHashMap<>();
                try (ResultSet rs = meta.getColumns(container.catalog(), escapePattern(meta, container.schema()), "%", "%")) {
                    while (rs.next()) {
                        String table = rs.getString("TABLE_NAME");
                        if (!byTable.containsKey(table) && ++tables > COMPLETION_TABLE_LIMIT) {
                            truncated = true;
                            break outer;
                        }
                        byTable.computeIfAbsent(table, t -> new ArrayList<>()).add(rs.getString("COLUMN_NAME"));
                    }
                }
                schemas.put(container.label(), byTable);
            }
            return new Completion(defaultContainer(conn, containers), schemas, truncated);
        });
    }

    /** Databases of the server (PostgreSQL); others list their databases as catalogs instead. */
    public record Databases(String current, List<String> databases) {
    }

    public Databases databases(DbSession session, boolean all) throws SQLException {
        return session.call(conn -> {
            String dialect = dialectOf(conn.getMetaData().getDatabaseProductName());
            if (!dialect.equals("postgresql")) {
                String catalog = conn.getCatalog();
                return new Databases(catalog, catalog == null ? List.of() : List.of(catalog));
            }
            String current;
            try (java.sql.Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT current_database()")) {
                rs.next();
                current = rs.getString(1);
            }
            if (!all) {
                return new Databases(current, List.of(current));
            }
            List<String> names = new ArrayList<>();
            try (java.sql.Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT datname FROM pg_database WHERE NOT datistemplate AND datallowconn ORDER BY datname")) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
            return new Databases(current, names);
        });
    }

    public DbInfo info(DbSession session) throws SQLException {
        return session.call(conn -> {
            DatabaseMetaData meta = conn.getMetaData();
            String product = meta.getDatabaseProductName();
            String quote = meta.getIdentifierQuoteString();
            return new DbInfo(product, meta.getDatabaseProductVersion(), dialectOf(product),
                    quote == null || quote.isBlank() ? "" : quote,
                    meta.supportsCatalogsInTableDefinitions(), meta.supportsSchemasInTableDefinitions());
        });
    }

    public static String dialectOf(String product) {
        String p = product.toLowerCase(java.util.Locale.ROOT);
        if (p.contains("postgres")) return "postgresql";
        if (p.contains("mariadb")) return "mariadb";
        if (p.contains("mysql")) return "mysql";
        if (p.contains("sqlite")) return "sqlite";
        if (p.equals("h2")) return "h2";
        if (p.contains("microsoft sql server")) return "sqlserver";
        if (p.contains("oracle")) return "oracle";
        return "generic";
    }

    private static final int SEARCH_SCAN_LIMIT = 50_000;

    /** "Open Database Object": tables and views whose name contains {@code query}, case-insensitive. */
    public List<SearchHit> search(DbSession session, String query, int limit) throws SQLException {
        String needle = query.trim().toLowerCase(java.util.Locale.ROOT);
        if (needle.isEmpty()) {
            return List.of();
        }
        List<Container> containers = containers(session);
        return session.call(conn -> {
            DatabaseMetaData meta = conn.getMetaData();
            List<SearchHit> exact = new ArrayList<>();
            List<SearchHit> prefix = new ArrayList<>();
            List<SearchHit> other = new ArrayList<>();
            int scanned = 0;
            outer:
            for (Container container : containers) {
                if (SYSTEM_CONTAINER.matcher(container.label()).matches()) {
                    continue;
                }
                try (ResultSet rs = meta.getTables(container.catalog(), escapePattern(meta, container.schema()), "%", null)) {
                    while (rs.next()) {
                        if (++scanned > SEARCH_SCAN_LIMIT) {
                            break outer;
                        }
                        if (isIndex(rs.getString("TABLE_TYPE"))) {
                            continue;
                        }
                        String name = rs.getString("TABLE_NAME");
                        String lower = name.toLowerCase(java.util.Locale.ROOT);
                        if (!lower.contains(needle)) {
                            continue;
                        }
                        SearchHit hit = new SearchHit(container.catalog(), container.schema(), container.label(), name,
                                rs.getString("TABLE_TYPE"));
                        (lower.equals(needle) ? exact : lower.startsWith(needle) ? prefix : other).add(hit);
                    }
                }
            }
            List<SearchHit> hits = new ArrayList<>(exact);
            hits.addAll(prefix);
            hits.addAll(other);
            return hits.size() > limit ? List.copyOf(hits.subList(0, limit)) : hits;
        });
    }

    private static String defaultContainer(Connection conn, List<Container> containers) {
        if (containers.size() == 1) {
            return containers.get(0).label();
        }
        try {
            String schema = conn.getSchema();
            if (schema != null) {
                return schema;
            }
        } catch (SQLException | AbstractMethodError ignored) {
            // pre-JDBC 4.1 driver
        }
        try {
            return conn.getCatalog();
        } catch (SQLException e) {
            return null;
        }
    }

    private List<IndexInfo> indexes(DatabaseMetaData meta, String catalog, String schema, String table)
            throws SQLException {
        List<IndexInfo> indexes = new ArrayList<>();
        try (ResultSet rs = meta.getIndexInfo(catalog, schema, table, false, true)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (name == null) {
                    continue; // table statistics row
                }
                indexes.add(new IndexInfo(name, rs.getString("COLUMN_NAME"), !rs.getBoolean("NON_UNIQUE"),
                        rs.getInt("ORDINAL_POSITION")));
            }
        }
        return indexes;
    }

    /** PostgreSQL (and MariaDB) list indexes and sequences among tables; sequences have their own folder. */
    private static boolean isIndex(String tableType) {
        if (tableType == null) {
            return false;
        }
        String type = tableType.toUpperCase(java.util.Locale.ROOT);
        return type.contains("INDEX") || type.contains("SEQUENCE");
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Escape LIKE wildcards in names passed to pattern-taking metadata calls. */
    public static String escapePattern(DatabaseMetaData meta, String name) throws SQLException {
        if (name == null) {
            return null;
        }
        String escape = meta.getSearchStringEscape();
        if (escape == null || escape.isEmpty()) {
            return name;
        }
        return name.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }

    private static String columnOrNull(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException e) {
            return null;
        }
    }
}
