-- 数据来源：datacharmer/test_db 的 employees 工资历史；每次只复制一条查询。
-- 两个查询读取同一批工资记录，返回一行汇总；实际计划会执行查询。

-- A：对员工号做运算，观察大表扫描
SELECT COUNT(*) AS salary_rows, SUM(s.salary) AS salary_sum
FROM public.salaries s
WHERE s.emp_no + 0 BETWEEN 10001 AND 10200;

-- B：直接约束索引列，保持相同工资记录范围
SELECT COUNT(*) AS salary_rows, SUM(s.salary) AS salary_sum
FROM public.salaries s
WHERE s.emp_no BETWEEN 10001 AND 10200;
