-- ==========================================
-- KashmirMenu
-- Version : V19
-- Description : Let a diner actually use a coupon.
--   Until now coupons could be created in the admin console but never reached a bill:
--   nothing linked a coupon to a cart or an order, so the discount had nowhere to live.
--
--   A coupon is held on the cart while the diner shops, then copied onto the order when it
--   is placed. The code and the discount are copied as plain values as well as a reference,
--   so a past order still shows what was given even if the coupon is later edited or deleted.
-- ==========================================

ALTER TABLE carts
    ADD COLUMN coupon_id BIGINT NULL,
    ADD CONSTRAINT fk_cart_coupon
        FOREIGN KEY (coupon_id) REFERENCES coupons (id) ON DELETE SET NULL;

ALTER TABLE orders
    -- Kept if the coupon is deleted later, so the order's own history stays readable.
    ADD COLUMN coupon_id BIGINT NULL,
    ADD COLUMN coupon_code VARCHAR(50) NULL,
    ADD CONSTRAINT fk_order_coupon
        FOREIGN KEY (coupon_id) REFERENCES coupons (id) ON DELETE SET NULL,
    ADD INDEX idx_orders_coupon (coupon_id);

-- used_count is incremented when an order is placed, not when a code is checked, so a diner
-- trying a code (or tapping twice) no longer consumes it. This guards the counter itself:
-- it can never go negative when a redemption is released by a cancellation.
ALTER TABLE coupons
    ADD CONSTRAINT chk_coupon_used_count_not_negative CHECK (used_count >= 0);
