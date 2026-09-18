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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

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

    @Test
    void foreignKeysFollowTablesAndUniqueIndexes() {
        assertBefore("CREATE TABLE z_fk_parent", "ALTER TABLE a_fk_child ADD CONSTRAINT fk_later_parent");
        assertBefore("CREATE UNIQUE INDEX idx_parent_code", "ALTER TABLE a_fk_child ADD CONSTRAINT fk_unique_index");
        assertBefore("CREATE TABLE a_fk_child", "ALTER TABLE z_fk_parent ADD CONSTRAINT fk_cycle");
        assertBefore("CREATE TABLE a_fk_child", "ALTER TABLE a_fk_child ADD CONSTRAINT fk_self");
        assertThat(ddl).contains("ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED", "NOT VALID");
        assertThat(ddl).containsOnlyOnce("ADD CONSTRAINT fk_partition_parent");
        assertBefore("CREATE TABLE sales_2024", "ALTER TABLE sales ADD CONSTRAINT fk_partition_parent");
    }

    private void assertBefore(String prerequisite, String dependent) {
        assertThat(ddl).contains(prerequisite, dependent);
        assertThat(ddl.indexOf(prerequisite))
                .as("%s must precede %s", prerequisite, dependent)
                .isLessThan(ddl.indexOf(dependent));
    }

    @Test
    void roundtrip() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            try (Statement s = conn.createStatement()) {
                s.execute("CREATE SCHEMA ddl_roundtrip");
                s.execute("SET search_path TO ddl_roundtrip");
            }
            assertRoundtrip(conn, new PostgresDdlExtractor(), "ddl_test", "ddl_roundtrip", ddl);
            // Also verify that all FK definitions survive the roundtrip.
            try (Statement s = conn.createStatement()) {
                s.execute("SET search_path TO pg_catalog");
            }
            assertThat(foreignKeys(conn, "ddl_roundtrip"))
                    .containsExactlyElementsOf(foreignKeys(conn, "ddl_test"));
        }
    }

    private List<String> foreignKeys(Connection conn, String schema) throws SQLException {
        String sql = """
                SELECT t.relname || ':' || c.conname || ':' || pg_get_constraintdef(c.oid) AS definition
                FROM pg_constraint c
                JOIN pg_class t ON c.conrelid = t.oid
                JOIN pg_namespace n ON t.relnamespace = n.oid
                WHERE n.nspname = ? AND c.contype = 'f'
                ORDER BY definition
                """;
        List<String> objects = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    objects.add(rs.getString(1).replace(schema + ".", ""));
                }
            }
        }
        return objects;
    }
}
