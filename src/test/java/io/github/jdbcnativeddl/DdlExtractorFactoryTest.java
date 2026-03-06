package io.github.jdbcnativeddl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DdlExtractorFactoryTest {

    @Test
    void detectsPostgres() {
        DdlExtractor extractor = Ddl.forJdbcUrl("jdbc:postgresql://localhost:5432/mydb");
        assertThat(extractor).isInstanceOf(PostgresDdlExtractor.class);
    }

    @Test
    void detectsOracle() {
        DdlExtractor extractor = Ddl.forJdbcUrl("jdbc:oracle:thin:@localhost:1521:orcl");
        assertThat(extractor).isInstanceOf(OracleDdlExtractor.class);
    }

    @Test
    void detectsMssql() {
        DdlExtractor extractor = Ddl.forJdbcUrl("jdbc:sqlserver://localhost:1433;databaseName=mydb");
        assertThat(extractor).isInstanceOf(MssqlDdlExtractor.class);
    }

    @Test
    void detectsDb2() {
        DdlExtractor extractor = Ddl.forJdbcUrl("jdbc:db2://localhost:50000/mydb");
        assertThat(extractor).isInstanceOf(Db2DdlExtractor.class);
    }

    @Test
    void throwsForUnsupportedUrl() {
        assertThatThrownBy(() -> Ddl.forJdbcUrl("jdbc:mysql://localhost/mydb"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported");
    }

    @Test
    void throwsForNull() {
        assertThatThrownBy(() -> Ddl.forJdbcUrl(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
