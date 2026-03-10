package io.github.jdbcnativeddl;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Entry point for DDL extraction.
 *
 * <p>Simple usage -- auto-detects database type from the connection:
 * <pre>{@code
 * try (Connection conn = dataSource.getConnection()) {
 *     String ddl = Ddl.extract(conn, "MY_SCHEMA");
 * }
 * }</pre>
 *
 * <p>If you need to reuse the extractor across multiple calls, obtain one via
 * {@link #forJdbcUrl(String)} or {@link #forConnection(Connection)}.
 */
public final class Ddl {

    private Ddl() {}

    /**
     * Extracts all DDL for the given schema, auto-detecting the database type from the connection.
     *
     * @param connection an open JDBC connection
     * @param schema the schema name to extract DDL from
     * @return a string containing all DDL statements
     * @throws SQLException if a database access error occurs
     */
    public static String extract(Connection connection, String schema) throws SQLException {
        return forConnection(connection).extractDdl(connection, schema);
    }

    /**
     * Extracts all DDL for the given schema and writes it to the provided {@link Writer}.
     *
     * @param connection an open JDBC connection
     * @param schema the schema name to extract DDL from
     * @param writer the writer to output DDL to
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs while writing
     */
    public static void extract(Connection connection, String schema, Writer writer) throws SQLException, IOException {
        forConnection(connection).extractDdl(connection, schema, writer);
    }

    /**
     * Extracts all DDL for the given schema and writes it to a file.
     *
     * @param connection an open JDBC connection
     * @param schema the schema name to extract DDL from
     * @param outputFile the file path to write DDL to
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs while writing
     */
    public static void extract(Connection connection, String schema, Path outputFile) throws SQLException, IOException {
        forConnection(connection).extractDdl(connection, schema, outputFile);
    }

    /**
     * Returns a {@link DdlExtractor} for the given JDBC URL.
     *
     * @param jdbcUrl the JDBC connection URL (e.g. {@code "jdbc:postgresql://localhost/mydb"})
     * @return a DdlExtractor that supports the given database
     * @throws IllegalArgumentException if the JDBC URL is not supported
     */
    public static DdlExtractor forJdbcUrl(String jdbcUrl) {
        return DdlExtractorFactory.forJdbcUrl(jdbcUrl);
    }

    /**
     * Returns a {@link DdlExtractor} for the database behind the given connection.
     *
     * <p>The database type is determined from the connection's JDBC URL via
     * {@link java.sql.DatabaseMetaData#getURL()}.
     *
     * @param connection an open JDBC connection
     * @return a DdlExtractor that supports the connected database
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if the database is not supported
     */
    public static DdlExtractor forConnection(Connection connection) throws SQLException {
        String url = connection.getMetaData().getURL();
        return forJdbcUrl(url);
    }
}
