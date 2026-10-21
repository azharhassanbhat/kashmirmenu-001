-- ==========================================
-- KashmirMenu
-- Version : V43
-- Description : Owner-uploaded UPI QR + "I've completed payment" claims.
--
--   restaurant_payment_settings.upi_qr_url : the address of the owner's UPI QR photo, kept in the existing
--       stored_images table (the same store dish photos and logos use). NULL = no QR uploaded.
--   settlements.customer_confirmed_at / customer_reference : set when the paying guest taps
--       "I've completed payment" (optionally with the UPI transaction reference). This is only a CLAIM:
--       the payments stay PENDING until staff confirm the money arrived. Staff then confirm or reject
--       through the existing payment-status code.
--
--   Additive only: three nullable columns.
-- ==========================================

ALTER TABLE restaurant_payment_settings
    ADD COLUMN upi_qr_url VARCHAR(255) NULL;

ALTER TABLE settlements
    ADD COLUMN customer_confirmed_at TIMESTAMP NULL,
    ADD COLUMN customer_reference VARCHAR(40) NULL;
