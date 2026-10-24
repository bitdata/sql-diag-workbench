-- backend/src/test/resources/integration/postgresql/seed.sql
-- Resets to known counts: 5 customers, 20 orders. Re-runnable.

TRUNCATE TABLE sqldiag_it_orders;
TRUNCATE TABLE sqldiag_it_customers;

INSERT INTO sqldiag_it_customers (id, name, region) VALUES
    (1, 'Alice', 'us-east'),
    (2, 'Bob',   'us-east'),
    (3, 'Carol', 'eu-west'),
    (4, 'Dave',  'us-west'),
    (5, 'Eve',   'eu-west');

INSERT INTO sqldiag_it_orders (id, customer_id, amount, status) VALUES
    (1,  1,  10.00, 'paid'),
    (2,  1,  20.00, 'paid'),
    (3,  1,  30.00, 'pending'),
    (4,  2,  15.00, 'paid'),
    (5,  2,  25.00, 'cancelled'),
    (6,  3,  12.00, 'paid'),
    (7,  3,  18.00, 'paid'),
    (8,  3,  22.00, 'pending'),
    (9,  4,   5.00, 'paid'),
    (10, 4,   8.00, 'paid'),
    (11, 5,  10.00, 'paid'),
    (12, 5,  15.00, 'pending'),
    (13, 1, 100.00, 'paid'),
    (14, 2, 200.00, 'paid'),
    (15, 3, 300.00, 'cancelled'),
    (16, 4,  50.00, 'paid'),
    (17, 5,  60.00, 'paid'),
    (18, 1,   1.00, 'paid'),
    (19, 2,   2.00, 'paid'),
    (20, 3,   3.00, 'pending');
