package io.github.jdbcnativeddl;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PostgresDdlExtractorTest extends AbstractDdlExtractorTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withInitScript("postgres-init.sql")
            .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("tc.postgres")));

    private static String ddl;

    @BeforeAll
    static void extractDdl() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            DdlExtractor extractor = new PostgresDdlExtractor();
            ddl = extractor.extractDdl(conn, "ddl_test");
            System.out.println("=== PostgreSQL DDL ===");
            System.out.println(ddl);
        }
    }

    @Test
    void commonDdl() {
        assertCommonDdl(ddl);
    }

    @Test
    void expressionIndex() {
        assertThat(ddl.toUpperCase()).contains("UPPER");
        assertThat(ddl).containsIgnoringCase("idx_emp_upper");
    }

    @Test
    void partialIndex() {
        assertThat(ddl).containsIgnoringCase("idx_active");
        assertThat(ddl).containsIgnoringCase("WHERE");
    }

    @Test
    void partitionedTable() {
        assertThat(ddl.toUpperCase()).contains("PARTITION BY RANGE");
        assertThat(ddl).containsIgnoringCase("sales_2023");
        assertThat(ddl).containsIgnoringCase("sales_2024");
    }

    @Test
    void enumType() {
        assertThat(ddl).containsIgnoringCase("CREATE TYPE");
        assertThat(ddl).containsIgnoringCase("mood");
        assertThat(ddl).containsIgnoringCase("ENUM");
    }

    @Test
    void domainType() {
        assertThat(ddl).containsIgnoringCase("CREATE DOMAIN");
        assertThat(ddl).containsIgnoringCase("email_address");
    }

    @Test
    void generatedColumn() {
        assertThat(ddl).containsIgnoringCase("full_name");
        assertThat(ddl.toUpperCase()).contains("GENERATED ALWAYS AS");
    }

    @Test
    void materializedView() {
        assertThat(ddl.toUpperCase()).contains("CREATE MATERIALIZED VIEW");
        assertThat(ddl).containsIgnoringCase("dept_summary");
    }

    @Test
    void view() {
        assertThat(ddl.toUpperCase()).contains("CREATE VIEW");
        assertThat(ddl).containsIgnoringCase("active_employees");
    }

    @Test
    void uniqueConstraint() {
        assertThat(ddl).containsIgnoringCase("uq_emp_email");
        assertThat(ddl.toUpperCase()).contains("UNIQUE");
    }

    @Test
    void foreignKeyConstraint() {
        assertThat(ddl).containsIgnoringCase("fk_dept");
        assertThat(ddl.toUpperCase()).contains("FOREIGN KEY");
    }
}
