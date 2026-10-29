package io.justinbeaver.edit;

import java.math.BigDecimal;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Converts JSON values (string, number, boolean) typed in the grid into the Java type JDBC expects
 * for the column. Strict drivers (PostgreSQL) reject a varchar bound to an integer or date column,
 * so values are converted here instead of relying on driver coercion.
 */
final class ValueConverter {

    private ValueConverter() {
    }

    /**
     * @param temporalAsText keep date/time values as text (SQLite stores bound java.sql dates as epoch numbers,
     *                       which would silently change how existing text dates are stored)
     */
    static Object convert(Object value, int sqlType, String column, boolean temporalAsText) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        try {
            return switch (sqlType) {
                // MySQL/MariaDB BOOLEAN is TINYINT(1) and the driver reads it back as true/false
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> value instanceof Boolean b ? (b ? 1 : 0)
                        : isBooleanWord(text) ? (parseBoolean(text) ? 1 : 0) : new BigDecimal(text).intValueExact();
                case Types.BIGINT -> new BigDecimal(text).longValueExact();
                case Types.DECIMAL, Types.NUMERIC -> new BigDecimal(text);
                case Types.REAL, Types.FLOAT, Types.DOUBLE -> Double.valueOf(text);
                case Types.BOOLEAN, Types.BIT -> value instanceof Boolean b ? b : parseBoolean(text);
                case Types.DATE -> temporalAsText ? text : java.sql.Date.valueOf(LocalDate.parse(text));
                case Types.TIME -> temporalAsText ? text : Time.valueOf(LocalTime.parse(text));
                case Types.TIMESTAMP -> temporalAsText ? text : Timestamp.valueOf(LocalDateTime.parse(text.replace(' ', 'T')));
                case Types.TIMESTAMP_WITH_TIMEZONE -> temporalAsText ? text : OffsetDateTime.parse(text.replace(' ', 'T'));
                default -> value instanceof String s ? s : text;
            };
        } catch (NumberFormatException | ArithmeticException | DateTimeParseException e) {
            throw new IllegalArgumentException("Value '%s' is not valid for column '%s' (%s)"
                    .formatted(text, column, typeName(sqlType)), e);
        }
    }

    private static boolean isBooleanWord(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.equals("true") || lower.equals("false");
    }

    private static Boolean parseBoolean(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "true", "t", "1", "yes", "y" -> true;
            case "false", "f", "0", "no", "n" -> false;
            default -> throw new NumberFormatException("not a boolean");
        };
    }

    /** SQL literal used only for the preview text. */
    static String literal(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number) {
            return value instanceof BigDecimal d ? d.toPlainString() : value.toString();
        }
        if (value instanceof Boolean b) {
            return b ? "TRUE" : "FALSE";
        }
        return "'" + value.toString().replace("'", "''") + "'";
    }

    private static String typeName(int sqlType) {
        try {
            return java.sql.JDBCType.valueOf(sqlType).getName();
        } catch (IllegalArgumentException e) {
            return "type " + sqlType;
        }
    }
}
