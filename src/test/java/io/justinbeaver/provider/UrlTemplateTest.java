package io.justinbeaver.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class UrlTemplateTest {

    private static final String PG = "jdbc:postgresql://{host}[:{port}]/[{database}]";

    @Test
    void keepsOptionalGroupsWhenValuesPresent() {
        assertThat(UrlTemplate.render(PG, Map.of("host", "db", "port", "5432", "database", "app")))
                .isEqualTo("jdbc:postgresql://db:5432/app");
    }

    @Test
    void dropsOptionalGroupsWhenValuesMissing() {
        Map<String, String> values = new HashMap<>();
        values.put("host", "db");
        values.put("port", null);
        values.put("database", " ");
        assertThat(UrlTemplate.render(PG, values)).isEqualTo("jdbc:postgresql://db/");
    }

    @Test
    void requiredPlaceholderMustHaveValue() {
        assertThatThrownBy(() -> UrlTemplate.render(PG, Map.of("port", "5432")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
    }

    @Test
    void valuesAreInsertedLiterally() {
        assertThat(UrlTemplate.render("jdbc:x:{file}", Map.of("file", "C:\\data\\$1.db")))
                .isEqualTo("jdbc:x:C:\\data\\$1.db");
    }
}
