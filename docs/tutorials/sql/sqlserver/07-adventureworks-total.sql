-- 数据来源：Microsoft sql-server-samples 的 AdventureWorks OLTP，读取数据库实际表。
-- 每次只复制一个 SELECT / WITH 查询到工作台；不要整份执行。

-- A：固定一张订单，观察订单头金额被明细复制
SELECT h.SalesOrderID, COUNT(*) AS detail_count,
 MAX(h.TotalDue) AS header_total, SUM(h.TotalDue) AS repeated_total
FROM Sales.SalesOrderHeader h JOIN Sales.SalesOrderDetail d ON d.SalesOrderID = h.SalesOrderID
WHERE h.SalesOrderID = 43659 GROUP BY h.SalesOrderID;

-- B：展开证据，查看每条明细重复的订单头金额
SELECT h.SalesOrderID, d.SalesOrderDetailID, h.TotalDue, d.LineTotal
FROM Sales.SalesOrderHeader h JOIN Sales.SalesOrderDetail d ON d.SalesOrderID = h.SalesOrderID
WHERE h.SalesOrderID = 43659 ORDER BY d.SalesOrderDetailID;

-- C：若业务要有明细订单的应付总额，用 EXISTS 保持订单头粒度
SELECT SUM(h.TotalDue) AS total_due FROM Sales.SalesOrderHeader h
WHERE h.SalesOrderID = 43659 AND EXISTS
 (SELECT 1 FROM Sales.SalesOrderDetail d WHERE d.SalesOrderID = h.SalesOrderID);
