CREATE SCHEMA ddl_test;
SET search_path TO ddl_test;

-- Custom types
CREATE TYPE ddl_test.mood AS ENUM ('happy', 'sad', 'neutral');
CREATE DOMAIN ddl_test.email_address AS VARCHAR(255) CHECK (VALUE ~ '^.+@.+$');

-- Sequences
CREATE SEQUENCE ddl_test.emp_seq START WITH 1000 INCREMENT BY 1;

-- Departments table
CREATE TABLE ddl_test.departments (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL
);

-- Employees table
CREATE TABLE ddl_test.employees (
    id INTEGER NOT NULL DEFAULT nextval('ddl_test.emp_seq'),
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    email VARCHAR(255),
    salary DECIMAL(10,2),
    department_id INTEGER,
    hire_date DATE DEFAULT CURRENT_DATE,
    is_active BOOLEAN DEFAULT true,
    full_name VARCHAR(201) GENERATED ALWAYS AS (first_name || ' ' || last_name) STORED,
    CONSTRAINT pk_employees PRIMARY KEY (id),
    CONSTRAINT uq_emp_email UNIQUE (email),
    CONSTRAINT chk_salary CHECK (salary > 0),
    CONSTRAINT fk_dept FOREIGN KEY (department_id) REFERENCES ddl_test.departments(id)
);

-- Composite index
CREATE INDEX idx_emp_name ON ddl_test.employees(last_name, first_name);

-- Expression index
CREATE INDEX idx_emp_upper ON ddl_test.employees(UPPER(last_name));

-- Partial index
CREATE INDEX idx_active ON ddl_test.employees(email) WHERE is_active = true;

-- Partitioned table
CREATE TABLE ddl_test.sales (
    id SERIAL,
    sale_date DATE NOT NULL,
    amount DECIMAL(10,2),
    region VARCHAR(50)
) PARTITION BY RANGE (sale_date);

CREATE TABLE ddl_test.sales_2023 PARTITION OF ddl_test.sales
    FOR VALUES FROM ('2023-01-01') TO ('2024-01-01');
CREATE TABLE ddl_test.sales_2024 PARTITION OF ddl_test.sales
    FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');

-- View
CREATE VIEW ddl_test.active_employees AS
    SELECT id, first_name, last_name, email, department_id
    FROM ddl_test.employees
    WHERE is_active = true;

-- Materialized view
CREATE MATERIALIZED VIEW ddl_test.dept_summary AS
    SELECT d.name AS department_name, COUNT(e.id) AS employee_count
    FROM ddl_test.departments d
    LEFT JOIN ddl_test.employees e ON d.id = e.department_id
    GROUP BY d.name;
