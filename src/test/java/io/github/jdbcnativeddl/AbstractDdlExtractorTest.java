package io.github.jdbcnativeddl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class AbstractDdlExtractorTest {

    protected void assertCommonDdl(String ddl) {
        String upper = ddl.toUpperCase();

        // Tables exist
        assertThat(upper).contains("EMPLOYEES");
        assertThat(upper).contains("DEPARTMENTS");

        // Column types
        assertThat(upper).contains("FIRST_NAME");
        assertThat(upper).contains("LAST_NAME");
        assertThat(upper).contains("EMAIL");
        assertThat(upper).contains("SALARY");
        assertThat(upper).contains("DEPARTMENT_ID");

        // Primary key
        assertThat(upper).contains("PRIMARY KEY");

        // Foreign key
        assertThat(upper).containsAnyOf("FOREIGN KEY", "REFERENCES");

        // Check constraint
        assertThat(upper).contains("CHECK");
        assertThat(upper).contains("SALARY");

        // Index
        assertThat(upper).contains("IDX_EMP_NAME");

        // Sequence
        assertThat(upper).contains("EMP_SEQ");

        // View
        assertThat(upper).contains("ACTIVE_EMPLOYEES");
    }

    /**
     * Executes DDL in a fresh schema and compares the resulting structure with the original.
     * This "dogfooding" test verifies that extracted DDL is valid and re-executable SQL.
     */
    protected void assertRoundtrip(Connection connection, DdlExtractor extractor,
                                   String originalSchema, String roundtripSchema,
                                   String ddl) throws SQLException {
        String adjustedDdl = ddl.replace(originalSchema, roundtripSchema);

        // Execute each statement in the roundtrip schema (skip CREATE SCHEMA — already created by test)
        for (String stmt : splitStatements(adjustedDdl)) {
            if (stmt.toUpperCase().startsWith("CREATE SCHEMA")) continue;
            try (Statement s = connection.createStatement()) {
                s.execute(stmt);
            } catch (SQLException e) {
                throw new AssertionError(
                        "Roundtrip DDL execution failed on statement:\n" + stmt, e);
            }
        }

        // Re-extract from the roundtrip schema
        String reExtracted = extractor.extractDdl(connection, roundtripSchema);
        System.out.println("=== Roundtrip DDL ===");
        System.out.println(reExtracted);

        // Compare structure: table names, view names, column count
        SchemaObjects original = querySchemaObjects(connection, originalSchema);
        SchemaObjects roundtrip = querySchemaObjects(connection, roundtripSchema);

        assertThat(roundtrip.tables)
                .as("Tables in roundtrip schema")
                .containsExactlyInAnyOrderElementsOf(original.tables);
        assertThat(roundtrip.views)
                .as("Views in roundtrip schema")
                .containsExactlyInAnyOrderElementsOf(original.views);
        assertThat(roundtrip.columnCount)
                .as("Total column count in roundtrip schema")
                .isEqualTo(original.columnCount);
    }

    /**
     * Queries schema metadata for comparison. Override for databases that don't support information_schema.
     */
    protected SchemaObjects querySchemaObjects(Connection connection, String schema) throws SQLException {
        SchemaObjects objects = new SchemaObjects();

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_type = 'BASE TABLE' ORDER BY table_name")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    objects.tables.add(rs.getString(1).toUpperCase());
                }
            }
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT table_name FROM information_schema.views WHERE table_schema = ? ORDER BY table_name")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    objects.views.add(rs.getString(1).toUpperCase());
                }
            }
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = ?")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    objects.columnCount = rs.getInt(1);
                }
            }
        }

        return objects;
    }

    private List<String> splitStatements(String ddl) {
        List<String> statements = new ArrayList<>();
        for (String stmt : ddl.split(";")) {
            String trimmed = stmt.trim();
            if (!trimmed.isEmpty()) {
                statements.add(trimmed);
            }
        }
        return statements;
    }

    protected static class SchemaObjects {
        final TreeSet<String> tables = new TreeSet<>();
        final TreeSet<String> views = new TreeSet<>();
        int columnCount;
    }
}
