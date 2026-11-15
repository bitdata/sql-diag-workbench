-- 每次只复制一个 SELECT / WITH 查询到工作台；不要整份执行。

-- A：标签 JOIN 把订单金额重复累加
SELECT o.customer_id, SUM(o.amount) AS total FROM (SELECT 1 AS id, 10 AS customer_id, 100 AS amount
 UNION ALL SELECT 2, 10, 40
 UNION ALL SELECT 3, 20, 70
 UNION ALL SELECT 4, 99, 20) o
JOIN (SELECT 1 AS order_id, 'red' AS tag
 UNION ALL SELECT 1, 'blue'
 UNION ALL SELECT 3, 'green') t ON o.id = t.order_id
GROUP BY o.customer_id ORDER BY o.customer_id;

-- B：有标签订单的客户金额，先用 EXISTS 保持订单粒度
SELECT o.customer_id, SUM(o.amount) AS total FROM (SELECT 1 AS id, 10 AS customer_id, 100 AS amount
 UNION ALL SELECT 2, 10, 40
 UNION ALL SELECT 3, 20, 70
 UNION ALL SELECT 4, 99, 20) o
WHERE EXISTS (SELECT 1 FROM (SELECT 1 AS order_id, 'red' AS tag
 UNION ALL SELECT 1, 'blue'
 UNION ALL SELECT 3, 'green') t WHERE t.order_id = o.id)
GROUP BY o.customer_id ORDER BY o.customer_id;
