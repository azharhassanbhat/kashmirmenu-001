-- ==========================================
-- KashmirMenu
-- Version : V17
-- Description : Google Place ID for "Review us on Google" links and rating display.
--               Nullable, so existing restaurants are unaffected.
-- ==========================================

ALTER TABLE restaurants
    ADD COLUMN google_place_id VARCHAR(255) NULL;
