package io.justinbeaver.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ScriptSplitterTest {

    @Test
    void splitsOnSemicolons() {
        assertThat(ScriptSplitter.split("select 1; select 2;")).containsExactly("select 1", "select 2");
    }

    @Test
    void ignoresSemicolonsInLiteralsAndQuotedIdentifiers() {
        assertThat(ScriptSplitter.split("select ';' ; select \"a;b\" from t"))
                .containsExactly("select ';'", "select \"a;b\" from t");
    }

    @Test
    void handlesEscapedQuotes() {
        assertThat(ScriptSplitter.split("select 'it''s; ok'")).containsExactly("select 'it''s; ok'");
    }

    @Test
    void ignoresSemicolonsInComments() {
        assertThat(ScriptSplitter.split("-- a;b\nselect 1; /* x; */ select 2"))
                .hasSize(2)
                .allSatisfy(s -> assertThat(s).doesNotEndWith(";"));
    }

    @Test
    void keepsDollarQuotedBodiesWhole() {
        assertThat(ScriptSplitter.split("do $$ begin x; y; end $$; select 1"))
                .containsExactly("do $$ begin x; y; end $$", "select 1");
    }

    @Test
    void dropsEmptyAndCommentOnlyStatements() {
        assertThat(ScriptSplitter.split("select 1;;  ; -- trailing")).containsExactly("select 1");
    }
}
