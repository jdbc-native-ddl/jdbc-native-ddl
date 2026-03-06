package io.github.jdbcnativeddl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** DB2 DDL extractor using {@code SYSCAT.*} catalog views. */
public class Db2DdlExtractor implements DdlExtractor {

    private static final Logger log = LoggerFactory.getLogger(Db2DdlExtractor.class);

    @Override
    public boolean supports(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.startsWith("jdbc:db2:");
    }

    @Override
    public String extractDdl(Connection connection, String schema) throws SQLException {
        String upperSchema = schema.toUpperCase();
        StringBuilder ddl = new StringBuilder();

        extractSequences(connection, upperSchema, ddl);
        extractTables(connection, upperSchema, ddl);
        extractCheckConstraints(connection, upperSchema, ddl);
        extractForeignKeys(connection, upperSchema, ddl);
        extractIndexes(connection, upperSchema, ddl);
        extractViews(connection, upperSchema, ddl);
        extractMaterializedViews(connection, upperSchema, ddl);

        return ddl.toString();
    }

    private void extractSequences(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT SEQNAME, START, INCREMENT, MINVALUE, MAXVALUE, CACHE, CYCLE, DATATYPEID
                FROM SYSCAT.SEQUENCES
                WHERE SEQSCHEMA = ? AND SEQTYPE = 'S'
                ORDER BY SEQNAME
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ddl.append("CREATE SEQUENCE ").append(rs.getString("SEQNAME"))
                            .append(" START WITH ").append(rs.getLong("START"))
                            .append(" INCREMENT BY ").append(rs.getLong("INCREMENT"))
                            .append(" MINVALUE ").append(rs.getLong("MINVALUE"))
                            .append(" MAXVALUE ").append(rs.getLong("MAXVALUE"));
                    int cache = rs.getInt("CACHE");
                    if (cache > 0) {
                        ddl.append(" CACHE ").append(cache);
                    } else {
                        ddl.append(" NO CACHE");
                    }
                    if ("Y".equals(rs.getString("CYCLE"))) {
                        ddl.append(" CYCLE");
                    }
                    ddl.append(";\n\n");
                }
            }
        }
    }

    private void extractTables(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        String tablesSql = """
                SELECT TABNAME, TBSPACE
                FROM SYSCAT.TABLES
                WHERE TABSCHEMA = ? AND TYPE = 'T'
                ORDER BY TABNAME
                """;
        List<String[]> tables = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(tablesSql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(new String[]{rs.getString("TABNAME"), rs.getString("TBSPACE")});
                }
            }
        }

        for (String[] table : tables) {
            String tableName = table[0];
            String tbspace = table[1];

            ddl.append("CREATE TABLE ").append(tableName).append(" (\n");
            extractColumns(connection, schema, tableName, ddl);
            extractPrimaryKey(connection, schema, tableName, ddl);
            extractUniqueConstraints(connection, schema, tableName, ddl);
            ddl.append("\n)");

            if (tbspace != null && !tbspace.isBlank()) {
                ddl.append(" IN ").append(tbspace.trim());
            }

            extractPartitioning(connection, schema, tableName, ddl);

            ddl.append(";\n\n");
        }
    }

    private void extractColumns(Connection connection, String schema, String tableName, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT COLNAME, TYPENAME, LENGTH, SCALE, NULLS, DEFAULT, IDENTITY, GENERATED, TEXT
                FROM SYSCAT.COLUMNS
                WHERE TABSCHEMA = ? AND TABNAME = ?
                ORDER BY COLNO
                """;
        boolean first = true;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (!first) ddl.append(",\n");
                    first = false;

                    String colName = rs.getString("COLNAME");
                    String typeName = rs.getString("TYPENAME").trim();
                    int length = rs.getInt("LENGTH");
                    int scale = rs.getInt("SCALE");

                    ddl.append("    ").append(colName).append(" ").append(formatDb2Type(typeName, length, scale));

                    String identity = rs.getString("IDENTITY");
                    if ("Y".equals(identity)) {
                        String generated = rs.getString("GENERATED");
                        if ("A".equals(generated)) {
                            ddl.append(" GENERATED ALWAYS AS IDENTITY");
                        } else {
                            ddl.append(" GENERATED BY DEFAULT AS IDENTITY");
                        }
                    } else {
                        String defaultVal = rs.getString("DEFAULT");
                        if (defaultVal != null && !defaultVal.isBlank()) {
                            ddl.append(" DEFAULT ").append(defaultVal.trim());
                        }
                    }

                    if ("N".equals(rs.getString("NULLS"))) {
                        ddl.append(" NOT NULL");
                    }
                }
            }
        }
    }

    private String formatDb2Type(String typeName, int length, int scale) {
        return switch (typeName) {
            case "VARCHAR", "CHARACTER", "CHAR", "VARGRAPHIC", "GRAPHIC" -> typeName + "(" + length + ")";
            case "DECIMAL" -> typeName + "(" + length + "," + scale + ")";
            default -> typeName;
        };
    }

    private void extractPrimaryKey(Connection connection, String schema, String tableName, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT tc.CONSTNAME,
                       LISTAGG(kcu.COLNAME, ', ') WITHIN GROUP (ORDER BY kcu.COLSEQ) AS columns
                FROM SYSCAT.TABCONST tc
                JOIN SYSCAT.KEYCOLUSE kcu ON tc.CONSTNAME = kcu.CONSTNAME AND tc.TABSCHEMA = kcu.TABSCHEMA AND tc.TABNAME = kcu.TABNAME
                WHERE tc.TABSCHEMA = ? AND tc.TABNAME = ? AND tc.TYPE = 'P'
                GROUP BY tc.CONSTNAME
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ddl.append(",\n    CONSTRAINT ").append(rs.getString("CONSTNAME").trim())
                            .append(" PRIMARY KEY (").append(rs.getString("columns")).append(")");
                }
            }
        }
    }

    private void extractUniqueConstraints(Connection connection, String schema, String tableName, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT tc.CONSTNAME,
                       LISTAGG(kcu.COLNAME, ', ') WITHIN GROUP (ORDER BY kcu.COLSEQ) AS columns
                FROM SYSCAT.TABCONST tc
                JOIN SYSCAT.KEYCOLUSE kcu ON tc.CONSTNAME = kcu.CONSTNAME AND tc.TABSCHEMA = kcu.TABSCHEMA AND tc.TABNAME = kcu.TABNAME
                WHERE tc.TABSCHEMA = ? AND tc.TABNAME = ? AND tc.TYPE = 'U'
                GROUP BY tc.CONSTNAME
                ORDER BY tc.CONSTNAME
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ddl.append(",\n    CONSTRAINT ").append(rs.getString("CONSTNAME").trim())
                            .append(" UNIQUE (").append(rs.getString("columns")).append(")");
                }
            }
        }
    }

    private void extractPartitioning(Connection connection, String schema, String tableName, StringBuilder ddl) {
      try {
        extractPartitioningInternal(connection, schema, tableName, ddl);
      } catch (SQLException e) {
        log.debug("Could not extract partitioning for {}.{}: {}", schema, tableName, e.getMessage());
      }
    }

    private void extractPartitioningInternal(Connection connection, String schema, String tableName, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT DATAPARTITIONNAME, LOWVALUE, HIGHVALUE
                FROM SYSCAT.DATAPARTITIONS
                WHERE TABSCHEMA = ? AND TABNAME = ?
                ORDER BY SEQNO
                """;
        List<String[]> partitions = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    partitions.add(new String[]{
                            rs.getString("DATAPARTITIONNAME"),
                            rs.getString("LOWVALUE"),
                            rs.getString("HIGHVALUE")
                    });
                }
            }
        }

        if (!partitions.isEmpty()) {
            String exprSql = """
                    SELECT DATAPARTITIONEXPRESSION
                    FROM SYSCAT.DATAPARTITIONEXPRESSION
                    WHERE TABSCHEMA = ? AND TABNAME = ?
                    ORDER BY SEQNO
                    """;
            String partExpr = null;
            try (PreparedStatement ps = connection.prepareStatement(exprSql)) {
                ps.setString(1, schema);
                ps.setString(2, tableName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        partExpr = rs.getString("DATAPARTITIONEXPRESSION");
                    }
                }
            }

            if (partExpr != null) {
                ddl.append("\nPARTITION BY RANGE (").append(partExpr.trim()).append(") (");
                boolean first = true;
                for (String[] p : partitions) {
                    if (!first) ddl.append(", ");
                    first = false;
                    ddl.append("PARTITION ").append(p[0].trim())
                            .append(" STARTING ").append(p[1] != null ? p[1].trim() : "MINVALUE")
                            .append(" ENDING ").append(p[2] != null ? p[2].trim() : "MAXVALUE");
                }
                ddl.append(")");
            }
        }
    }

    private void extractCheckConstraints(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT tc.TABNAME, tc.CONSTNAME, ch.TEXT
                FROM SYSCAT.TABCONST tc
                JOIN SYSCAT.CHECKS ch ON tc.CONSTNAME = ch.CONSTNAME AND tc.TABSCHEMA = ch.TABSCHEMA AND tc.TABNAME = ch.TABNAME
                WHERE tc.TABSCHEMA = ? AND tc.TYPE = 'K'
                ORDER BY tc.TABNAME, tc.CONSTNAME
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ddl.append("ALTER TABLE ").append(rs.getString("TABNAME"))
                            .append(" ADD CONSTRAINT ").append(rs.getString("CONSTNAME").trim())
                            .append(" CHECK ").append(rs.getString("TEXT").trim())
                            .append(";\n\n");
                }
            }
        }
    }

    private void extractForeignKeys(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT r.CONSTNAME, r.TABNAME, r.REFTABNAME, r.FK_COLNAMES, r.PK_COLNAMES
                FROM SYSCAT.REFERENCES r
                WHERE r.TABSCHEMA = ?
                ORDER BY r.TABNAME, r.CONSTNAME
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String fkCols = rs.getString("FK_COLNAMES").trim();
                    String pkCols = rs.getString("PK_COLNAMES").trim();
                    ddl.append("ALTER TABLE ").append(rs.getString("TABNAME"))
                            .append(" ADD CONSTRAINT ").append(rs.getString("CONSTNAME").trim())
                            .append(" FOREIGN KEY (").append(fkCols)
                            .append(") REFERENCES ").append(rs.getString("REFTABNAME"))
                            .append(" (").append(pkCols).append(");\n\n");
                }
            }
        }
    }

    private void extractIndexes(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT i.INDNAME, i.TABNAME, i.UNIQUERULE, i.INDEXTYPE,
                       LISTAGG(ic.COLNAME, ', ') WITHIN GROUP (ORDER BY ic.COLSEQ) AS columns
                FROM SYSCAT.INDEXES i
                JOIN SYSCAT.INDEXCOLUSE ic ON i.INDSCHEMA = ic.INDSCHEMA AND i.INDNAME = ic.INDNAME
                WHERE i.INDSCHEMA = ? AND i.SYSTEM_REQUIRED = 0
                  AND NOT EXISTS (
                      SELECT 1 FROM SYSCAT.TABCONST tc
                      WHERE tc.TABSCHEMA = i.TABSCHEMA AND tc.TABNAME = i.TABNAME
                        AND tc.CONSTNAME = i.INDNAME AND tc.TYPE IN ('P', 'U')
                  )
                GROUP BY i.INDNAME, i.TABNAME, i.UNIQUERULE, i.INDEXTYPE
                ORDER BY i.TABNAME, i.INDNAME
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ddl.append("CREATE ");
                    if ("U".equals(rs.getString("UNIQUERULE"))) {
                        ddl.append("UNIQUE ");
                    }
                    ddl.append("INDEX ").append(rs.getString("INDNAME"))
                            .append(" ON ").append(rs.getString("TABNAME"))
                            .append(" (").append(rs.getString("columns")).append(");\n\n");
                }
            }
        }
    }

    private void extractViews(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT VIEWNAME, TEXT
                FROM SYSCAT.VIEWS
                WHERE VIEWSCHEMA = ? AND VIEWNAME NOT LIKE 'SYS%'
                ORDER BY VIEWNAME
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ddl.append(rs.getString("TEXT").trim()).append(";\n\n");
                }
            }
        }
    }

    private void extractMaterializedViews(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT TABNAME
                FROM SYSCAT.TABLES
                WHERE TABSCHEMA = ? AND TYPE = 'S'
                ORDER BY TABNAME
                """;
        List<String> mqtNames = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    mqtNames.add(rs.getString("TABNAME"));
                }
            }
        }

        for (String mqtName : mqtNames) {
            String viewSql = """
                    SELECT TEXT FROM SYSCAT.VIEWS WHERE VIEWSCHEMA = ? AND VIEWNAME = ?
                    """;
            try (PreparedStatement ps = connection.prepareStatement(viewSql)) {
                ps.setString(1, schema);
                ps.setString(2, mqtName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        ddl.append(rs.getString("TEXT").trim()).append(";\n\n");
                    }
                }
            }
        }
    }
}
