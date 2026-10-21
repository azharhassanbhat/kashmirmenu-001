-- ==========================================
-- KashmirMenu
-- Version : V24
-- Description : The platform owner role and its one-time bootstrap.
--
--   PLATFORM_OWNER administers the whole platform and is deliberately separate from every
--   restaurant role. ADMIN, despite the name, is a restaurant-scoped role: it appears in the
--   same authorization groups as RESTAURANT_OWNER and MANAGER throughout, and those handlers
--   resolve data through the caller's own restaurant. Reusing it would have given the platform
--   owner restaurant-scoped endpoints that either fail or act on an arbitrary restaurant.
--
--   No account is created here. The application creates the first platform owner on startup
--   from PLATFORM_OWNER_EMAIL, disabled and with no usable password, and logs a single-use
--   claim token; the operator sets the password by presenting that token once. That keeps any
--   credential out of configuration, out of the image and out of this migration.
-- ==========================================

INSERT IGNORE INTO roles (name) VALUES ('PLATFORM_OWNER');

ALTER TABLE users
    -- Null except for an unclaimed bootstrap account. Hashed, so the log line that carries the
    -- token is the only place the usable value ever exists.
    ADD COLUMN platform_claim_token_hash VARCHAR(100) NULL,
    ADD COLUMN platform_claim_expires_at TIMESTAMP    NULL;
