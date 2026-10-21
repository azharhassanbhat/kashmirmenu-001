-- ==========================================
-- KashmirMenu
-- Version : V15
-- Description : Add restaurant_id to users
-- Author : Azhar Hassan Bhat
-- ==========================================

ALTER TABLE users
ADD COLUMN restaurant_id BIGINT NULL;

ALTER TABLE users
ADD CONSTRAINT fk_user_restaurant
FOREIGN KEY (restaurant_id)
REFERENCES restaurants(id);