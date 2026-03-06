package io.github.jdbcnativeddl;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Entry point for obtaining a {@link DdlExtractor} instance.
 *
 * <p>Example usage:
 * <pre>{@code
 * DdlExtractor extractor = Ddl.forJdbcUrl("jdbc:postgresql://localhost/mydb");
 * String ddl = extractor.extractDdl(connection, "public");
 * }</pre>
 */
public final class Ddl {

    private Ddl() {}

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
