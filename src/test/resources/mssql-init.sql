-- Create test schema
CREATE SCHEMA ddl_test;
GO

-- Sequence
CREATE SEQUENCE ddl_test.emp_seq AS BIGINT START WITH 1000 INCREMENT BY 1;
GO

-- Departments table
CREATE TABLE ddl_test.departments (
    id INT IDENTITY(1,1),
    name VARCHAR(100) NOT NULL,
    CONSTRAINT pk_departments PRIMARY KEY (id)
);
GO

-- Employees table
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
);
GO

-- Composite index
CREATE INDEX idx_emp_name ON ddl_test.employees(last_name, first_name);
GO

-- Filtered index
CREATE INDEX idx_active ON ddl_test.employees(email) WHERE is_active = 1;
GO

-- Index with included columns
CREATE INDEX idx_name_inc ON ddl_test.employees(last_name) INCLUDE (email, salary);
GO

-- View
CREATE VIEW ddl_test.active_employees AS
    SELECT id, first_name, last_name, email, department_id
    FROM ddl_test.employees
    WHERE is_active = 1;
GO

-- Temporal table
CREATE TABLE ddl_test.employee_positions (
    id INT IDENTITY(1,1) NOT NULL,
    employee_id INT NOT NULL,
    position_name VARCHAR(100) NOT NULL,
    sys_start DATETIME2 GENERATED ALWAYS AS ROW START NOT NULL,
    sys_end DATETIME2 GENERATED ALWAYS AS ROW END NOT NULL,
    PERIOD FOR SYSTEM_TIME (sys_start, sys_end),
    CONSTRAINT pk_emp_positions PRIMARY KEY (id)
) WITH (SYSTEM_VERSIONING = ON (HISTORY_TABLE = ddl_test.employee_positions_history));
GO

-- Sales archive with columnstore index
CREATE TABLE ddl_test.sales_archive (
    id INT IDENTITY(1,1),
    sale_date DATE NOT NULL,
    amount DECIMAL(10,2),
    region VARCHAR(50),
    CONSTRAINT pk_sales_archive PRIMARY KEY (id)
);
GO

CREATE NONCLUSTERED COLUMNSTORE INDEX cci_sales ON ddl_test.sales_archive (sale_date, amount, region);
GO
