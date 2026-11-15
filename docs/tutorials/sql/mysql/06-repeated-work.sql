-- 数据来源：datacharmer/test_db 的 employees 样例数据模型，读取数据库实际表。
-- 每次只复制一个 SELECT / WITH 查询到工作台；不要整份执行。

-- A：关联标量子查询；优化器也可能自动改写
SELECT e.emp_no,
 (SELECT MAX(s.salary) FROM employees.salaries s WHERE s.emp_no = e.emp_no) AS max_salary
FROM employees.employees e WHERE e.emp_no BETWEEN 10001 AND 10200 ORDER BY e.emp_no;

-- B：限定同一区间后预聚合，保留无工资记录的员工
SELECT e.emp_no, x.max_salary FROM employees.employees e
LEFT JOIN (SELECT s.emp_no, MAX(s.salary) AS max_salary FROM employees.salaries s
 WHERE s.emp_no BETWEEN 10001 AND 10200 GROUP BY s.emp_no) x ON x.emp_no = e.emp_no
WHERE e.emp_no BETWEEN 10001 AND 10200 ORDER BY e.emp_no;
