package io.justinbeaver.edit;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.justinbeaver.edit.EditModels.EditRequest;
import io.justinbeaver.edit.EditModels.EditResult;
import io.justinbeaver.edit.EditModels.Op;
import io.justinbeaver.edit.EditModels.PreviewResult;
import io.justinbeaver.edit.EditModels.RowChange;
import io.justinbeaver.meta.MetadataService;
import io.justinbeaver.meta.RowKeys;
import io.justinbeaver.query.DataService;
import io.justinbeaver.session.DbSession;
import org.springframework.stereotype.Service;

/**
 * Turns grid edits into INSERT/UPDATE/DELETE statements keyed by the primary key (DBeaver's
 * "Save" in the data editor). Table and column names are checked against the database metadata
 * and quoted; values are always bound as parameters.
 * <p>
 * A save is all-or-nothing: in auto-commit mode it runs in its own transaction; inside a manual
 * transaction it uses a savepoint so a failure undoes only this save. Every UPDATE/DELETE must
 * touch exactly one row, otherwise the save is undone.
 */
@Service
public class EditService {

    private final MetadataService metadata;

    public EditService(MetadataService metadata) {
        this.metadata = metadata;
    }

    record Planned(Op op, String sql, List<Object> params, List<Integer> types, String display) {
    }

    /** @param key how rows are addressed; null when nothing can identify a row */
    private record TableShape(Map<String, Integer> columnTypes, RowKeys.RowKey key) {
    }

    public PreviewResult preview(DbSession session, EditRequest request) throws SQLException {
        return session.call(conn -> new PreviewResult(plan(conn, request).stream().map(Planned::display).toList()));
    }

    public EditResult apply(DbSession session, EditRequest request) throws SQLException {
        io.justinbeaver.session.ReadOnlyGuard.checkWrite(session);
        return session.call(conn -> {
            List<Planned> plan = plan(conn, request);
            boolean autoCommit = conn.getAutoCommit();
            Savepoint savepoint = null;
            if (autoCommit) {
                conn.setAutoCommit(false);
            } else {
                savepoint = trySavepoint(conn);
            }
            try {
                long affected = 0;
                for (Planned step : plan) {
                    affected += execute(conn, step);
                }
                if (autoCommit) {
                    conn.commit();
                }
                return new EditResult(plan.size(), affected, autoCommit);
            } catch (SQLException | RuntimeException e) {
                undo(conn, autoCommit, savepoint, e);
                throw e;
            } finally {
                if (autoCommit) {
                    conn.setAutoCommit(true);
                }
            }
        });
    }

    private static final int CELL_LIMIT_BYTES = 16 * 1024 * 1024;

    /**
     * Value viewer: the complete content of one cell, addressed by primary key (grid values are
     * truncated previews). Binary data is capped at 16 MB.
     */
    public EditModels.CellContent readCell(DbSession session, EditModels.CellRequest request) throws SQLException {
        return session.call(conn -> {
            String table = request.table();
            if (!metadata.tableExists(conn, request.catalog(), request.schema(), table)) {
                throw new IllegalArgumentException("Table '%s' not found".formatted(table));
            }
            DatabaseMetaData meta = conn.getMetaData();
            TableShape shape = shape(meta, request.catalog(), request.schema(), table);
            if (shape.key() == null) {
                throw new IllegalArgumentException("Rows of '%s' cannot be addressed (no key and no comparable column)".formatted(table));
            }
            Integer columnType = shape.columnTypes().get(request.column());
            if (columnType == null) {
                throw new IllegalArgumentException("Unknown column '%s'".formatted(request.column()));
            }
            boolean temporalAsText = "SQLite".equalsIgnoreCase(meta.getDatabaseProductName());
            String quote = meta.getIdentifierQuoteString();
            Map<String, Object> key = requireKey(new RowChange(Op.DELETE, request.key(), null), shape);
            Builder b = new Builder(shape, quote, temporalAsText);
            String where = b.where(key);
            String sql = "SELECT %s FROM %s WHERE %s".formatted(DataService.quoteIdentifier(quote, request.column()),
                    DataService.qualified(meta, quote, request.catalog(), request.schema(), table), where);
            Planned planned = b.build(Op.UPDATE, sql);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < planned.params().size(); i++) {
                    ps.setObject(i + 1, planned.params().get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new EditConflictException("Row not found (changed or deleted by someone else?)");
                    }
                    return content(rs, columnType);
                }
            }
        });
    }

    /**
     * Decides text vs binary from the value the driver actually returns: declared column types are
     * unreliable (SQLite reports its own affinities).
     */
    private static EditModels.CellContent content(ResultSet rs, int sqlType) throws SQLException {
        Object value = switch (sqlType) {
            case Types.BLOB -> rs.getBlob(1);
            case Types.CLOB, Types.NCLOB -> rs.getClob(1);
            default -> rs.getObject(1);
        };
        if (value == null) {
            return new EditModels.CellContent(null, null, 0, null, false);
        }
        if (value instanceof java.sql.Blob blob) {
            long size = blob.length();
            return binary(blob.getBytes(1, (int) Math.min(size, CELL_LIMIT_BYTES)), size);
        }
        if (value instanceof byte[] bytes) {
            return binary(bytes.length > CELL_LIMIT_BYTES ? java.util.Arrays.copyOf(bytes, CELL_LIMIT_BYTES) : bytes, bytes.length);
        }
        String text;
        long size;
        int limit = CELL_LIMIT_BYTES / 2; // chars
        if (value instanceof java.sql.Clob clob) {
            size = clob.length();
            text = clob.getSubString(1, (int) Math.min(size, limit));
        } else {
            text = value instanceof String s ? s : String.valueOf(value);
            size = text.length();
            if (text.length() > limit) {
                text = text.substring(0, limit);
            }
        }
        return new EditModels.CellContent(text, null, size, null, size > text.length());
    }

    private static EditModels.CellContent binary(byte[] bytes, long size) {
        return new EditModels.CellContent(null, java.util.Base64.getEncoder().encodeToString(bytes), size, mimeOf(bytes),
                size > bytes.length);
    }

    /** Magic numbers of the formats the viewer can show. */
    static String mimeOf(byte[] b) {
        if (startsWith(b, 0x89, 'P', 'N', 'G')) return "image/png";
        if (startsWith(b, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
        if (startsWith(b, 'G', 'I', 'F', '8')) return "image/gif";
        if (startsWith(b, 'R', 'I', 'F', 'F') && b.length > 11 && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        if (startsWith(b, '%', 'P', 'D', 'F')) return "application/pdf";
        if (startsWith(b, 'P', 'K', 3, 4)) return "application/zip";
        return null;
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int execute(Connection conn, Planned step) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(step.sql())) {
            for (int i = 0; i < step.params().size(); i++) {
                Object value = step.params().get(i);
                int type = step.types().get(i);
                if (value == null) {
                    ps.setNull(i + 1, type);
                } else if (type == Types.OTHER && value instanceof String) {
                    ps.setObject(i + 1, value, Types.OTHER); // json, uuid, enums...: let the server cast
                } else {
                    ps.setObject(i + 1, value);
                }
            }
            int count = ps.executeUpdate();
            if (step.op() != Op.INSERT && count != 1) {
                throw new EditConflictException("%s matched %d rows instead of 1 (row changed or deleted by someone else?): %s"
                        .formatted(step.op(), count, step.display()));
            }
            return count;
        }
    }

    private static Savepoint trySavepoint(Connection conn) {
        try {
            return conn.setSavepoint();
        } catch (SQLException e) {
            return null; // driver without savepoints: a failure leaves earlier statements pending
        }
    }

    private static void undo(Connection conn, boolean autoCommit, Savepoint savepoint, Exception cause) {
        try {
            if (autoCommit) {
                conn.rollback();
            } else if (savepoint != null) {
                conn.rollback(savepoint);
            }
        } catch (SQLException rollbackError) {
            cause.addSuppressed(rollbackError);
        }
    }

    List<Planned> plan(Connection conn, EditRequest request) throws SQLException {
        String table = request.table();
        if (!metadata.tableExists(conn, request.catalog(), request.schema(), table)) {
            throw new IllegalArgumentException("Table '%s' not found".formatted(table));
        }
        DatabaseMetaData meta = conn.getMetaData();
        TableShape shape = shape(meta, request.catalog(), request.schema(), table);
        boolean addressesRows = request.changes().stream().anyMatch(c -> c.op() != Op.INSERT);
        if (addressesRows && shape.key() == null) {
            throw new IllegalArgumentException(
                    "Rows of '%s' cannot be addressed: no primary key, no unique index and no comparable column".formatted(table));
        }
        boolean temporalAsText = "SQLite".equalsIgnoreCase(meta.getDatabaseProductName());
        String quote = meta.getIdentifierQuoteString();
        String qualified = DataService.qualified(meta, quote, request.catalog(), request.schema(), table);

        List<Planned> plan = new ArrayList<>();
        for (RowChange change : request.changes()) {
            plan.add(switch (change.op()) {
                case INSERT -> insert(change, shape, qualified, quote, temporalAsText);
                case UPDATE -> update(change, shape, qualified, quote, temporalAsText);
                case DELETE -> delete(change, shape, qualified, quote, temporalAsText);
            });
        }
        return plan;
    }

    private Planned insert(RowChange change, TableShape shape, String qualified, String quote, boolean temporalAsText) {
        Map<String, Object> values = requireValues(change, shape);
        Builder b = new Builder(shape, quote, temporalAsText);
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            names.add(DataService.quoteIdentifier(quote, e.getKey()));
            b.param(e.getKey(), e.getValue());
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(names.size(), "?"));
        return b.build(Op.INSERT, "INSERT INTO %s (%s) VALUES (%s)".formatted(qualified, String.join(", ", names), placeholders));
    }

    private Planned update(RowChange change, TableShape shape, String qualified, String quote, boolean temporalAsText) {
        Map<String, Object> values = requireValues(change, shape);
        Map<String, Object> key = requireKey(change, shape);
        Builder b = new Builder(shape, quote, temporalAsText);
        List<String> sets = new ArrayList<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            sets.add(DataService.quoteIdentifier(quote, e.getKey()) + " = ?");
            b.param(e.getKey(), e.getValue());
        }
        String where = b.where(key);
        return b.build(Op.UPDATE, "UPDATE %s SET %s WHERE %s".formatted(qualified, String.join(", ", sets), where));
    }

    private Planned delete(RowChange change, TableShape shape, String qualified, String quote, boolean temporalAsText) {
        Map<String, Object> key = requireKey(change, shape);
        Builder b = new Builder(shape, quote, temporalAsText);
        String where = b.where(key);
        return b.build(Op.DELETE, "DELETE FROM %s WHERE %s".formatted(qualified, where));
    }

    private static Map<String, Object> requireValues(RowChange change, TableShape shape) {
        if (change.values() == null || change.values().isEmpty()) {
            throw new IllegalArgumentException("%s needs at least one column value".formatted(change.op()));
        }
        for (String column : change.values().keySet()) {
            if (!shape.columnTypes().containsKey(column)) {
                throw new IllegalArgumentException("Unknown column '%s'".formatted(column));
            }
        }
        return new LinkedHashMap<>(change.values());
    }

    /**
     * The key must name exactly the row-key columns. Primary-key values cannot be NULL; with a unique
     * index or the all-columns key they can (matched with IS NULL).
     */
    private static Map<String, Object> requireKey(RowChange change, TableShape shape) {
        Map<String, Object> key = change.key() == null ? Map.of() : change.key();
        RowKeys.RowKey rowKey = shape.key();
        Set<String> expected = new TreeSet<>(rowKey.columns());
        if (!expected.equals(new TreeSet<>(key.keySet()))) {
            throw new IllegalArgumentException("%s key must be exactly the row key %s (%s)".formatted(change.op(), expected,
                    rowKey.kind().name().toLowerCase().replace('_', ' ')));
        }
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (String column : rowKey.columns()) {
            Object value = key.get(column);
            if (value == null && !rowKey.allowsNulls()) {
                throw new IllegalArgumentException("Primary key column '%s' cannot be null".formatted(column));
            }
            ordered.put(column, value);
        }
        return ordered;
    }

    private static TableShape shape(DatabaseMetaData meta, String catalog, String schema, String table) throws SQLException {
        String cat = catalog == null || catalog.isBlank() ? null : catalog;
        String sch = schema == null || schema.isBlank() ? null : schema;
        Map<String, Integer> types = new LinkedHashMap<>();
        try (ResultSet rs = meta.getColumns(cat, MetadataService.escapePattern(meta, sch), MetadataService.escapePattern(meta, table), "%")) {
            while (rs.next()) {
                if (table.equals(rs.getString("TABLE_NAME"))) {
                    types.put(rs.getString("COLUMN_NAME"), rs.getInt("DATA_TYPE"));
                }
            }
        }
        return new TableShape(types, RowKeys.find(meta, cat, sch, table, types));
    }

    /** Accumulates converted parameters and the matching preview text. */
    private static final class Builder {
        private final TableShape shape;
        private final String quote;
        private final boolean temporalAsText;
        private final List<Object> params = new ArrayList<>();
        private final List<Integer> types = new ArrayList<>();

        Builder(TableShape shape, String quote, boolean temporalAsText) {
            this.shape = shape;
            this.quote = quote;
            this.temporalAsText = temporalAsText;
        }

        void param(String column, Object raw) {
            int type = shape.columnTypes().get(column);
            params.add(ValueConverter.convert(raw, type, column, temporalAsText));
            types.add(type);
        }

        String where(Map<String, Object> key) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Object> e : key.entrySet()) {
                if (e.getValue() == null) {
                    parts.add(DataService.quoteIdentifier(quote, e.getKey()) + " IS NULL");
                } else {
                    parts.add(DataService.quoteIdentifier(quote, e.getKey()) + " = ?");
                    param(e.getKey(), e.getValue());
                }
            }
            return String.join(" AND ", parts);
        }

        Planned build(Op op, String sql) {
            StringBuilder display = new StringBuilder();
            int next = 0;
            for (int i = 0; i < sql.length(); i++) {
                char c = sql.charAt(i);
                // placeholders only appear outside quoted identifiers, but identifiers may contain '?'
                if (c == '?' && !insideQuotes(sql, i)) {
                    display.append(ValueConverter.literal(params.get(next++)));
                } else {
                    display.append(c);
                }
            }
            return new Planned(op, sql, nullSafe(params), List.copyOf(types), display.toString());
        }

        private boolean insideQuotes(String sql, int index) {
            if (quote == null || quote.isBlank()) {
                return false;
            }
            int count = 0;
            for (int i = sql.indexOf(quote); i >= 0 && i < index; i = sql.indexOf(quote, i + quote.length())) {
                count++;
            }
            return count % 2 == 1;
        }

        private static List<Object> nullSafe(List<Object> values) {
            // List.copyOf rejects nulls; wrap to keep positional nulls for setNull
            return java.util.Collections.unmodifiableList(new ArrayList<>(values));
        }
    }
}
