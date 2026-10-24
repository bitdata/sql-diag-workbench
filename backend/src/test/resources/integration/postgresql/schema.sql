-- backend/src/test/resources/integration/postgresql/schema.sql
-- sqldiag_it — integration test schema, idempotent, isolated from any other testdb objects.

CREATE TABLE IF NOT EXISTS sqldiag_it_customers (
    id          INTEGER PRIMARY KEY,
    name        TEXT NOT NULL,
    region      TEXT NOT NULL,
    created_at  TEXT NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS sqldiag_it_orders (
    id          INTEGER PRIMARY KEY,
    customer_id INTEGER NOT NULL,
    amount      NUMERIC(10,2) NOT NULL,
    status      TEXT NOT NULL,
    created_at  TEXT NOT NULL DEFAULT now()
);

-- Intentionally NO index on sqldiag_it_orders.customer_id → drives Seq Scan + JoinAmplification
-- on cross-joins / large WHERE filters.
