package io.github.jdbcnativeddl;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Db2Container;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class Db2DdlExtractorTest extends AbstractDdlExtractorTest {

    @Container
    @SuppressWarnings("resource")
    static Db2Container db2 = new Db2Container("icr.io/db2_community/db2:11.5.9.0")
            .acceptLicense()
            .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("tc.db2")));

    private static String ddl;

    @BeforeAll
    static void extractDdl() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                db2.getJdbcUrl(), db2.getUsername(), db2.getPassword())) {
            runInitScript(conn);
        }

        try (Connection conn = DriverManager.getConnection(
                db2.getJdbcUrl(), db2.getUsername(), db2.getPassword())) {
            DdlExtractor extractor = new Db2DdlExtractor();
            ddl = extractor.extractDdl(conn, "DDL_TEST");
            System.out.println("=== DB2 DDL ===");
            System.out.println(ddl);
        }
    }

    private static void runInitScript(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA DDL_TEST");

            stmt.execute("CREATE SEQUENCE DDL_TEST.EMP_SEQ START WITH 1000 INCREMENT BY 1");

            stmt.execute("""
                CREATE TABLE DDL_TEST.DEPARTMENTS (
                    ID INTEGER NOT NULL GENERATED ALWAYS AS IDENTITY,
                    NAME VARCHAR(100) NOT NULL,
                    CONSTRAINT PK_DEPARTMENTS PRIMARY KEY (ID)
                )""");

            stmt.execute("""
                CREATE TABLE DDL_TEST.EMPLOYEES (
                    ID INTEGER NOT NULL,
                    FIRST_NAME VARCHAR(100) NOT NULL,
                    LAST_NAME VARCHAR(100) NOT NULL,
                    EMAIL VARCHAR(255) NOT NULL,
                    SALARY DECIMAL(10,2),
                    DEPARTMENT_ID INTEGER,
                    HIRE_DATE DATE DEFAULT CURRENT DATE,
                    IS_ACTIVE CHAR(1) DEFAULT 'Y' NOT NULL,
                    CONSTRAINT PK_EMPLOYEES PRIMARY KEY (ID),
                    CONSTRAINT UQ_EMP_EMAIL UNIQUE (EMAIL),
                    CONSTRAINT FK_DEPT FOREIGN KEY (DEPARTMENT_ID) REFERENCES DDL_TEST.DEPARTMENTS(ID)
                )""");

            stmt.execute("ALTER TABLE DDL_TEST.EMPLOYEES ADD CONSTRAINT CHK_SALARY CHECK (SALARY > 0)");

            stmt.execute("CREATE INDEX DDL_TEST.IDX_EMP_NAME ON DDL_TEST.EMPLOYEES(LAST_NAME, FIRST_NAME)");

            stmt.execute("""
                CREATE VIEW DDL_TEST.ACTIVE_EMPLOYEES AS
                    SELECT ID, FIRST_NAME, LAST_NAME, EMAIL, DEPARTMENT_ID
                    FROM DDL_TEST.EMPLOYEES
                    WHERE IS_ACTIVE = 'Y'
                """);
        }
    }

    @Test
    void commonDdl() {
        assertCommonDdl(ddl);
    }

    @Test
    void identityColumn() {
        assertThat(ddl.toUpperCase()).contains("GENERATED ALWAYS AS IDENTITY");
    }

    @Test
    void view() {
        assertThat(ddl.toUpperCase()).contains("ACTIVE_EMPLOYEES");
    }

    @Test
    void checkConstraint() {
        assertThat(ddl.toUpperCase()).contains("CHK_SALARY");
        assertThat(ddl.toUpperCase()).contains("CHECK");
    }

    @Test
    void foreignKey() {
        assertThat(ddl.toUpperCase()).contains("FK_DEPT");
        assertThat(ddl.toUpperCase()).contains("FOREIGN KEY");
    }

    @Test
    void uniqueConstraint() {
        assertThat(ddl.toUpperCase()).contains("UQ_EMP_EMAIL");
        assertThat(ddl.toUpperCase()).contains("UNIQUE");
    }

    @Test
    void sequence() {
        assertThat(ddl.toUpperCase()).contains("EMP_SEQ");
    }
}
