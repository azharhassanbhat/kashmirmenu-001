-- Seeds roles for the test schema (Hibernate-generated via ddl-auto=create-drop,
-- not Flyway, which is disabled for the test profile). Mirrors the roles the
-- app expects to exist (see RoleName) so auth/registration flows can be
-- exercised in integration tests without a real migrated database.
INSERT INTO roles (name, active, created_at, updated_at) VALUES
('PLATFORM_OWNER', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('ADMIN', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('RESTAURANT_OWNER', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('MANAGER', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('CHEF', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('WAITER', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('CASHIER', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('CUSTOMER', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);