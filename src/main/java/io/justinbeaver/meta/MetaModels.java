package io.justinbeaver.meta;

import java.util.List;

/** Wire shapes for the database navigator. */
public final class MetaModels {

    private MetaModels() {
    }

    /**
     * A node that holds tables: a schema, or a catalog on databases without schemas (MySQL), or the
     * single implicit one (SQLite). Null parts are not used by the database.
     */
    public record Container(String catalog, String schema, String label) {
    }

    public record TableInfo(String name, String type, String remarks) {
    }

    public record ColumnInfo(String name, String typeName, int size, Integer decimalDigits, boolean nullable,
                             String defaultValue, int position, boolean primaryKey, boolean autoIncrement,
                             String remarks) {
    }

    public record ForeignKeyInfo(String name, String column, String refCatalog, String refSchema, String refTable,
                                 String refColumn) {
    }

    public record IndexInfo(String name, String column, boolean unique, int position) {
    }

    /**
     * @param foreignKeys  keys of this table pointing elsewhere ({@code column} is local, {@code ref*} the target)
     * @param referencedBy keys of other tables pointing here ({@code column} is local, {@code ref*} the referencing side)
     */
    /**
     * @param rowKey     columns that address a row for editing (see {@link RowKeys}); empty when none can
     * @param rowKeyKind PRIMARY_KEY, UNIQUE_INDEX or ALL_COLUMNS; null when rows cannot be addressed
     */
    public record TableDetails(String name, List<ColumnInfo> columns, List<ForeignKeyInfo> foreignKeys,
                               List<ForeignKeyInfo> referencedBy, List<IndexInfo> indexes, List<String> rowKey, String rowKeyKind) {
    }

    /**
     * What the UI needs to generate DDL for this database.
     *
     * @param dialect one of postgresql, mysql, mariadb, sqlite, h2, sqlserver, oracle, generic
     */
    public record DbInfo(String product, String version, String dialect, String identifierQuote,
                         boolean supportsCatalogs, boolean supportsSchemas) {
    }

    /** One hit of "Open Database Object". */
    public record SearchHit(String catalog, String schema, String container, String name, String type) {
    }

    /**
     * Names for SQL autocompletion: container label -> table -> columns.
     *
     * @param defaultSchema container whose tables complete without a qualifier
     * @param truncated     more tables existed than were returned
     */
    public record Completion(String defaultSchema, java.util.Map<String, java.util.Map<String, List<String>>> schemas,
                             boolean truncated) {
    }
}
