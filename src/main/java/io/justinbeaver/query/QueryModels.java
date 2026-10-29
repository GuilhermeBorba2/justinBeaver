package io.justinbeaver.query;

import java.util.List;

/** Wire shapes for query execution. */
public final class QueryModels {

    private QueryModels() {
    }

    /**
     * @param kind one of number, text, temporal, boolean, binary, other: lets the grid align and format
     */
    public record Column(String name, String typeName, String kind) {
    }

    /**
     * @param columns     empty when the statement produced no result set
     * @param updateCount rows affected by DML, null for queries
     * @param truncated   more rows existed than {@code maxRows}
     * @param messages    server output: warnings and notices (PostgreSQL RAISE NOTICE, SQL Server PRINT,
     *                    MySQL warnings) and Oracle DBMS_OUTPUT lines
     * @param source         set when the rows come from one table and the result holds a row key, so the
     *                       grid can edit them; null otherwise
     * @param readOnlyReason why a result set cannot be edited (shown in the grid), null when editable or not a query
     */
    public record QueryResult(List<Column> columns, List<List<Object>> rows, boolean truncated, Long updateCount,
                              long elapsedMs, List<String> messages, EditableSource source, String readOnlyReason) {

        public QueryResult {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }

        public QueryResult(List<Column> columns, List<List<Object>> rows, boolean truncated, Long updateCount, long elapsedMs) {
            this(columns, rows, truncated, updateCount, elapsedMs, List.of(), null, null);
        }

        public QueryResult withMessages(List<String> output) {
            return output.isEmpty() ? this
                    : new QueryResult(columns, rows, truncated, updateCount, elapsedMs, output, source, readOnlyReason);
        }

        public QueryResult withSource(EditableSource editable, String reason) {
            return new QueryResult(columns, rows, truncated, updateCount, elapsedMs, messages, editable, editable == null ? reason : null);
        }
    }

    /**
     * The table behind an editable result.
     *
     * @param columns    for each result column, the table column it shows, or null (expression, alias of
     *                   something else): such columns stay read-only
     * @param primaryKey the row key columns (primary key, unique index or every comparable column), all in {@code columns}
     * @param keyKind    PRIMARY_KEY, UNIQUE_INDEX or ALL_COLUMNS
     */
    public record EditableSource(String catalog, String schema, String table, java.util.List<String> columns,
                                 java.util.List<String> primaryKey, String keyKind) {
    }

    /** One statement of a script: exactly one of {@code result} / {@code error} is set. */
    public record StatementResult(String sql, QueryResult result, String error) {
    }

    public record DataPage(List<Column> columns, List<List<Object>> rows, int offset, boolean hasMore,
                           long elapsedMs) {
    }
}
