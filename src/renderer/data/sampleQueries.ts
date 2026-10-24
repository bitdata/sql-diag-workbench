export const SAMPLE_QUERY = `-- Sample: Why does this query return 0 rows?
-- orders, line_items, products — find high-value electronics orders

SELECT o.order_id, o.customer_name, p.product_name, li.quantity
FROM orders o
JOIN line_items li ON o.order_id = li.order_id
JOIN products p ON li.product_id = p.product_id
WHERE o.status = 'completed'
  AND p.category = 'Electronics'
  AND o.order_date > '2024-06-01'
  AND p.price > 1000`

export const SAMPLE_QUERY_SIMPLE = `-- Simple: select all active users
SELECT id, name, email, created_at
FROM users
WHERE status = 'active'
ORDER BY created_at DESC`
