package io.justinbeaver.query;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a SQL script on {@code ;} while ignoring semicolons inside string literals, quoted
 * identifiers, comments and PostgreSQL dollar-quoted bodies. Statements that contain only
 * whitespace/comments are dropped. Procedural blocks with inner semicolons (Oracle PL/SQL, T-SQL
 * {@code GO} batches) need per-dialect rules and are out of scope here.
 */
public final class ScriptSplitter {

    private ScriptSplitter() {
    }

    public static List<String> split(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean hasContent = false;
        int n = script.length();
        int i = 0;
        while (i < n) {
            char c = script.charAt(i);
            if (c == '-' && next(script, i) == '-') {
                int end = indexOrEnd(script, "\n", i);
                current.append(script, i, end);
                i = end;
            } else if (c == '/' && next(script, i) == '*') {
                int close = script.indexOf("*/", i + 2);
                int end = close < 0 ? n : close + 2;
                current.append(script, i, end);
                i = end;
            } else if (c == '\'' || c == '"' || c == '`') {
                int end = endOfQuoted(script, i, c);
                current.append(script, i, end);
                hasContent = true;
                i = end;
            } else if (c == '$' && dollarTagEnd(script, i) > 0) {
                int tagEnd = dollarTagEnd(script, i);
                String tag = script.substring(i, tagEnd);
                int close = script.indexOf(tag, tagEnd);
                int end = close < 0 ? n : close + tag.length();
                current.append(script, i, end);
                hasContent = true;
                i = end;
            } else if (c == ';') {
                if (hasContent) {
                    statements.add(current.toString().trim());
                }
                current.setLength(0);
                hasContent = false;
                i++;
            } else {
                current.append(c);
                if (!Character.isWhitespace(c)) {
                    hasContent = true;
                }
                i++;
            }
        }
        if (hasContent) {
            statements.add(current.toString().trim());
        }
        return statements;
    }

    private static char next(String s, int i) {
        return i + 1 < s.length() ? s.charAt(i + 1) : '\0';
    }

    private static int indexOrEnd(String s, String needle, int from) {
        int idx = s.indexOf(needle, from);
        return idx < 0 ? s.length() : idx;
    }

    /** End index (exclusive) of a quoted run; a doubled quote char is an escaped quote. */
    private static int endOfQuoted(String s, int start, char quote) {
        int i = start + 1;
        while (i < s.length()) {
            if (s.charAt(i) == quote) {
                if (next(s, i) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return s.length();
    }

    /** If a {@code $tag$} opener starts at {@code i}, the index after it; otherwise -1. */
    private static int dollarTagEnd(String s, int i) {
        int j = i + 1;
        while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) {
            j++;
        }
        return j < s.length() && s.charAt(j) == '$' ? j + 1 : -1;
    }
}
