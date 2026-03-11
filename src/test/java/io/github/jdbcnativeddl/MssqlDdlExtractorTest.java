package io.github.jdbcnativeddl;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class MssqlDdlExtractorTest extends AbstractDdlExtractorTest {

    @Container
    @SuppressWarnings("resource")
    static MSSQLServerContainer<?> mssql = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
            .acceptLicense()
            .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("tc.mssql")));

    private static String ddl;

    @BeforeAll
    static void extractDdl() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                mssql.getJdbcUrl(), mssql.getUsername(), mssql.getPassword())) {
            runInitScript(conn);
        }

        try (Connection conn = DriverManager.getConnection(
                mssql.getJdbcUrl(), mssql.getUsername(), mssql.getPassword())) {
            DdlExtractor extractor = new MssqlDdlExtractor();
            ddl = extractor.extractDdl(conn, "ddl_test");
            System.out.println("=== SQL Server DDL ===");
            System.out.println(ddl);
        }
    }

    private static void runInitScript(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA ddl_test");

            stmt.execute("CREATE SEQUENCE ddl_test.emp_seq AS BIGINT START WITH 1000 INCREMENT BY 1");

            stmt.execute("""
                CREATE TABLE ddl_test.departments (
                    id INT IDENTITY(1,1),
                    name VARCHAR(100) NOT NULL,
                    CONSTRAINT pk_departments PRIMARY KEY (id)
                )""");

            stmt.execute("""
                CREATE TABLE ddl_test.employees (
                    id INT NOT NULL DEFAULT NEXT VALUE FOR ddl_test.emp_seq,
                    first_name VARCHAR(100) NOT NULL,
                    last_name VARCHAR(100) NOT NULL,
                    email VARCHAR(255),
                    salary DECIMAL(10,2),
                    department_id INT,
                    hire_date DATE DEFAULT GETDATE(),
                    is_active BIT DEFAULT 1,
                    full_name AS (first_name + ' ' + last_name),
                    CONSTRAINT pk_employees PRIMARY KEY (id),
                    CONSTRAINT uq_emp_email UNIQUE (email),
                    CONSTRAINT chk_salary CHECK (salary > 0),
                    CONSTRAINT fk_dept FOREIGN KEY (department_id) REFERENCES ddl_test.departments(id)
                )""");

            stmt.execute("CREATE INDEX idx_emp_name ON ddl_test.employees(last_name, first_name)");
            stmt.execute("CREATE INDEX idx_active ON ddl_test.employees(email) WHERE is_active = 1");
            stmt.execute("CREATE INDEX idx_name_inc ON ddl_test.employees(last_name) INCLUDE (email, salary)");

            stmt.execute("""
                CREATE VIEW ddl_test.active_employees AS
                    SELECT id, first_name, last_name, email, department_id
                    FROM ddl_test.employees
                    WHERE is_active = 1
                """);

            stmt.execute("""
                CREATE TABLE ddl_test.employee_positions (
                    id INT IDENTITY(1,1) NOT NULL,
                    employee_id INT NOT NULL,
                    position_name VARCHAR(100) NOT NULL,
                    sys_start DATETIME2 GENERATED ALWAYS AS ROW START NOT NULL,
                    sys_end DATETIME2 GENERATED ALWAYS AS ROW END NOT NULL,
                    PERIOD FOR SYSTEM_TIME (sys_start, sys_end),
                    CONSTRAINT pk_emp_positions PRIMARY KEY (id)
                ) WITH (SYSTEM_VERSIONING = ON (HISTORY_TABLE = ddl_test.employee_positions_history))
                """);

            stmt.execute("""
                CREATE TABLE ddl_test.sales_archive (
                    id INT IDENTITY(1,1),
                    sale_date DATE NOT NULL,
                    amount DECIMAL(10,2),
                    region VARCHAR(50),
                    CONSTRAINT pk_sales_archive PRIMARY KEY (id)
                )""");

            stmt.execute("CREATE NONCLUSTERED COLUMNSTORE INDEX cci_sales ON ddl_test.sales_archive (sale_date, amount, region)");
        }
    }

    @Test
    void commonDdl() {
        assertCommonDdl(ddl);
    }

    @Test
    void filteredIndex() {
        assertThat(ddl).containsIgnoringCase("idx_active");
        assertThat(ddl).containsIgnoringCase("WHERE");
    }

    @Test
    void includedColumns() {
        assertThat(ddl).containsIgnoringCase("idx_name_inc");
        assertThat(ddl.toUpperCase()).contains("INCLUDE");
    }

    @Test
    void computedColumn() {
        assertThat(ddl).containsIgnoringCase("full_name");
        assertThat(ddl.toUpperCase()).contains(" AS ");
    }

    @Test
    void temporalTable() {
        assertThat(ddl).containsIgnoringCase("employee_positions");
        assertThat(ddl.toUpperCase()).contains("SYSTEM_VERSIONING");
        assertThat(ddl.toUpperCase()).contains("PERIOD FOR SYSTEM_TIME");
    }

    @Test
    void columnstoreIndex() {
        assertThat(ddl.toUpperCase()).contains("COLUMNSTORE");
        assertThat(ddl).containsIgnoringCase("cci_sales");
    }

    @Test
    void view() {
        assertThat(ddl).containsIgnoringCase("active_employees");
    }

    @Test
    void checkConstraint() {
        assertThat(ddl).containsIgnoringCase("chk_salary");
        assertThat(ddl.toUpperCase()).contains("CHECK");
    }

    @Test
    void foreignKey() {
        assertThat(ddl).containsIgnoringCase("fk_dept");
        assertThat(ddl.toUpperCase()).contains("FOREIGN KEY");
    }

    @Test
    void sequence() {
        assertThat(ddl).containsIgnoringCase("emp_seq");
    }

    @Test
    void identityColumn() {
        assertThat(ddl.toUpperCase()).contains("IDENTITY");
    }

    @Test
    void roundtrip() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                mssql.getJdbcUrl(), mssql.getUsername(), mssql.getPassword())) {
            try (Statement s = conn.createStatement()) {
                s.execute("CREATE SCHEMA ddl_roundtrip");
            }
            assertRoundtrip(conn, new MssqlDdlExtractor(), "ddl_test", "ddl_roundtrip", ddl);
        }
    }
}
