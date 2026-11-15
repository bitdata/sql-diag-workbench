-- 每次只复制一个 SELECT / WITH 查询到工作台；不要整份执行。

-- A：业务要每个有标签的订单一行，JOIN 返回了每个标签一行
SELECT o.id FROM (SELECT 1 AS id, 10 AS customer_id, 100 AS amount
 UNION ALL SELECT 2, 10, 40
 UNION ALL SELECT 3, 20, 70
 UNION ALL SELECT 4, 99, 20) o
JOIN (SELECT 1 AS order_id, 'red' AS tag
 UNION ALL SELECT 1, 'blue'
 UNION ALL SELECT 3, 'green') t ON o.id = t.order_id ORDER BY o.id;

-- B：只判断是否有标签，使用 EXISTS
SELECT o.id FROM (SELECT 1 AS id, 10 AS customer_id, 100 AS amount
 UNION ALL SELECT 2, 10, 40
 UNION ALL SELECT 3, 20, 70
 UNION ALL SELECT 4, 99, 20) o
WHERE EXISTS (SELECT 1 FROM (SELECT 1 AS order_id, 'red' AS tag
 UNION ALL SELECT 1, 'blue'
 UNION ALL SELECT 3, 'green') t WHERE t.order_id = o.id) ORDER BY o.id;

-- C：正常对照，业务粒度为订单加标签，两行是合理的
SELECT o.id, t.tag FROM (SELECT 1 AS id, 10 AS customer_id, 100 AS amount
 UNION ALL SELECT 2, 10, 40
 UNION ALL SELECT 3, 20, 70
 UNION ALL SELECT 4, 99, 20) o
JOIN (SELECT 1 AS order_id, 'red' AS tag
 UNION ALL SELECT 1, 'blue'
 UNION ALL SELECT 3, 'green') t ON o.id = t.order_id ORDER BY o.id, t.tag;
