-- ==========================================
-- KashmirMenu
-- Version : V21
-- Description : A minimum number of dishes, alongside the existing minimum order value.
--   minimum_order_amount is a currency amount; this is a count. A coupon can now require
--   both ("spend ₹500 and order at least 2 dishes"), either, or neither.
--   0 means no requirement, matching how usage_limit already treats 0.
-- ==========================================

ALTER TABLE coupons
    ADD COLUMN minimum_items INT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_coupon_minimum_items_not_negative CHECK (minimum_items >= 0);
