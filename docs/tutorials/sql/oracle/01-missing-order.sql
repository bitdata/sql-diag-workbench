-- 每次只复制一个 SELECT / WITH 查询到工作台；不要整份执行。

-- A：目标是保留全部订单，但 WHERE 排除了没有 east 客户的订单
SELECT o.id, c.region FROM (SELECT 1 AS id, 10 AS customer_id, 100 AS amount FROM dual
 UNION ALL SELECT 2, 10, 40 FROM dual
 UNION ALL SELECT 3, 20, 70 FROM dual
 UNION ALL SELECT 4, 99, 20 FROM dual) o
LEFT JOIN (SELECT 10 AS cid, 'east' AS region FROM dual
 UNION ALL SELECT 20, 'west' FROM dual) c ON o.customer_id = c.cid
WHERE c.region = 'east' ORDER BY o.id;

-- B：保留全部订单，只补充 east 客户信息
SELECT o.id, c.region FROM (SELECT 1 AS id, 10 AS customer_id, 100 AS amount FROM dual
 UNION ALL SELECT 2, 10, 40 FROM dual
 UNION ALL SELECT 3, 20, 70 FROM dual
 UNION ALL SELECT 4, 99, 20 FROM dual) o
LEFT JOIN (SELECT 10 AS cid, 'east' AS region FROM dual
 UNION ALL SELECT 20, 'west' FROM dual) c ON o.customer_id = c.cid AND c.region = 'east'
ORDER BY o.id;
