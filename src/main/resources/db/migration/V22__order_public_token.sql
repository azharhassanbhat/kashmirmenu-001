-- ==========================================
-- KashmirMenu
-- Version : V22
-- Description : An unguessable token for the public order-tracking URL.
--
--   Order numbers became short and sequential (ORD-1, ORD-2) so diners could read them
--   back to staff. But GET /api/public/orders/{orderNumber} is unauthenticated by design,
--   which makes the order number the only credential protecting that order's data - and a
--   sequential credential can simply be counted through.
--
--   The number stays as the human label. Lookups move to this random token, so the two
--   jobs - being readable, and being unguessable - stop fighting each other.
-- ==========================================

ALTER TABLE orders
    ADD COLUMN public_token VARCHAR(43) NULL;

-- Existing orders get a token derived from a random UUID. MySQL's UUID() is evaluated per
-- row here, so each order gets a distinct value. These are not cryptographically strong;
-- they only need to stop the counting attack on history that is already placed.
UPDATE orders
SET public_token = REPLACE(UUID(), '-', '')
WHERE public_token IS NULL;

ALTER TABLE orders
    MODIFY COLUMN public_token VARCHAR(43) NOT NULL,
    ADD UNIQUE INDEX uk_orders_public_token (public_token);
