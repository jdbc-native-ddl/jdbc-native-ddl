package io.github.jdbcnativeddl;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class AbstractDdlExtractorTest {

    protected void assertCommonDdl(String ddl) {
        String upper = ddl.toUpperCase();

        // Tables exist
        assertThat(upper).contains("EMPLOYEES");
        assertThat(upper).contains("DEPARTMENTS");

        // Column types
        assertThat(upper).contains("FIRST_NAME");
        assertThat(upper).contains("LAST_NAME");
        assertThat(upper).contains("EMAIL");
        assertThat(upper).contains("SALARY");
        assertThat(upper).contains("DEPARTMENT_ID");

        // Primary key
        assertThat(upper).contains("PRIMARY KEY");

        // Foreign key
        assertThat(upper).containsAnyOf("FOREIGN KEY", "REFERENCES");

        // Check constraint
        assertThat(upper).contains("CHECK");
        assertThat(upper).contains("SALARY");

        // Index
        assertThat(upper).contains("IDX_EMP_NAME");

        // Sequence
        assertThat(upper).contains("EMP_SEQ");

        // View
        assertThat(upper).contains("ACTIVE_EMPLOYEES");
    }
}
