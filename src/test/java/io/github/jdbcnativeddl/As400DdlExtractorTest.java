package io.github.jdbcnativeddl;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the AS/400 DDL extractor against mock QSYS2 catalog views in H2.
 * This validates SQL correctness and DDL generation logic without a real IBM i system.
 */
class As400DdlExtractorTest extends AbstractDdlExtractorTest {

    private static String ddl;

    @BeforeAll
    static void extractDdl() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:as400test;MODE=DB2")) {
            createMockCatalog(conn);
            populateMockData(conn);

            DdlExtractor extractor = new As400DdlExtractor();
            ddl = extractor.extractDdl(conn, "TESTLIB");
            System.out.println("=== AS/400 (mock) DDL ===");
            System.out.println(ddl);
        }
    }

    private static void createMockCatalog(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA QSYS2");

            stmt.execute("""
                CREATE TABLE QSYS2.SYSSEQUENCES (
                    SEQUENCE_SCHEMA VARCHAR(128),
                    SEQUENCE_NAME VARCHAR(128),
                    START BIGINT,
                    INCREMENT BIGINT,
                    MINIMUM_VALUE BIGINT,
                    MAXIMUM_VALUE BIGINT,
                    CACHE INT,
                    CYCLE_OPTION VARCHAR(3),
                    DATA_TYPE VARCHAR(128)
                )""");

            stmt.execute("""
                CREATE TABLE QSYS2.SYSTABLES (
                    TABLE_SCHEMA VARCHAR(128),
                    TABLE_NAME VARCHAR(128),
                    TABLE_TYPE CHAR(1)
                )""");

            stmt.execute("""
                CREATE TABLE QSYS2.SYSCOLUMNS (
                    TABLE_SCHEMA VARCHAR(128),
                    TABLE_NAME VARCHAR(128),
                    COLUMN_NAME VARCHAR(128),
                    DATA_TYPE VARCHAR(128),
                    LENGTH INT,
                    NUMERIC_SCALE INT,
                    IS_NULLABLE CHAR(1),
                    COLUMN_DEFAULT VARCHAR(256),
                    IS_IDENTITY VARCHAR(3),
                    IDENTITY_GENERATION VARCHAR(10),
                    ORDINAL_POSITION INT
                )""");

            stmt.execute("""
                CREATE TABLE QSYS2.SYSCST (
                    CONSTRAINT_SCHEMA VARCHAR(128),
                    CONSTRAINT_NAME VARCHAR(128),
                    TABLE_SCHEMA VARCHAR(128),
                    TABLE_NAME VARCHAR(128),
                    CONSTRAINT_TYPE VARCHAR(11)
                )""");

            stmt.execute("""
                CREATE TABLE QSYS2.SYSKEYCST (
                    CONSTRAINT_SCHEMA VARCHAR(128),
                    CONSTRAINT_NAME VARCHAR(128),
                    COLUMN_NAME VARCHAR(128),
                    ORDINAL_POSITION INT
                )""");

            stmt.execute("""
                CREATE TABLE QSYS2.SYSCHKCST (
                    CONSTRAINT_SCHEMA VARCHAR(128),
                    CONSTRAINT_NAME VARCHAR(128),
                    CHECK_CLAUSE VARCHAR(2000)
                )""");

            stmt.execute("""
                CREATE TABLE QSYS2.SYSREFCST (
                    CONSTRAINT_SCHEMA VARCHAR(128),
                    CONSTRAINT_NAME VARCHAR(128),
                    UNIQUE_CONSTRAINT_SCHEMA VARCHAR(128),
                    UNIQUE_CONSTRAINT_NAME VARCHAR(128)
                )""");

            stmt.execute("""
                CREATE TABLE QSYS2.SYSINDEXES (
                    INDEX_SCHEMA VARCHAR(128),
                    INDEX_NAME VARCHAR(128),
                    TABLE_SCHEMA VARCHAR(128),
                    TABLE_NAME VARCHAR(128),
                    IS_UNIQUE CHAR(1)
                )""");

            stmt.execute("""
                CREATE TABLE QSYS2.SYSKEYS (
                    INDEX_SCHEMA VARCHAR(128),
                    INDEX_NAME VARCHAR(128),
                    COLUMN_NAME VARCHAR(128),
                    ORDINAL_POSITION INT,
                    ORDERING CHAR(1),
                    KEY_EXPRESSION VARCHAR(2000)
                )""");

            stmt.execute("""
                CREATE TABLE QSYS2.SYSPARTITIONINDEXES (
                    INDEX_SCHEMA VARCHAR(128),
                    INDEX_NAME VARCHAR(128),
                    TABLE_SCHEMA VARCHAR(128),
                    TABLE_NAME VARCHAR(128),
                    INDEX_TYPE VARCHAR(20),
                    "UNIQUE" VARCHAR(21)
                )""");

            stmt.execute("""
                CREATE TABLE QSYS2.SYSVIEWS (
                    TABLE_SCHEMA VARCHAR(128),
                    TABLE_NAME VARCHAR(128),
                    VIEW_DEFINITION VARCHAR(4000)
                )""");

            stmt.execute("CREATE SCHEMA QSYS");

            stmt.execute("""
                CREATE TABLE QSYS.QADBKFLD (
                    DBKLIB VARCHAR(128),
                    DBKFIL VARCHAR(128),
                    DBKFLD VARCHAR(128),
                    DBKPOS INT,
                    DBKORD CHAR(1)
                )""");
        }
    }

    private static void populateMockData(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            // Sequence
            stmt.execute("""
                INSERT INTO QSYS2.SYSSEQUENCES VALUES
                ('TESTLIB', 'EMP_SEQ', 1000, 1, 1, 999999999, 20, 'NO', 'INTEGER')
                """);

            // Tables
            stmt.execute("INSERT INTO QSYS2.SYSTABLES VALUES ('TESTLIB', 'DEPARTMENTS', 'T')");
            stmt.execute("INSERT INTO QSYS2.SYSTABLES VALUES ('TESTLIB', 'EMPLOYEES', 'T')");

            // Departments columns
            stmt.execute("""
                INSERT INTO QSYS2.SYSCOLUMNS VALUES
                ('TESTLIB', 'DEPARTMENTS', 'ID', 'INTEGER', 4, 0, 'N', NULL, 'YES', 'ALWAYS', 1)
                """);
            stmt.execute("""
                INSERT INTO QSYS2.SYSCOLUMNS VALUES
                ('TESTLIB', 'DEPARTMENTS', 'NAME', 'VARCHAR', 100, 0, 'N', NULL, 'NO', NULL, 2)
                """);

            // Employees columns
            stmt.execute("""
                INSERT INTO QSYS2.SYSCOLUMNS VALUES
                ('TESTLIB', 'EMPLOYEES', 'ID', 'INTEGER', 4, 0, 'N', NULL, 'NO', NULL, 1)
                """);
            stmt.execute("""
                INSERT INTO QSYS2.SYSCOLUMNS VALUES
                ('TESTLIB', 'EMPLOYEES', 'FIRST_NAME', 'VARCHAR', 100, 0, 'N', NULL, 'NO', NULL, 2)
                """);
            stmt.execute("""
                INSERT INTO QSYS2.SYSCOLUMNS VALUES
                ('TESTLIB', 'EMPLOYEES', 'LAST_NAME', 'VARCHAR', 100, 0, 'N', NULL, 'NO', NULL, 3)
                """);
            stmt.execute("""
                INSERT INTO QSYS2.SYSCOLUMNS VALUES
                ('TESTLIB', 'EMPLOYEES', 'EMAIL', 'VARCHAR', 255, 0, 'N', NULL, 'NO', NULL, 4)
                """);
            stmt.execute("""
                INSERT INTO QSYS2.SYSCOLUMNS VALUES
                ('TESTLIB', 'EMPLOYEES', 'SALARY', 'DECIMAL', 10, 2, 'Y', NULL, 'NO', NULL, 5)
                """);
            stmt.execute("""
                INSERT INTO QSYS2.SYSCOLUMNS VALUES
                ('TESTLIB', 'EMPLOYEES', 'DEPARTMENT_ID', 'INTEGER', 4, 0, 'Y', NULL, 'NO', NULL, 6)
                """);

            // PK constraints
            stmt.execute("INSERT INTO QSYS2.SYSCST VALUES ('TESTLIB', 'PK_DEPARTMENTS', 'TESTLIB', 'DEPARTMENTS', 'PRIMARY KEY')");
            stmt.execute("INSERT INTO QSYS2.SYSKEYCST VALUES ('TESTLIB', 'PK_DEPARTMENTS', 'ID', 1)");

            stmt.execute("INSERT INTO QSYS2.SYSCST VALUES ('TESTLIB', 'PK_EMPLOYEES', 'TESTLIB', 'EMPLOYEES', 'PRIMARY KEY')");
            stmt.execute("INSERT INTO QSYS2.SYSKEYCST VALUES ('TESTLIB', 'PK_EMPLOYEES', 'ID', 1)");

            // Unique constraint
            stmt.execute("INSERT INTO QSYS2.SYSCST VALUES ('TESTLIB', 'UQ_EMP_EMAIL', 'TESTLIB', 'EMPLOYEES', 'UNIQUE')");
            stmt.execute("INSERT INTO QSYS2.SYSKEYCST VALUES ('TESTLIB', 'UQ_EMP_EMAIL', 'EMAIL', 1)");

            // Check constraint
            stmt.execute("INSERT INTO QSYS2.SYSCST VALUES ('TESTLIB', 'CHK_SALARY', 'TESTLIB', 'EMPLOYEES', 'CHECK')");
            stmt.execute("INSERT INTO QSYS2.SYSCHKCST VALUES ('TESTLIB', 'CHK_SALARY', 'SALARY > 0')");

            // Foreign key
            stmt.execute("INSERT INTO QSYS2.SYSCST VALUES ('TESTLIB', 'FK_DEPT', 'TESTLIB', 'EMPLOYEES', 'FOREIGN KEY')");
            stmt.execute("INSERT INTO QSYS2.SYSKEYCST VALUES ('TESTLIB', 'FK_DEPT', 'DEPARTMENT_ID', 1)");
            stmt.execute("INSERT INTO QSYS2.SYSREFCST VALUES ('TESTLIB', 'FK_DEPT', 'TESTLIB', 'PK_DEPARTMENTS')");

            // Index (SQL-created — present in both SYSPARTITIONINDEXES and SYSKEYS)
            stmt.execute("INSERT INTO QSYS2.SYSINDEXES VALUES ('TESTLIB', 'IDX_EMP_NAME', 'TESTLIB', 'EMPLOYEES', 'D')");
            stmt.execute("INSERT INTO QSYS2.SYSPARTITIONINDEXES VALUES ('TESTLIB', 'IDX_EMP_NAME', 'TESTLIB', 'EMPLOYEES', 'INDEX', 'NON-UNIQUE')");
            stmt.execute("INSERT INTO QSYS2.SYSKEYS VALUES ('TESTLIB', 'IDX_EMP_NAME', 'LAST_NAME', 1, 'A', NULL)");
            stmt.execute("INSERT INTO QSYS2.SYSKEYS VALUES ('TESTLIB', 'IDX_EMP_NAME', 'FIRST_NAME', 2, 'A', NULL)");

            // Expression-based index (SQL-created — present in both SYSPARTITIONINDEXES and SYSKEYS)
            stmt.execute("INSERT INTO QSYS2.SYSINDEXES VALUES ('TESTLIB', 'IDX_EMP_LOWER_NAME', 'TESTLIB', 'EMPLOYEES', 'D')");
            stmt.execute("INSERT INTO QSYS2.SYSPARTITIONINDEXES VALUES ('TESTLIB', 'IDX_EMP_LOWER_NAME', 'TESTLIB', 'EMPLOYEES', 'INDEX', 'NON-UNIQUE')");
            stmt.execute("INSERT INTO QSYS2.SYSKEYS VALUES ('TESTLIB', 'IDX_EMP_LOWER_NAME', 'IXCOL00001', 1, 'A', 'LOWER(FIRST_NAME)')");
            stmt.execute("INSERT INTO QSYS2.SYSKEYS VALUES ('TESTLIB', 'IDX_EMP_LOWER_NAME', 'LAST_NAME', 2, 'A', NULL)");

            // DDS logical file index (only in SYSPARTITIONINDEXES + QADBKFLD, NOT in SYSINDEXES/SYSKEYS)
            stmt.execute("INSERT INTO QSYS2.SYSPARTITIONINDEXES VALUES ('TESTLIB', 'LF_DEPT_NAME', 'TESTLIB', 'DEPARTMENTS', 'LOGICAL', 'NON-UNIQUE')");
            stmt.execute("INSERT INTO QSYS.QADBKFLD VALUES ('TESTLIB', 'LF_DEPT_NAME', 'NAME', 1, 'A')");

            // Cross-library DDS index (index lives in OTHERLIB, table in TESTLIB)
            stmt.execute("INSERT INTO QSYS2.SYSPARTITIONINDEXES VALUES ('OTHERLIB', 'LF_EMP_SALARY', 'TESTLIB', 'EMPLOYEES', 'LOGICAL', 'NON-UNIQUE')");
            stmt.execute("INSERT INTO QSYS.QADBKFLD VALUES ('OTHERLIB', 'LF_EMP_SALARY', 'SALARY', 1, 'D')");

            // View
            stmt.execute("""
                INSERT INTO QSYS2.SYSVIEWS VALUES
                ('TESTLIB', 'ACTIVE_EMPLOYEES',
                 'SELECT ID, FIRST_NAME, LAST_NAME, EMAIL FROM TESTLIB.EMPLOYEES')
                """);
        }
    }

    @Test
    void commonDdl() {
        assertCommonDdl(ddl);
    }

    @Test
    void tables() {
        assertThat(ddl).contains("CREATE TABLE \"DEPARTMENTS\"");
        assertThat(ddl).contains("CREATE TABLE \"EMPLOYEES\"");
    }

    @Test
    void columns() {
        assertThat(ddl).contains("\"FIRST_NAME\"");
        assertThat(ddl).contains("\"LAST_NAME\"");
        assertThat(ddl).contains("\"EMAIL\"");
        assertThat(ddl).contains("\"SALARY\"");
        assertThat(ddl).contains("\"DEPARTMENT_ID\"");
    }

    @Test
    void columnTypes() {
        assertThat(ddl).contains("VARCHAR(100)");
        assertThat(ddl).contains("DECIMAL(10,2)");
    }

    @Test
    void identityColumn() {
        assertThat(ddl.toUpperCase()).contains("GENERATED ALWAYS AS IDENTITY");
    }

    @Test
    void primaryKey() {
        assertThat(ddl.toUpperCase()).contains("PRIMARY KEY");
        assertThat(ddl).contains("\"PK_EMPLOYEES\"");
        assertThat(ddl).contains("\"PK_DEPARTMENTS\"");
    }

    @Test
    void uniqueConstraint() {
        assertThat(ddl).contains("\"UQ_EMP_EMAIL\"");
        assertThat(ddl.toUpperCase()).contains("UNIQUE");
    }

    @Test
    void checkConstraint() {
        assertThat(ddl).contains("\"CHK_SALARY\"");
        assertThat(ddl.toUpperCase()).contains("CHECK");
        assertThat(ddl.toUpperCase()).contains("SALARY > 0");
    }

    @Test
    void foreignKey() {
        assertThat(ddl).contains("\"FK_DEPT\"");
        assertThat(ddl.toUpperCase()).contains("FOREIGN KEY");
        assertThat(ddl).contains("REFERENCES \"DEPARTMENTS\"");
    }

    @Test
    void index() {
        assertThat(ddl).contains("\"IDX_EMP_NAME\"");
        assertThat(ddl.toUpperCase()).contains("CREATE INDEX");
    }

    @Test
    void ddsLogicalFileIndex() {
        assertThat(ddl).contains("CREATE INDEX \"LF_DEPT_NAME\" ON \"DEPARTMENTS\" (\"NAME\")");
    }

    @Test
    void crossLibraryIndexHasSourceComment() {
        assertThat(ddl).contains("-- Source: OTHERLIB.LF_EMP_SALARY");
        assertThat(ddl).contains("CREATE INDEX \"LF_EMP_SALARY\" ON \"EMPLOYEES\" (\"SALARY\" DESC)");
    }

    @Test
    void expressionBasedIndex() {
        assertThat(ddl).contains("\"IDX_EMP_LOWER_NAME\"");
        assertThat(ddl).contains("\"IXCOL00001\" /* LOWER(FIRST_NAME) */");
    }

    @Test
    void createSchema() {
        assertThat(ddl).contains("CREATE SCHEMA \"TESTLIB\"");
    }

    @Test
    void sequence() {
        assertThat(ddl).contains("\"EMP_SEQ\"");
        assertThat(ddl.toUpperCase()).contains("CREATE SEQUENCE");
    }

    @Test
    void view() {
        assertThat(ddl).contains("CREATE VIEW \"ACTIVE_EMPLOYEES\"");
    }
}
