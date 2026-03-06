package io.github.jdbcnativeddl;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class OracleDdlExtractorTest extends AbstractDdlExtractorTest {

    @Container
    static OracleContainer oracle = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
            .withUsername("ddl_test")
            .withPassword("testpassword")
            .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("tc.oracle")));

    private static String ddl;

    @BeforeAll
    static void extractDdl() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                oracle.getJdbcUrl(), oracle.getUsername(), oracle.getPassword())) {
            runInitScript(conn);
        }

        try (Connection conn = DriverManager.getConnection(
                oracle.getJdbcUrl(), oracle.getUsername(), oracle.getPassword())) {
            DdlExtractor extractor = new OracleDdlExtractor();
            ddl = extractor.extractDdl(conn, "DDL_TEST");
            System.out.println("=== Oracle DDL ===");
            System.out.println(ddl);
        }
    }

    private static void runInitScript(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SEQUENCE EMP_SEQ START WITH 1000 INCREMENT BY 1");

            stmt.execute("""
                CREATE TABLE DEPARTMENTS (
                    ID NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    NAME VARCHAR2(100) NOT NULL
                )""");

            stmt.execute("""
                CREATE TABLE EMPLOYEES (
                    ID NUMBER DEFAULT EMP_SEQ.NEXTVAL NOT NULL,
                    FIRST_NAME VARCHAR2(100) NOT NULL,
                    LAST_NAME VARCHAR2(100) NOT NULL,
                    EMAIL VARCHAR2(255),
                    SALARY NUMBER(10,2),
                    DEPARTMENT_ID NUMBER,
                    HIRE_DATE DATE DEFAULT SYSDATE,
                    IS_ACTIVE CHAR(1) DEFAULT 'Y',
                    CONSTRAINT PK_EMPLOYEES PRIMARY KEY (ID),
                    CONSTRAINT UQ_EMP_EMAIL UNIQUE (EMAIL),
                    CONSTRAINT CHK_SALARY CHECK (SALARY > 0),
                    CONSTRAINT CHK_ACTIVE CHECK (IS_ACTIVE IN ('Y', 'N')),
                    CONSTRAINT FK_DEPT FOREIGN KEY (DEPARTMENT_ID) REFERENCES DEPARTMENTS(ID)
                )""");

            stmt.execute("CREATE INDEX IDX_EMP_NAME ON EMPLOYEES(LAST_NAME, FIRST_NAME)");
            stmt.execute("CREATE INDEX IDX_EMP_UPPER ON EMPLOYEES(UPPER(LAST_NAME))");

            stmt.execute("""
                CREATE TABLE SALES (
                    ID NUMBER GENERATED ALWAYS AS IDENTITY,
                    SALE_DATE DATE NOT NULL,
                    AMOUNT NUMBER(10,2),
                    REGION VARCHAR2(50)
                ) PARTITION BY RANGE (SALE_DATE) (
                    PARTITION SALES_2023 VALUES LESS THAN (TO_DATE('2024-01-01', 'YYYY-MM-DD')),
                    PARTITION SALES_2024 VALUES LESS THAN (TO_DATE('2025-01-01', 'YYYY-MM-DD'))
                )""");

            stmt.execute("""
                CREATE VIEW ACTIVE_EMPLOYEES AS
                    SELECT ID, FIRST_NAME, LAST_NAME, EMAIL, DEPARTMENT_ID
                    FROM EMPLOYEES
                    WHERE IS_ACTIVE = 'Y'
                """);

            stmt.execute("""
                CREATE MATERIALIZED VIEW MV_DEPT_SUMMARY AS
                    SELECT D.NAME AS DEPARTMENT_NAME, COUNT(E.ID) AS EMPLOYEE_COUNT
                    FROM DEPARTMENTS D
                    LEFT JOIN EMPLOYEES E ON D.ID = E.DEPARTMENT_ID
                    GROUP BY D.NAME
                """);
        }
    }

    @Test
    void commonDdl() {
        assertCommonDdl(ddl);
    }

    @Test
    void functionBasedIndex() {
        assertThat(ddl.toUpperCase()).contains("UPPER");
        assertThat(ddl.toUpperCase()).contains("IDX_EMP_UPPER");
    }

    @Test
    void partitionedTable() {
        assertThat(ddl.toUpperCase()).contains("PARTITION");
        assertThat(ddl.toUpperCase()).contains("SALES");
    }

    @Test
    void view() {
        assertThat(ddl.toUpperCase()).contains("ACTIVE_EMPLOYEES");
    }

    @Test
    void materializedView() {
        assertThat(ddl.toUpperCase()).contains("MV_DEPT_SUMMARY");
    }

    @Test
    void sequence() {
        assertThat(ddl.toUpperCase()).contains("EMP_SEQ");
    }

    @Test
    void checkConstraint() {
        assertThat(ddl.toUpperCase()).contains("CHK_SALARY");
    }
}
