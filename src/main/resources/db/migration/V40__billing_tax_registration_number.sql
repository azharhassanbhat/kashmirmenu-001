-- ==========================================
-- KashmirMenu
-- Version : V40
-- Description : The tax registration number a restaurant prints on its receipts (Stage I).
--
--   A restaurant that charges tax usually has a registration number (a GSTIN in India, a VAT number
--   elsewhere) that belongs on the bill. It sits with the other billing settings, one per restaurant.
--   NULL means none: nothing is printed. The application tidies it (upper case, no spaces, letters, digits,
--   hyphen and slash, 5 to 30 long) but does NOT check that it is genuine; that is the owner's responsibility.
--
--   Additive only: one nullable column, so every existing restaurant is unchanged.
-- ==========================================

ALTER TABLE restaurant_billing_settings
    ADD COLUMN tax_registration_number VARCHAR(30) NULL;
