# Contributing

Thanks for your interest in contributing to JDBC Native DDL!

## Getting Started

1. Fork the repository
2. Clone your fork: `git clone git@github.com:YOUR_USERNAME/jdbc-native-ddl.git`
3. Create a branch: `git checkout -b my-feature`
4. Make your changes
5. Run tests: `mvn clean verify` (requires Docker)
6. Push and open a pull request

## Requirements

- Java 17+
- Maven 3.8+
- Docker (for integration tests)

## Running Tests

All integration tests use Testcontainers to spin up real database instances:

```bash
mvn test                                    # Run all tests
mvn test -Dtest=PostgresDdlExtractorTest    # Run a single database
```

Tests take a few minutes on first run while Docker images are pulled.

## Adding a New Database

See the "Adding a New Database" section in the [README](README.md). In short:

1. Implement `DdlExtractor` using the database's native catalog views
2. Register it in `DdlExtractorFactory`
3. Add integration tests extending `AbstractDdlExtractorTest`
4. Include a **roundtrip test**: extract DDL, execute it in a fresh schema, then verify the resulting structure matches the original. See existing `roundtrip()` tests for examples.
5. Add Testcontainers + JDBC driver dependencies to `pom.xml` (test scope)

## Code Style

- Use the database's native metadata facilities (catalog views, built-in DDL functions) rather than generic JDBC metadata
- Use `PreparedStatement` with `?` placeholders -- never concatenate schema names into SQL
- Keep extractors self-contained -- each database implementation should be independent
- No Javadoc on internals or tests; Javadoc on public API only

## Pull Requests

- Keep PRs focused -- one feature or fix per PR
- Include tests for new functionality
- Make sure `mvn clean verify` passes before submitting
- Write a clear PR description explaining what and why

## Reporting Issues

Open an issue with:
- What you expected to happen
- What actually happened
- Database type and version
- Minimal reproduction steps if possible
