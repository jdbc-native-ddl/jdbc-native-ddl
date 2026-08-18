# JDBC Native DDL

[![CI](https://github.com/jdbc-native-ddl/jdbc-native-ddl/actions/workflows/ci.yml/badge.svg)](https://github.com/jdbc-native-ddl/jdbc-native-ddl/actions/workflows/ci.yml)

Extract DDL from live databases using each database's native catalog views and built-in functions via JDBC.
Produces the most accurate DDL possible, including vendor-specific features like partitioning, expression indexes, 
temporal tables, and more. 100% Java, No dependencies (okay, just one: slf4j-api).

## Quick Start

```java
try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password)) {
    String ddl = Ddl.extract(conn, "MY_SCHEMA");
}
```

## Output Targets

```java
// To a String
String ddl = Ddl.extract(conn, "MY_SCHEMA");

// To a Writer
Ddl.extract(conn, "MY_SCHEMA", new PrintWriter(System.out));

// To a file
Ddl.extract(conn, "MY_SCHEMA", Path.of("schema.sql"));
```

## Spring Boot

```java
@Bean
CommandLineRunner extractDdl(DataSource dataSource) {
    return args -> {
        try (Connection conn = dataSource.getConnection()) {
            Ddl.extract(conn, "MY_SCHEMA", Path.of("schema.sql"));
        }
    };
}
```

## Supported Databases

| Database | Strategy | Key Features |
|----------|----------|-------------|
| **PostgreSQL** | `pg_catalog` queries | Expression indexes, partial indexes, partitioning, enum/domain types, generated columns, materialized views |
| **Oracle** | `DBMS_METADATA.GET_DDL` | Partitioning, function-based indexes, materialized views, sequences |
| **SQL Server** | `sys.*` catalog views | Filtered indexes, included columns, computed columns, temporal tables, columnstore indexes |
| **DB2 LUW** | `SYSCAT.*` catalog views | Identity columns, range partitioning, tablespaces, sequences |
| **IBM i (AS/400)** | `QSYS2.*` catalog views | Identity columns, sequences, check constraints, views |

## Identifier Quoting and Casing

All identifiers (table names, column names, constraints, etc.) are emitted with double quotes to ensure special characters like dashes are handled correctly.

The casing of identifiers reflects what the database catalog actually stores. Most databases fold unquoted identifiers to a canonical case: Oracle, DB2, and AS/400 fold to uppercase, while PostgreSQL folds to lowercase. For example, `CREATE TABLE MyTable` in PostgreSQL is stored as `mytable` and will be extracted as `"mytable"`. This is not a bug -- it is how SQL identifier resolution works.

## Maven

```xml
<dependency>
    <groupId>io.github.jdbc-native-ddl</groupId>
    <artifactId>jdbc-native-ddl</artifactId>
    <version>0.1.9</version>
</dependency>
```

## Gradle

```groovy
implementation 'io.github.jdbc-native-ddl:jdbc-native-ddl:0.1.9'
```

JDBC drivers are **not** included -- provide them in your application's classpath.

## Building & Testing

```bash
mvn clean package          # Build JAR (skips integration tests without Docker)
mvn test                   # Run all integration tests (requires Docker)
mvn test -Dtest=PostgresDdlExtractorTest   # Run a single database test
```

Integration tests use [Testcontainers](https://testcontainers.com/) to spin up real database instances. You need Docker running locally.

## Adding a New Database

1. **Create the extractor** -- implement `DdlExtractor` using the database's native catalog views:

```java
public class MysqlDdlExtractor implements DdlExtractor {
    @Override
    public boolean supports(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.startsWith("jdbc:mysql:");
    }

    @Override
    public String extractDdl(Connection connection, String schema) throws SQLException {
        // Use SHOW CREATE TABLE or information_schema queries
    }
}
```

2. **Register it** in `DdlExtractorFactory`:

```java
private static final List<DdlExtractor> EXTRACTORS = List.of(
        new PostgresDdlExtractor(),
        new OracleDdlExtractor(),
        new MssqlDdlExtractor(),
        new Db2DdlExtractor(),
        new MysqlDdlExtractor()  // add here
);
```

3. **Add integration tests** extending `AbstractDdlExtractorTest` with a Testcontainers container.

4. **Add test dependencies** (Testcontainers module + JDBC driver) to `pom.xml` with test scope.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on how to contribute.

## License

[Apache License 2.0](LICENSE)
