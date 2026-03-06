package io.github.jdbcnativeddl;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Extracts DDL (Data Definition Language) statements from a live database via JDBC.
 *
 * <p>Each implementation uses database-native catalog views and built-in functions to produce
 * the most accurate DDL, including vendor-specific features like partitioning, expression indexes,
 * temporal tables, and more.
 *
 * <p>Use {@link Ddl#forJdbcUrl(String)} or {@link Ddl#forConnection(Connection)} to obtain an
 * instance for your database.
 */
public interface DdlExtractor {

    /**
     * Extracts all DDL statements for the given schema and returns them as a single string.
     *
     * @param connection an open JDBC connection to the target database
     * @param schema the schema name to extract DDL from
     * @return a string containing all DDL statements
     * @throws SQLException if a database access error occurs
     */
    String extractDdl(Connection connection, String schema) throws SQLException;

    /**
     * Returns {@code true} if this extractor supports the given JDBC URL.
     *
     * @param jdbcUrl the JDBC connection URL
     * @return {@code true} if this extractor can handle the given URL
     */
    boolean supports(String jdbcUrl);

    /**
     * Extracts all DDL statements for the given schema and writes them to the provided {@link Writer}.
     *
     * @param connection an open JDBC connection to the target database
     * @param schema the schema name to extract DDL from
     * @param writer the writer to output DDL to
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs while writing
     */
    default void extractDdl(Connection connection, String schema, Writer writer) throws SQLException, IOException {
        writer.write(extractDdl(connection, schema));
    }

    /**
     * Extracts all DDL statements for the given schema and writes them to a file.
     *
     * @param connection an open JDBC connection to the target database
     * @param schema the schema name to extract DDL from
     * @param outputFile the file path to write DDL to
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs while writing
     */
    default void extractDdl(Connection connection, String schema, Path outputFile) throws SQLException, IOException {
        try (var w = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            extractDdl(connection, schema, w);
        }
    }
}
