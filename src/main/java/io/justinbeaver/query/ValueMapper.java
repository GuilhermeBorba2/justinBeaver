package io.justinbeaver.query;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Arrays;
import java.util.HexFormat;

import io.justinbeaver.query.QueryModels.Column;

/**
 * Turns JDBC values into JSON-safe values: huge numbers become strings (JavaScript doubles lose
 * precision above 2^53), dates become ISO strings, LOBs are summarized or truncated.
 */
final class ValueMapper {

    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final int MAX_TEXT_CHARS = 10_000;
    private static final int MAX_BINARY_PREVIEW_BYTES = 32;

    private ValueMapper() {
    }

    static Column column(java.sql.ResultSetMetaData meta, int index) throws SQLException {
        return new Column(meta.getColumnLabel(index), meta.getColumnTypeName(index), kind(meta.getColumnType(index)));
    }

    static String kind(int sqlType) {
        return switch (sqlType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.FLOAT, Types.REAL, Types.DOUBLE,
                 Types.NUMERIC, Types.DECIMAL -> "number";
            case Types.BOOLEAN, Types.BIT -> "boolean";
            case Types.DATE, Types.TIME, Types.TIMESTAMP, Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE -> "temporal";
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> "binary";
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                 Types.CLOB, Types.NCLOB, Types.SQLXML -> "text";
            default -> "other";
        };
    }

    static Object read(ResultSet rs, int index) {
        try {
            return toJson(rs.getObject(index));
        } catch (SQLException | RuntimeException e) {
            return "<unreadable: " + e.getMessage() + ">";
        }
    }

    static Object toJson(Object value) throws SQLException {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return value;
        }
        if (value instanceof Long l) {
            return Math.abs(l) > MAX_SAFE_INTEGER ? l.toString() : l;
        }
        if (value instanceof Double d) {
            return d.isNaN() || d.isInfinite() ? d.toString() : d;
        }
        if (value instanceof Float f) {
            return f.isNaN() || f.isInfinite() ? f.toString() : (Object) f.doubleValue();
        }
        if (value instanceof BigDecimal || value instanceof BigInteger) {
            return value.toString();
        }
        if (value instanceof Timestamp t) {
            return dateTime(t.toLocalDateTime());
        }
        if (value instanceof java.time.LocalDateTime t) {
            return dateTime(t);
        }
        if (value instanceof java.time.OffsetDateTime t) {
            return dateTime(t.toLocalDateTime()) + t.getOffset();
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (value instanceof Time t) {
            return t.toLocalTime().toString();
        }
        if (value instanceof byte[] bytes) {
            return binary(bytes);
        }
        if (value instanceof Blob blob) {
            long length = blob.length();
            int preview = (int) Math.min(length, MAX_BINARY_PREVIEW_BYTES);
            return binary(blob.getBytes(1, preview), length);
        }
        if (value instanceof Clob clob) {
            long length = clob.length();
            String text = clob.getSubString(1, (int) Math.min(length, MAX_TEXT_CHARS));
            return length > MAX_TEXT_CHARS ? text + "… (" + length + " chars)" : text;
        }
        if (value instanceof SQLXML xml) {
            return xml.getString();
        }
        if (value instanceof Array array) {
            Object content = array.getArray();
            return content instanceof Object[] objects ? Arrays.deepToString(objects) : String.valueOf(content);
        }
        if (value instanceof Number) {
            return value.toString();
        }
        return value.toString();
    }

    private static final java.time.format.DateTimeFormatter DATE_TIME =
            java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    /** Always with seconds, fraction only when present and without trailing zeros: 2024-03-06 12:11:01.314 */
    static String dateTime(java.time.LocalDateTime t) {
        String text = DATE_TIME.format(t);
        if (t.getNano() == 0) {
            return text;
        }
        String fraction = String.format("%09d", t.getNano()).replaceFirst("0+$", "");
        return text + "." + fraction;
    }

    private static String binary(byte[] bytes) {
        return binary(bytes.length > MAX_BINARY_PREVIEW_BYTES
                ? Arrays.copyOf(bytes, MAX_BINARY_PREVIEW_BYTES) : bytes, bytes.length);
    }

    private static String binary(byte[] preview, long totalLength) {
        String hex = "0x" + HexFormat.of().formatHex(preview);
        return totalLength > preview.length ? hex + "… (" + totalLength + " bytes)" : hex;
    }
}
