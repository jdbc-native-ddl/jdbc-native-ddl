package io.github.jdbcnativeddl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** PostgreSQL DDL extractor using {@code pg_catalog} queries. */
public class PostgresDdlExtractor implements DdlExtractor {

    private static final Logger log = LoggerFactory.getLogger(PostgresDdlExtractor.class);

    @Override
    public boolean supports(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.startsWith("jdbc:postgresql:");
    }

    @Override
    public String extractDdl(Connection connection, String schema) throws SQLException {
        StringBuilder ddl = new StringBuilder();
        String schemaLower = schema.toLowerCase();

        extractCustomTypes(connection, schemaLower, ddl);
        extractSequences(connection, schemaLower, ddl);
        extractTables(connection, schemaLower, ddl);
        extractIndexes(connection, schemaLower, ddl);
        extractViews(connection, schemaLower, ddl);
        extractMaterializedViews(connection, schemaLower, ddl);

        return ddl.toString();
    }

    private void extractCustomTypes(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        // Enum types
        String sql = """
                SELECT t.typname, e.enumlabel
                FROM pg_type t
                JOIN pg_enum e ON t.oid = e.enumtypid
                JOIN pg_namespace n ON t.typnamespace = n.oid
                WHERE n.nspname = ?
                ORDER BY t.typname, e.enumsortorder
                """;
        Map<String, List<String>> enums = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    enums.computeIfAbsent(rs.getString("typname"), k -> new ArrayList<>())
                            .add(rs.getString("enumlabel"));
                }
            }
        }
        for (var entry : enums.entrySet()) {
            ddl.append("CREATE TYPE ").append(entry.getKey()).append(" AS ENUM (");
            ddl.append(String.join(", ", entry.getValue().stream().map(v -> "'" + v + "'").toList()));
            ddl.append(");\n\n");
        }

        // Domain types
        sql = """
                SELECT t.typname, pg_catalog.format_type(t.typbasetype, t.typtypmod) AS base_type,
                       pg_catalog.pg_get_constraintdef(c.oid) AS check_clause
                FROM pg_type t
                JOIN pg_namespace n ON t.typnamespace = n.oid
                LEFT JOIN pg_constraint c ON c.contypid = t.oid
                WHERE n.nspname = ? AND t.typtype = 'd'
                ORDER BY t.typname
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ddl.append("CREATE DOMAIN ").append(rs.getString("typname"))
                            .append(" AS ").append(rs.getString("base_type"));
                    String check = rs.getString("check_clause");
                    if (check != null) {
                        ddl.append(" ").append(check);
                    }
                    ddl.append(";\n\n");
                }
            }
        }
    }

    private void extractSequences(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT sequencename, start_value, increment_by, min_value, max_value, cache_size, cycle
                FROM pg_sequences
                WHERE schemaname = ?
                ORDER BY sequencename
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ddl.append("CREATE SEQUENCE ").append(rs.getString("sequencename"));
                    ddl.append(" START WITH ").append(rs.getLong("start_value"));
                    ddl.append(" INCREMENT BY ").append(rs.getLong("increment_by"));
                    ddl.append(" MINVALUE ").append(rs.getLong("min_value"));
                    ddl.append(" MAXVALUE ").append(rs.getLong("max_value"));
                    ddl.append(" CACHE ").append(rs.getLong("cache_size"));
                    if (rs.getBoolean("cycle")) {
                        ddl.append(" CYCLE");
                    }
                    ddl.append(";\n\n");
                }
            }
        }
    }

    private void extractTables(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        // Get all tables (including partitioned)
        String tablesSql = """
                SELECT c.relname, c.relkind,
                       pg_get_partkeydef(c.oid) AS partition_key
                FROM pg_class c
                JOIN pg_namespace n ON c.relnamespace = n.oid
                WHERE n.nspname = ? AND c.relkind IN ('r', 'p')
                  AND c.relname NOT LIKE 'pg_%'
                  AND NOT EXISTS (SELECT 1 FROM pg_inherits i WHERE i.inhrelid = c.oid)
                ORDER BY c.relname
                """;
        List<String[]> tables = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(tablesSql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(new String[]{
                            rs.getString("relname"),
                            rs.getString("relkind"),
                            rs.getString("partition_key")
                    });
                }
            }
        }

        for (String[] table : tables) {
            String tableName = table[0];
            String relkind = table[1];
            String partitionKey = table[2];

            ddl.append("CREATE TABLE ").append(tableName).append(" (\n");
            extractColumns(connection, schema, tableName, ddl);
            extractTableConstraints(connection, schema, tableName, ddl);
            ddl.append("\n)");

            if ("p".equals(relkind) && partitionKey != null) {
                ddl.append(" PARTITION BY ").append(partitionKey);
            }

            ddl.append(";\n\n");
        }

        // Partition children
        String partChildSql = """
                SELECT c.relname AS child,
                       pg_get_expr(c.relpartbound, c.oid) AS bound_spec,
                       p.relname AS parent
                FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                JOIN pg_class p ON p.oid = i.inhparent
                JOIN pg_namespace n ON c.relnamespace = n.oid
                WHERE n.nspname = ? AND p.relkind = 'p'
                ORDER BY c.relname
                """;
        try (PreparedStatement ps = connection.prepareStatement(partChildSql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ddl.append("CREATE TABLE ").append(rs.getString("child"))
                            .append(" PARTITION OF ").append(rs.getString("parent"))
                            .append(" ").append(rs.getString("bound_spec"))
                            .append(";\n\n");
                }
            }
        }
    }

    private void extractColumns(Connection connection, String schema, String tableName, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod) AS data_type,
                       a.attnotnull, pg_get_expr(d.adbin, d.adrelid) AS default_value,
                       a.attgenerated
                FROM pg_attribute a
                JOIN pg_class c ON a.attrelid = c.oid
                JOIN pg_namespace n ON c.relnamespace = n.oid
                LEFT JOIN pg_attrdef d ON a.attrelid = d.adrelid AND a.attnum = d.adnum
                WHERE n.nspname = ? AND c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped
                ORDER BY a.attnum
                """;
        boolean first = true;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (!first) ddl.append(",\n");
                    first = false;

                    ddl.append("    ").append(rs.getString("attname"))
                            .append(" ").append(rs.getString("data_type"));

                    String generated = rs.getString("attgenerated");
                    if ("s".equals(generated)) {
                        ddl.append(" GENERATED ALWAYS AS (")
                                .append(rs.getString("default_value"))
                                .append(") STORED");
                    } else {
                        String defaultVal = rs.getString("default_value");
                        if (defaultVal != null) {
                            ddl.append(" DEFAULT ").append(defaultVal);
                        }
                    }

                    if (rs.getBoolean("attnotnull")) {
                        ddl.append(" NOT NULL");
                    }
                }
            }
        }
    }

    private void extractTableConstraints(Connection connection, String schema, String tableName, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT conname, contype, pg_get_constraintdef(c.oid) AS def
                FROM pg_constraint c
                JOIN pg_class t ON c.conrelid = t.oid
                JOIN pg_namespace n ON t.relnamespace = n.oid
                WHERE n.nspname = ? AND t.relname = ?
                ORDER BY
                    CASE contype WHEN 'p' THEN 1 WHEN 'u' THEN 2 WHEN 'f' THEN 3 WHEN 'c' THEN 4 END,
                    conname
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ddl.append(",\n    CONSTRAINT ").append(rs.getString("conname"))
                            .append(" ").append(rs.getString("def"));
                }
            }
        }
    }

    private void extractIndexes(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT indexname, indexdef
                FROM pg_indexes
                WHERE schemaname = ?
                  AND indexname NOT IN (
                      SELECT conname FROM pg_constraint c
                      JOIN pg_class t ON c.conrelid = t.oid
                      JOIN pg_namespace n ON t.relnamespace = n.oid
                      WHERE n.nspname = ? AND contype IN ('p', 'u')
                  )
                ORDER BY tablename, indexname
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ddl.append(rs.getString("indexdef")).append(";\n\n");
                }
            }
        }
    }

    private void extractViews(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT viewname, definition
                FROM pg_views
                WHERE schemaname = ?
                ORDER BY viewname
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ddl.append("CREATE VIEW ").append(rs.getString("viewname"))
                            .append(" AS\n").append(rs.getString("definition")).append(";\n\n");
                }
            }
        }
    }

    private void extractMaterializedViews(Connection connection, String schema, StringBuilder ddl) throws SQLException {
        String sql = """
                SELECT c.relname, pg_get_viewdef(c.oid, true) AS definition
                FROM pg_class c
                JOIN pg_namespace n ON c.relnamespace = n.oid
                WHERE n.nspname = ? AND c.relkind = 'm'
                ORDER BY c.relname
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ddl.append("CREATE MATERIALIZED VIEW ").append(rs.getString("relname"))
                            .append(" AS\n").append(rs.getString("definition")).append(";\n\n");
                }
            }
        }
    }
}
