package io.justinbeaver.meta;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DdlTypeTest {

    @Test
    void postgresArrayTypesBecomeBrackets() {
        assertThat(DdlService.typeOf("_text", 0, 0)).isEqualTo("text[]");
        assertThat(DdlService.typeOf("_int4", 0, 0)).isEqualTo("int4[]");
    }

    @Test
    void serialDoesNotRepeatItsSequenceDefault() {
        assertThat(DdlService.impliedBySerial("serial", "nextval('t_id_seq'::regclass)")).isTrue();
        assertThat(DdlService.impliedBySerial("bigserial", "nextval('x'::regclass)")).isTrue();
        assertThat(DdlService.impliedBySerial("int4", "nextval('x'::regclass)")).isFalse();
        assertThat(DdlService.impliedBySerial("serial", "5")).isFalse();
    }
}
