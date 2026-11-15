-- 数据来源：datacharmer/test_db 的 employees 样例数据模型，读取数据库实际表。
-- 每次只复制一个 SELECT / WITH 查询到工作台；不要整份执行。

-- A：工资历史和职称历史直接 JOIN，员工会出现多行
SELECT e.emp_no, s.from_date AS salary_from, t.from_date AS title_from
FROM public.employees e JOIN public.salaries s ON s.emp_no = e.emp_no
JOIN public.titles t ON t.emp_no = e.emp_no
WHERE e.emp_no BETWEEN 10001 AND 10005
ORDER BY e.emp_no, s.from_date, t.from_date;

-- B：业务若只要当前工资和职称，则同时约束有效期
SELECT e.emp_no, s.salary, t.title
FROM public.employees e JOIN public.salaries s ON s.emp_no = e.emp_no
JOIN public.titles t ON t.emp_no = e.emp_no
WHERE e.emp_no BETWEEN 10001 AND 10005
AND s.to_date = '9999-01-01' AND t.to_date = '9999-01-01'
ORDER BY e.emp_no;
