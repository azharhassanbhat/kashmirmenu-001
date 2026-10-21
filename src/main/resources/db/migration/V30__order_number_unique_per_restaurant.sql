-- ==========================================
-- KashmirMenu
-- Version : V30
-- Description : Make order numbers unique per restaurant, not globally.
--
--   V20 gave each restaurant its own counter (ORD-1, ORD-2, ...), so every restaurant has an
--   ORD-1. V8's UNIQUE (order_number) still demanded global uniqueness, so the second restaurant's
--   first order failed with a duplicate-key error. Uniqueness now holds within a restaurant.
--
--   Existing rows are unaffected: any set of rows that was unique globally is unique per restaurant.
--   This also keeps an index whose leading column is restaurant_id, which the FK needs.
-- ==========================================

ALTER TABLE orders
    ADD CONSTRAINT uk_order_restaurant_number UNIQUE (restaurant_id, order_number);

ALTER TABLE orders
    DROP INDEX uk_order_number;
