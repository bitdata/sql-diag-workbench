-- 数据来源：datacharmer/test_db 的 employees 样例数据模型，读取数据库实际表。
-- 每次只复制一个 SELECT / WITH 查询到工作台；不要整份执行。

-- A：对索引列做表达式运算，观察访问方式
SELECT e.emp_no, e.first_name, e.last_name FROM employees.employees e WHERE e.emp_no + 0 = 10001;

-- B：等价的直接等值条件；是否更快以计划和测量为准
SELECT e.emp_no, e.first_name, e.last_name FROM employees.employees e WHERE e.emp_no = 10001;
