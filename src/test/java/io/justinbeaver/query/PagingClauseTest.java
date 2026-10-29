package io.justinbeaver.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PagingClauseTest {

    @Test
    void limitOffsetDialects() {
        assertThat(DataService.pagingClause("PostgreSQL", false, 400, 201)).isEqualTo(" LIMIT 201 OFFSET 400");
        assertThat(DataService.pagingClause("H2", true, 0, 11)).isEqualTo(" LIMIT 11 OFFSET 0");
    }

    @Test
    void offsetFetchDialects() {
        assertThat(DataService.pagingClause("Microsoft SQL Server", false, 10, 5))
                .isEqualTo(" ORDER BY (SELECT NULL) OFFSET 10 ROWS FETCH NEXT 5 ROWS ONLY");
        assertThat(DataService.pagingClause("Microsoft SQL Server", true, 10, 5)).isEqualTo(" OFFSET 10 ROWS FETCH NEXT 5 ROWS ONLY");
        assertThat(DataService.pagingClause("Oracle", false, 0, 5)).isEqualTo(" OFFSET 0 ROWS FETCH NEXT 5 ROWS ONLY");
    }

    @Test
    void unknownDatabasesPageOverJdbc() {
        assertThat(DataService.pagingClause("Informix Dynamic Server", false, 0, 5)).isNull();
    }
}
