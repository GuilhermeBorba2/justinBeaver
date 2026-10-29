package io.justinbeaver.provider;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders DBeaver-style sample URLs: {@code jdbc:postgresql://{host}[:{port}]/[{database}]}.
 * A {@code [...]} group is kept only when every placeholder inside it has a value; a placeholder
 * outside any group is required.
 */
public final class UrlTemplate {

    private static final Pattern OPTIONAL = Pattern.compile("\\[([^\\[\\]]*)]");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    private UrlTemplate() {
    }

    public static String render(String template, Map<String, String> values) {
        Matcher optional = OPTIONAL.matcher(template);
        String withoutOptionals = optional.replaceAll(m -> {
            String group = m.group(1);
            return hasAllValues(group, values) ? Matcher.quoteReplacement(fill(group, values)) : "";
        });
        return fill(withoutOptionals, values);
    }

    private static boolean hasAllValues(String fragment, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(fragment);
        while (m.find()) {
            String value = values.get(m.group(1));
            if (value == null || value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String fill(String fragment, Map<String, String> values) {
        return PLACEHOLDER.matcher(fragment).replaceAll(m -> {
            String value = values.get(m.group(1));
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing value for '%s' in URL template".formatted(m.group(1)));
            }
            return Matcher.quoteReplacement(value);
        });
    }
}
