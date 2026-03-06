package io.github.jdbcnativeddl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;

/** Oracle DDL extractor using {@code DBMS_METADATA.GET_DDL}. */
public class OracleDdlExtractor implements DdlExtractor {

    private static final Logger log = LoggerFactory.getLogger(OracleDdlExtractor.class);

    @Override
    public boolean supports(String jdbcUrl) {
        return jdbcUrl != null && (jdbcUrl.startsWith("jdbc:oracle:") || jdbcUrl.startsWith("jdbc:thin:"));
    }

    @Override
    public String extractDdl(Connection connection, String schema) throws SQLException {
        String upperSchema = schema.toUpperCase();
        StringBuilder ddl = new StringBuilder();

        configureMetadata(connection);
        extractTables(connection, upperSchema, ddl);
        extractIndexes(connection, upperSchema, ddl);
        extractSequences(connection, upperSchema, ddl);
        extractViews(connection, upperSchema, ddl);
        extractMaterializedViews(connection, upperSchema, ddl);

        return ddl.toString();
    }

    private void configureMetadata(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("BEGIN DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'STORAGE', false); END;");
            stmt.execute("BEGIN DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'SEGMENT_ATTRIBUTES', false); END;");
            stmt.execute("BEGIN DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'SQLTERMINATOR', true); END;");
            stmt.execute("BEGIN DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'CONSTRAINTS', true); END;");
            stmt.execute("BEGIN DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'REF_CONSTRAINTS', true); END;");
        }
    }

    private void extractTables(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT DBMS_METADATA.GET_DDL('TABLE', table_name, owner) AS ddl_text
                FROM all_tables
                WHERE owner = ? AND nested = 'NO' AND secondary = 'N'
                  AND table_name NOT LIKE 'SYS_%' AND table_name NOT LIKE 'MLOG$%' AND table_name NOT LIKE 'RUPD$%'
                ORDER BY table_name
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ddl.append(rs.getString("ddl_text").trim()).append("\n\n");
                }
            }
        }
    }

    private void extractIndexes(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT DBMS_METADATA.GET_DDL('INDEX', i.index_name, i.owner) AS ddl_text
                FROM all_indexes i
                WHERE i.table_owner = ?
                  AND i.index_type != 'LOB'
                  AND NOT EXISTS (
                      SELECT 1 FROM all_constraints c
                      WHERE c.owner = i.owner AND c.index_name = i.index_name
                        AND c.constraint_type IN ('P', 'U')
                  )
                ORDER BY i.index_name
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ddl.append(rs.getString("ddl_text").trim()).append("\n\n");
                }
            }
        }
    }

    private void extractSequences(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT DBMS_METADATA.GET_DDL('SEQUENCE', sequence_name, sequence_owner) AS ddl_text
                FROM all_sequences
                WHERE sequence_owner = ? AND sequence_name NOT LIKE 'ISEQ$$%'
                ORDER BY sequence_name
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ddl.append(rs.getString("ddl_text").trim()).append("\n\n");
                }
            }
        }
    }

    private void extractViews(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT DBMS_METADATA.GET_DDL('VIEW', view_name, owner) AS ddl_text
                FROM all_views
                WHERE owner = ?
                ORDER BY view_name
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ddl.append(rs.getString("ddl_text").trim()).append("\n\n");
                }
            }
        }
    }

    private void extractMaterializedViews(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT DBMS_METADATA.GET_DDL('MATERIALIZED_VIEW', mview_name, owner) AS ddl_text
                FROM all_mviews
                WHERE owner = ?
                ORDER BY mview_name
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ddl.append(rs.getString("ddl_text").trim()).append("\n\n");
                }
            }
        }
    }
}
