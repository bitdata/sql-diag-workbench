-- backend/src/test/resources/integration/mysql/schema.sql
-- Uses prefix sqldiag_it_ on user-configured database; no CREATE DATABASE (avoid extra priv).

CREATE TABLE IF NOT EXISTS sqldiag_it_customers (
    id          INT PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    region      VARCHAR(64)  NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sqldiag_it_orders (
    id          INT PRIMARY KEY,
    customer_id INT NOT NULL,
    amount      DECIMAL(10,2) NOT NULL,
    status      VARCHAR(32)  NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
