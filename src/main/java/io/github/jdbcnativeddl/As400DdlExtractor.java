package io.github.jdbcnativeddl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

import static io.github.jdbcnativeddl.DdlExtractor.quoteId;

/** IBM i (AS/400) DDL extractor using {@code QSYS2.*} catalog views. */
public class As400DdlExtractor implements DdlExtractor {

    private static final Logger log = LoggerFactory.getLogger(As400DdlExtractor.class);

    @Override
    public boolean supports(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.startsWith("jdbc:as400:");
    }

    @Override
    public String extractDdl(Connection connection, String schema) throws SQLException {
        String upperSchema = schema.toUpperCase();
        StringBuilder ddl = new StringBuilder();

        ddl.append("CREATE SCHEMA ").append(quoteId(upperSchema)).append(";\n\n");

        extractSequences(connection, upperSchema, ddl);
        extractTables(connection, upperSchema, ddl);
        extractCheckConstraints(connection, upperSchema, ddl);
        extractForeignKeys(connection, upperSchema, ddl);
        extractIndexes(connection, upperSchema, ddl);
        extractViews(connection, upperSchema, ddl);

        return ddl.toString();
    }

    private void extractSequences(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT SEQUENCE_NAME, START, INCREMENT, MINIMUM_VALUE, MAXIMUM_VALUE, CACHE, CYCLE_OPTION, DATA_TYPE
                FROM QSYS2.SYSSEQUENCES
                WHERE SEQUENCE_SCHEMA = ?
                ORDER BY SEQUENCE_NAME
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ddl.append("CREATE SEQUENCE ").append(quoteId(rs.getString("SEQUENCE_NAME")))
                            .append(" AS ").append(rs.getString("DATA_TYPE").trim())
                            .append(" START WITH ").append(rs.getLong("START"))
                            .append(" INCREMENT BY ").append(rs.getLong("INCREMENT"))
                            .append(" MINVALUE ").append(rs.getLong("MINIMUM_VALUE"))
                            .append(" MAXVALUE ").append(rs.getLong("MAXIMUM_VALUE"));
                    int cache = rs.getInt("CACHE");
                    if (cache > 0) {
                        ddl.append(" CACHE ").append(cache);
                    } else {
                        ddl.append(" NO CACHE");
                    }
                    if ("YES".equalsIgnoreCase(rs.getString("CYCLE_OPTION"))) {
                        ddl.append(" CYCLE");
                    }
                    ddl.append(";\n\n");
                }
            }
        }
    }

    private void extractTables(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        String tablesSql = """
                SELECT TABLE_NAME
                FROM QSYS2.SYSTABLES
                WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'T'
                ORDER BY TABLE_NAME
                """;
        List<String> tables = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(tablesSql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
        }

        for (String tableName : tables) {
            ddl.append("CREATE TABLE ").append(quoteId(tableName)).append(" (\n");
            extractColumns(connection, schema, tableName, ddl);
            extractPrimaryKey(connection, schema, tableName, ddl);
            extractUniqueConstraints(connection, schema, tableName, ddl);
            ddl.append("\n);\n\n");
        }
    }

    private void extractColumns(Connection connection, String schema, String tableName, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT COLUMN_NAME, DATA_TYPE, LENGTH, NUMERIC_SCALE, IS_NULLABLE,
                       COLUMN_DEFAULT, IS_IDENTITY, IDENTITY_GENERATION
                FROM QSYS2.SYSCOLUMNS
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
                ORDER BY ORDINAL_POSITION
                """;
        boolean first = true;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (!first) ddl.append(",\n");
                    first = false;

                    String colName = rs.getString("COLUMN_NAME");
                    String dataType = rs.getString("DATA_TYPE").trim();
                    int length = rs.getInt("LENGTH");
                    int scale = rs.getInt("NUMERIC_SCALE");

                    ddl.append("    ").append(quoteId(colName)).append(" ").append(formatType(dataType, length, scale));

                    String isIdentity = rs.getString("IS_IDENTITY");
                    if ("YES".equalsIgnoreCase(isIdentity)) {
                        String generation = rs.getString("IDENTITY_GENERATION");
                        if ("ALWAYS".equalsIgnoreCase(generation)) {
                            ddl.append(" GENERATED ALWAYS AS IDENTITY");
                        } else {
                            ddl.append(" GENERATED BY DEFAULT AS IDENTITY");
                        }
                    } else {
                        String defaultVal = rs.getString("COLUMN_DEFAULT");
                        if (defaultVal != null && !defaultVal.isBlank()) {
                            ddl.append(" DEFAULT ").append(defaultVal.trim());
                        }
                    }

                    if ("N".equalsIgnoreCase(rs.getString("IS_NULLABLE"))) {
                        ddl.append(" NOT NULL");
                    }
                }
            }
        }
    }

    private String formatType(String dataType, int length, int scale) {
        return switch (dataType) {
            case "VARCHAR", "CHARACTER", "CHAR", "VARGRAPHIC", "GRAPHIC" -> dataType + "(" + length + ")";
            case "DECIMAL", "NUMERIC" -> dataType + "(" + length + "," + scale + ")";
            default -> dataType;
        };
    }

    private void extractPrimaryKey(Connection connection, String schema, String tableName, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT c.CONSTRAINT_NAME,
                       k.COLUMN_NAME
                FROM QSYS2.SYSCST c
                JOIN QSYS2.SYSKEYCST k ON c.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA
                    AND c.CONSTRAINT_NAME = k.CONSTRAINT_NAME
                WHERE c.TABLE_SCHEMA = ? AND c.TABLE_NAME = ? AND c.CONSTRAINT_TYPE = 'PRIMARY KEY'
                ORDER BY k.ORDINAL_POSITION
                """;
        List<String> columns = new ArrayList<>();
        String constraintName = null;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (constraintName == null) {
                        constraintName = rs.getString("CONSTRAINT_NAME").trim();
                    }
                    columns.add(quoteId(rs.getString("COLUMN_NAME")));
                }
            }
        }
        if (constraintName != null) {
            ddl.append(",\n    CONSTRAINT ").append(quoteId(constraintName))
                    .append(" PRIMARY KEY (").append(String.join(", ", columns)).append(")");
        }
    }

    private void extractUniqueConstraints(Connection connection, String schema, String tableName, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT c.CONSTRAINT_NAME,
                       k.COLUMN_NAME
                FROM QSYS2.SYSCST c
                JOIN QSYS2.SYSKEYCST k ON c.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA
                    AND c.CONSTRAINT_NAME = k.CONSTRAINT_NAME
                WHERE c.TABLE_SCHEMA = ? AND c.TABLE_NAME = ? AND c.CONSTRAINT_TYPE = 'UNIQUE'
                ORDER BY c.CONSTRAINT_NAME, k.ORDINAL_POSITION
                """;
        String currentConstraint = null;
        List<String> columns = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("CONSTRAINT_NAME").trim();
                    if (currentConstraint != null && !currentConstraint.equals(name)) {
                        ddl.append(",\n    CONSTRAINT ").append(quoteId(currentConstraint))
                                .append(" UNIQUE (").append(String.join(", ", columns)).append(")");
                        columns.clear();
                    }
                    currentConstraint = name;
                    columns.add(quoteId(rs.getString("COLUMN_NAME")));
                }
            }
        }
        if (currentConstraint != null) {
            ddl.append(",\n    CONSTRAINT ").append(quoteId(currentConstraint))
                    .append(" UNIQUE (").append(String.join(", ", columns)).append(")");
        }
    }

    private void extractCheckConstraints(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT c.TABLE_NAME, c.CONSTRAINT_NAME, ch.CHECK_CLAUSE
                FROM QSYS2.SYSCST c
                JOIN QSYS2.SYSCHKCST ch ON c.CONSTRAINT_SCHEMA = ch.CONSTRAINT_SCHEMA
                    AND c.CONSTRAINT_NAME = ch.CONSTRAINT_NAME
                WHERE c.TABLE_SCHEMA = ? AND c.CONSTRAINT_TYPE = 'CHECK'
                ORDER BY c.TABLE_NAME, c.CONSTRAINT_NAME
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ddl.append("ALTER TABLE ").append(quoteId(rs.getString("TABLE_NAME")))
                            .append(" ADD CONSTRAINT ").append(quoteId(rs.getString("CONSTRAINT_NAME").trim()))
                            .append(" CHECK (").append(rs.getString("CHECK_CLAUSE").trim())
                            .append(");\n\n");
                }
            }
        }
    }

    private void extractForeignKeys(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        // Get FK constraints with their columns, and resolve parent table via the referenced unique constraint
        String sql = """
                SELECT fk.CONSTRAINT_NAME, fk.TABLE_NAME AS CHILD_TABLE,
                       pk.TABLE_NAME AS PARENT_TABLE
                FROM QSYS2.SYSCST fk
                JOIN QSYS2.SYSREFCST r ON fk.CONSTRAINT_SCHEMA = r.CONSTRAINT_SCHEMA
                    AND fk.CONSTRAINT_NAME = r.CONSTRAINT_NAME
                JOIN QSYS2.SYSCST pk ON r.UNIQUE_CONSTRAINT_SCHEMA = pk.CONSTRAINT_SCHEMA
                    AND r.UNIQUE_CONSTRAINT_NAME = pk.CONSTRAINT_NAME
                WHERE fk.TABLE_SCHEMA = ? AND fk.CONSTRAINT_TYPE = 'FOREIGN KEY'
                ORDER BY fk.TABLE_NAME, fk.CONSTRAINT_NAME
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String fkName = rs.getString("CONSTRAINT_NAME").trim();
                    String childTable = rs.getString("CHILD_TABLE");
                    String parentTable = rs.getString("PARENT_TABLE");

                    List<String> fkCols = getKeyColumns(connection, schema, fkName);

                    ddl.append("ALTER TABLE ").append(quoteId(childTable))
                            .append(" ADD CONSTRAINT ").append(quoteId(fkName))
                            .append(" FOREIGN KEY (").append(String.join(", ", fkCols))
                            .append(") REFERENCES ").append(quoteId(parentTable))
                            .append(";\n\n");
                }
            }
        }
    }

    private List<String> getKeyColumns(Connection connection, String schema, String constraintName) throws SQLException {
        String sql = """
                SELECT COLUMN_NAME
                FROM QSYS2.SYSKEYCST
                WHERE CONSTRAINT_SCHEMA = ? AND CONSTRAINT_NAME = ?
                ORDER BY ORDINAL_POSITION
                """;
        List<String> columns = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, constraintName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(quoteId(rs.getString("COLUMN_NAME")));
                }
            }
        }
        return columns;
    }

    private void extractIndexes(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        String indexSql = """
                SELECT INDEX_SCHEMA, INDEX_NAME, TABLE_NAME, "UNIQUE"
                FROM QSYS2.SYSPARTITIONINDEXES
                WHERE TABLE_SCHEMA = ?
                  AND INDEX_TYPE NOT IN ('PRIMARY KEY', 'UNIQUE', 'FOREIGN KEY')
                ORDER BY TABLE_NAME, INDEX_NAME
                """;
        String syskeysSql = """
                SELECT COLUMN_NAME, ORDERING, CAST(KEY_EXPRESSION AS VARCHAR(2000)) AS KEY_EXPRESSION
                FROM QSYS2.SYSKEYS
                WHERE INDEX_SCHEMA = ? AND INDEX_NAME = ?
                ORDER BY ORDINAL_POSITION
                """;
        String qadbkfldSql = """
                SELECT DBKFLD AS COLUMN_NAME, DBKORD AS ORDERING
                FROM QSYS.QADBKFLD
                WHERE DBKLIB = ? AND DBKFIL = ?
                ORDER BY DBKPOS
                """;

        try (PreparedStatement ps = connection.prepareStatement(indexSql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String indexSchema = rs.getString("INDEX_SCHEMA");
                    String indexName = rs.getString("INDEX_NAME");
                    String tableName = rs.getString("TABLE_NAME");
                    String isUnique = rs.getString("UNIQUE");

                    List<String> columns = getIndexColumns(connection, syskeysSql, indexSchema, indexName);
                    if (columns.isEmpty()) {
                        columns = getDdsIndexColumns(connection, qadbkfldSql, indexSchema, indexName);
                    }
                    if (!columns.isEmpty()) {
                        if (!indexSchema.equals(schema)) {
                            ddl.append("-- Source: ").append(indexSchema).append(".").append(indexName).append("\n");
                        }
                        appendIndex(ddl, indexName, tableName, isUnique, columns);
                    }
                }
            }
        }
    }

    private List<String> getIndexColumns(Connection connection, String sql, String indexSchema, String indexName) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, indexSchema);
            ps.setString(2, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String expression = rs.getString("KEY_EXPRESSION");
                    String colName = rs.getString("COLUMN_NAME");
                    String col;
                    if (expression != null) {
                        col = quoteId(colName) + " /* " + expression + " */";
                    } else {
                        col = quoteId(colName);
                    }
                    String ordering = rs.getString("ORDERING");
                    if ("D".equals(ordering)) {
                        col += " DESC";
                    }
                    columns.add(col);
                }
            }
        }
        return columns;
    }

    private List<String> getDdsIndexColumns(Connection connection, String sql, String indexLib, String indexFile) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, indexLib);
            ps.setString(2, indexFile);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String colName = rs.getString("COLUMN_NAME");
                    String col = quoteId(colName);
                    String ordering = rs.getString("ORDERING");
                    if ("D".equals(ordering)) {
                        col += " DESC";
                    }
                    columns.add(col);
                }
            }
        }
        return columns;
    }

    private void appendIndex(StringBuilder ddl, String indexName, String tableName, String isUnique, List<String> columns) {
        ddl.append("CREATE ");
        if ("UNIQUE".equals(isUnique)) {
            ddl.append("UNIQUE ");
        }
        ddl.append("INDEX ").append(quoteId(indexName))
                .append(" ON ").append(quoteId(tableName))
                .append(" (").append(String.join(", ", columns)).append(");\n\n");
    }

    private void extractViews(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT TABLE_NAME, VIEW_DEFINITION
                FROM QSYS2.SYSVIEWS
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME NOT LIKE 'SYS%'
                ORDER BY TABLE_NAME
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    String definition = rs.getString("VIEW_DEFINITION");
                    if (definition != null) {
                        definition = definition.trim();
                        if (!definition.toUpperCase().startsWith("CREATE")) {
                            ddl.append("CREATE VIEW ").append(quoteId(name)).append(" AS\n");
                        }
                        ddl.append(definition).append(";\n\n");
                    }
                }
            }
        }
    }
}
