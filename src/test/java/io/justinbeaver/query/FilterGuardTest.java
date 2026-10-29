package io.justinbeaver.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FilterGuardTest {

    @Test
    void semicolonInsideAStringIsNotASeparator() {
        assertThat(DataService.hasSeparator("name = 'a;b'")).isFalse();
        assertThat(DataService.hasSeparator("note = \"x;y\" -- ; comment")).isFalse();
    }

    @Test
    void severalStatementsAreRejected() {
        assertThat(DataService.hasSeparator("id = 1; DROP TABLE t")).isTrue();
        assertThat(DataService.hasSeparator(null)).isFalse();
        assertThat(DataService.hasSeparator("id = 1")).isFalse();
    }
}
