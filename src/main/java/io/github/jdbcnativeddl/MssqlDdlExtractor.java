package io.github.jdbcnativeddl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** SQL Server DDL extractor using {@code sys.*} catalog views. */
public class MssqlDdlExtractor implements DdlExtractor {

    private static final Logger log = LoggerFactory.getLogger(MssqlDdlExtractor.class);

    @Override
    public boolean supports(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.startsWith("jdbc:sqlserver:");
    }

    @Override
    public String extractDdl(Connection connection, String schema) throws SQLException {
        StringBuilder ddl = new StringBuilder();

        extractSequences(connection, schema, ddl);
        extractTables(connection, schema, ddl);
        extractCheckConstraints(connection, schema, ddl);
        extractForeignKeys(connection, schema, ddl);
        extractIndexes(connection, schema, ddl);
        extractViews(connection, schema, ddl);

        return ddl.toString();
    }

    private void extractSequences(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT s.name, s.start_value, s.increment, s.minimum_value, s.maximum_value,
                       TYPE_NAME(s.system_type_id) AS data_type, s.is_cycling
                FROM sys.sequences s
                JOIN sys.schemas sc ON s.schema_id = sc.schema_id
                WHERE sc.name = ?
                ORDER BY s.name
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ddl.append("CREATE SEQUENCE ").append(schema).append(".").append(rs.getString("name"))
                            .append(" AS ").append(rs.getString("data_type"))
                            .append(" START WITH ").append(rs.getLong("start_value"))
                            .append(" INCREMENT BY ").append(rs.getLong("increment"))
                            .append(" MINVALUE ").append(rs.getLong("minimum_value"))
                            .append(" MAXVALUE ").append(rs.getLong("maximum_value"));
                    if (rs.getBoolean("is_cycling")) {
                        ddl.append(" CYCLE");
                    }
                    ddl.append(";\n\n");
                }
            }
        }
    }

    private void extractTables(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        String tablesSql = """
                SELECT t.name AS table_name, t.temporal_type,
                       ht.name AS history_table_name, hs.name AS history_schema_name
                FROM sys.tables t
                JOIN sys.schemas s ON t.schema_id = s.schema_id
                LEFT JOIN sys.tables ht ON t.history_table_id = ht.object_id
                LEFT JOIN sys.schemas hs ON ht.schema_id = hs.schema_id
                WHERE s.name = ? AND t.is_ms_shipped = 0 AND t.temporal_type != 1
                ORDER BY t.name
                """;
        List<String[]> tables = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(tablesSql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(new String[]{
                            rs.getString("table_name"),
                            String.valueOf(rs.getInt("temporal_type")),
                            rs.getString("history_schema_name"),
                            rs.getString("history_table_name")
                    });
                }
            }
        }

        for (String[] table : tables) {
            String tableName = table[0];
            int temporalType = Integer.parseInt(table[1]);
            String historySchema = table[2];
            String historyTable = table[3];

            ddl.append("CREATE TABLE ").append(schema).append(".").append(tableName).append(" (\n");
            extractColumns(connection, schema, tableName, ddl);
            extractPrimaryKeyAndUnique(connection, schema, tableName, ddl);

            if (temporalType == 2) {
                extractPeriod(connection, schema, tableName, ddl);
            }

            ddl.append("\n)");

            if (temporalType == 2) {
                ddl.append("\nWITH (SYSTEM_VERSIONING = ON");
                if (historyTable != null) {
                    ddl.append(" (HISTORY_TABLE = ").append(historySchema).append(".").append(historyTable).append(")");
                }
                ddl.append(")");
            }

            ddl.append(";\n\n");
        }
    }

    private void extractColumns(Connection connection, String schema, String tableName, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT c.name, TYPE_NAME(c.user_type_id) AS type_name,
                       c.max_length, c.precision, c.scale, c.is_nullable, c.is_identity,
                       c.generated_always_type,
                       ic.seed_value, ic.increment_value,
                       cc.definition AS computed_def, cc.is_persisted,
                       dc.definition AS default_def
                FROM sys.columns c
                JOIN sys.tables t ON c.object_id = t.object_id
                JOIN sys.schemas s ON t.schema_id = s.schema_id
                LEFT JOIN sys.identity_columns ic ON c.object_id = ic.object_id AND c.column_id = ic.column_id
                LEFT JOIN sys.computed_columns cc ON c.object_id = cc.object_id AND c.column_id = cc.column_id
                LEFT JOIN sys.default_constraints dc ON c.default_object_id = dc.object_id
                WHERE s.name = ? AND t.name = ?
                ORDER BY c.column_id
                """;
        boolean first = true;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (!first) ddl.append(",\n");
                    first = false;

                    String colName = rs.getString("name");
                    String computedDef = rs.getString("computed_def");

                    ddl.append("    ").append(colName);

                    if (computedDef != null) {
                        ddl.append(" AS ").append(computedDef);
                        if (rs.getBoolean("is_persisted")) {
                            ddl.append(" PERSISTED");
                        }
                    } else {
                        String typeName = rs.getString("type_name");
                        ddl.append(" ").append(formatMssqlType(typeName, rs.getInt("max_length"),
                                rs.getInt("precision"), rs.getInt("scale")));

                        if (rs.getBoolean("is_identity")) {
                            ddl.append(" IDENTITY(").append(rs.getInt("seed_value"))
                                    .append(",").append(rs.getInt("increment_value")).append(")");
                        }

                        int generatedAlways = rs.getInt("generated_always_type");
                        if (generatedAlways == 1) {
                            ddl.append(" GENERATED ALWAYS AS ROW START");
                        } else if (generatedAlways == 2) {
                            ddl.append(" GENERATED ALWAYS AS ROW END");
                        } else {
                            String defaultDef = rs.getString("default_def");
                            if (defaultDef != null) {
                                ddl.append(" DEFAULT ").append(defaultDef);
                            }
                        }

                        if (!rs.getBoolean("is_nullable")) {
                            ddl.append(" NOT NULL");
                        }
                    }
                }
            }
        }
    }

    private String formatMssqlType(String typeName, int maxLength, int precision, int scale) {
        return switch (typeName.toLowerCase()) {
            case "varchar", "nvarchar", "char", "nchar", "varbinary" -> {
                String len = maxLength == -1 ? "MAX" : String.valueOf(
                        typeName.toLowerCase().startsWith("n") ? maxLength / 2 : maxLength);
                yield typeName + "(" + len + ")";
            }
            case "decimal", "numeric" -> typeName + "(" + precision + "," + scale + ")";
            default -> typeName;
        };
    }

    private void extractPrimaryKeyAndUnique(Connection connection, String schema, String tableName, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT kc.name AS constraint_name, kc.type_desc,
                       STRING_AGG(col.name, ', ') WITHIN GROUP (ORDER BY ic.key_ordinal) AS columns
                FROM sys.key_constraints kc
                JOIN sys.tables t ON kc.parent_object_id = t.object_id
                JOIN sys.schemas s ON t.schema_id = s.schema_id
                JOIN sys.index_columns ic ON kc.parent_object_id = ic.object_id AND kc.unique_index_id = ic.index_id
                JOIN sys.columns col ON ic.object_id = col.object_id AND ic.column_id = col.column_id
                WHERE s.name = ? AND t.name = ? AND ic.is_included_column = 0
                GROUP BY kc.name, kc.type_desc
                ORDER BY kc.type_desc, kc.name
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String type = rs.getString("type_desc").contains("PRIMARY") ? "PRIMARY KEY" : "UNIQUE";
                    ddl.append(",\n    CONSTRAINT ").append(rs.getString("constraint_name"))
                            .append(" ").append(type)
                            .append(" (").append(rs.getString("columns")).append(")");
                }
            }
        }
    }

    private void extractPeriod(Connection connection, String schema, String tableName, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT COL_NAME(p.object_id, p.start_column_id) AS start_col,
                       COL_NAME(p.object_id, p.end_column_id) AS end_col
                FROM sys.periods p
                JOIN sys.tables t ON p.object_id = t.object_id
                JOIN sys.schemas s ON t.schema_id = s.schema_id
                WHERE s.name = ? AND t.name = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ddl.append(",\n    PERIOD FOR SYSTEM_TIME (")
                            .append(rs.getString("start_col")).append(", ")
                            .append(rs.getString("end_col")).append(")");
                }
            }
        }
    }

    private void extractCheckConstraints(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT cc.name AS constraint_name, OBJECT_NAME(cc.parent_object_id) AS table_name,
                       cc.definition
                FROM sys.check_constraints cc
                JOIN sys.tables t ON cc.parent_object_id = t.object_id
                JOIN sys.schemas s ON t.schema_id = s.schema_id
                WHERE s.name = ? AND cc.is_ms_shipped = 0
                ORDER BY table_name, cc.name
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ddl.append("ALTER TABLE ").append(schema).append(".").append(rs.getString("table_name"))
                            .append(" ADD CONSTRAINT ").append(rs.getString("constraint_name"))
                            .append(" CHECK ").append(rs.getString("definition"))
                            .append(";\n\n");
                }
            }
        }
    }

    private void extractForeignKeys(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT fk.name AS fk_name, OBJECT_NAME(fk.parent_object_id) AS child_table,
                       OBJECT_NAME(fk.referenced_object_id) AS parent_table,
                       STRING_AGG(COL_NAME(fkc.parent_object_id, fkc.parent_column_id), ', ')
                           WITHIN GROUP (ORDER BY fkc.constraint_column_id) AS child_cols,
                       STRING_AGG(COL_NAME(fkc.referenced_object_id, fkc.referenced_column_id), ', ')
                           WITHIN GROUP (ORDER BY fkc.constraint_column_id) AS parent_cols
                FROM sys.foreign_keys fk
                JOIN sys.foreign_key_columns fkc ON fk.object_id = fkc.constraint_object_id
                JOIN sys.schemas s ON fk.schema_id = s.schema_id
                WHERE s.name = ?
                GROUP BY fk.name, fk.parent_object_id, fk.referenced_object_id
                ORDER BY fk.name
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ddl.append("ALTER TABLE ").append(schema).append(".").append(rs.getString("child_table"))
                            .append(" ADD CONSTRAINT ").append(rs.getString("fk_name"))
                            .append(" FOREIGN KEY (").append(rs.getString("child_cols"))
                            .append(") REFERENCES ").append(schema).append(".").append(rs.getString("parent_table"))
                            .append(" (").append(rs.getString("parent_cols")).append(");\n\n");
                }
            }
        }
    }

    private void extractIndexes(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT i.name AS index_name, t.name AS table_name,
                       i.type_desc, i.is_unique, i.filter_definition,
                       (SELECT STRING_AGG(c2.name, ', ') WITHIN GROUP (ORDER BY ic2.key_ordinal)
                        FROM sys.index_columns ic2
                        JOIN sys.columns c2 ON ic2.object_id = c2.object_id AND ic2.column_id = c2.column_id
                        WHERE ic2.object_id = i.object_id AND ic2.index_id = i.index_id AND ic2.is_included_column = 0
                       ) AS key_columns,
                       (SELECT STRING_AGG(c3.name, ', ') WITHIN GROUP (ORDER BY ic3.index_column_id)
                        FROM sys.index_columns ic3
                        JOIN sys.columns c3 ON ic3.object_id = c3.object_id AND ic3.column_id = c3.column_id
                        WHERE ic3.object_id = i.object_id AND ic3.index_id = i.index_id AND ic3.is_included_column = 1
                       ) AS include_columns
                FROM sys.indexes i
                JOIN sys.tables t ON i.object_id = t.object_id
                JOIN sys.schemas s ON t.schema_id = s.schema_id
                WHERE s.name = ? AND i.type > 0
                  AND i.is_primary_key = 0 AND i.is_unique_constraint = 0
                  AND t.is_ms_shipped = 0 AND t.temporal_type != 1
                ORDER BY t.name, i.name
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String typeDesc = rs.getString("type_desc");
                    if (typeDesc.contains("COLUMNSTORE")) {
                        ddl.append("CREATE ");
                        if (typeDesc.startsWith("CLUSTERED")) {
                            ddl.append("CLUSTERED ");
                        }
                        ddl.append("COLUMNSTORE INDEX ");
                    } else {
                        ddl.append("CREATE ");
                        if (rs.getBoolean("is_unique")) ddl.append("UNIQUE ");
                        if (typeDesc.startsWith("CLUSTERED")) ddl.append("CLUSTERED ");
                        ddl.append("INDEX ");
                    }

                    String keyColumns = rs.getString("key_columns");
                    String includeCols = rs.getString("include_columns");

                    ddl.append(rs.getString("index_name"))
                            .append(" ON ").append(schema).append(".").append(rs.getString("table_name"));

                    if (typeDesc.contains("COLUMNSTORE")) {
                        // Columnstore indexes list all columns directly
                        String cols = includeCols != null ? includeCols : keyColumns;
                        if (cols != null) {
                            ddl.append(" (").append(cols).append(")");
                        }
                    } else {
                        ddl.append(" (").append(keyColumns).append(")");
                        if (includeCols != null) {
                            ddl.append(" INCLUDE (").append(includeCols).append(")");
                        }
                    }

                    String filter = rs.getString("filter_definition");
                    if (filter != null) {
                        ddl.append(" WHERE ").append(filter);
                    }

                    ddl.append(";\n\n");
                }
            }
        }
    }

    private void extractViews(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT v.name, m.definition
                FROM sys.views v
                JOIN sys.schemas s ON v.schema_id = s.schema_id
                JOIN sys.sql_modules m ON v.object_id = m.object_id
                WHERE s.name = ?
                ORDER BY v.name
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ddl.append(rs.getString("definition").trim()).append(";\n\n");
                }
            }
        }
    }
}
