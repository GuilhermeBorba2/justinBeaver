package io.justinbeaver.edit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;

import org.junit.jupiter.api.Test;

class ValueConverterTest {

    @Test
    void convertsTextToColumnTypes() {
        assertThat(ValueConverter.convert("42", Types.INTEGER, "c", false)).isEqualTo(42);
        assertThat(ValueConverter.convert(42, Types.BIGINT, "c", false)).isEqualTo(42L);
        assertThat(ValueConverter.convert("1.50", Types.DECIMAL, "c", false)).isEqualTo(new BigDecimal("1.50"));
        assertThat(ValueConverter.convert("yes", Types.BOOLEAN, "c", false)).isEqualTo(true);
        assertThat(ValueConverter.convert("2020-01-02 03:04:05", Types.TIMESTAMP, "c", false))
                .isEqualTo(Timestamp.valueOf("2020-01-02 03:04:05"));
        assertThat(ValueConverter.convert(7, Types.VARCHAR, "c", false)).isEqualTo("7");
        assertThat(ValueConverter.convert(null, Types.INTEGER, "c", false)).isNull();
        assertThat(ValueConverter.convert("true", Types.TINYINT, "c", false)).isEqualTo(1);
        assertThat(ValueConverter.convert(false, Types.TINYINT, "c", false)).isEqualTo(0);
    }

    @Test
    void keepsTemporalsAsTextWhenAsked() {
        assertThat(ValueConverter.convert("2020-01-02", Types.DATE, "c", true)).isEqualTo("2020-01-02");
    }

    @Test
    void rejectsValuesThatDoNotFit() {
        assertThatThrownBy(() -> ValueConverter.convert("1.5", Types.INTEGER, "qty", false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("qty");
        assertThatThrownBy(() -> ValueConverter.convert("maybe", Types.BOOLEAN, "flag", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ValueConverter.convert("2020-13-01", Types.DATE, "d", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rendersPreviewLiterals() {
        assertThat(ValueConverter.literal("O'Brien")).isEqualTo("'O''Brien'");
        assertThat(ValueConverter.literal(new BigDecimal("1E+2"))).isEqualTo("100");
        assertThat(ValueConverter.literal(null)).isEqualTo("NULL");
        assertThat(ValueConverter.literal(true)).isEqualTo("TRUE");
    }
}
