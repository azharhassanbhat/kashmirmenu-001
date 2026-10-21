-- ==========================================
-- KashmirMenu
-- Version : V16
-- Description : Seed roles referenced by code (RoleName enum) but
--               never inserted by earlier migrations. Without this,
--               restaurant owner / staff registration fails with
--               "role not found" on any freshly migrated database.
-- ==========================================

INSERT IGNORE INTO roles (name)
VALUES
('RESTAURANT_OWNER'),
('MANAGER'),
('CHEF'),
('WAITER'),
('CASHIER');