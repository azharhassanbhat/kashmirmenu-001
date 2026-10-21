-- ==========================================
-- KashmirMenu
-- Version : V20
-- Description : Short, human order numbers.
--   Order numbers were "ORD-20261013-290F2080CCD3": a date plus 12 random hex characters.
--   Unique, but impossible for a diner to read back to staff or for staff to call across a
--   kitchen. Each restaurant now gets its own counter, producing ORD-1, ORD-2, ORD-3...
--
--   Existing orders keep the numbers they were given: diners hold them in their browser's
--   order history and they are printed on past bills. Each restaurant's counter therefore
--   starts above any short number that somehow already exists, so new numbers cannot collide.
-- ==========================================

CREATE TABLE restaurant_order_counters (
    restaurant_id BIGINT    NOT NULL,
    next_number   BIGINT    NOT NULL DEFAULT 1,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (restaurant_id),

    CONSTRAINT fk_order_counter_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE
);

-- One row per existing restaurant, so the first order after this migration does not have to
-- create it. Starts at 1 unless short-form numbers are already present.
INSERT INTO restaurant_order_counters (restaurant_id, next_number)
SELECT r.id,
       COALESCE(
           (SELECT MAX(CAST(SUBSTRING(o.order_number, 5) AS UNSIGNED)) + 1
            FROM orders o
            WHERE o.restaurant_id = r.id
              AND o.order_number REGEXP '^ORD-[0-9]+$'),
           1)
FROM restaurants r;
