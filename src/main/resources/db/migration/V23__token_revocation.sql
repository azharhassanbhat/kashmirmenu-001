-- ==========================================
-- KashmirMenu
-- Version : V23
-- Description : Make tokens revocable.
--   JWTs were valid until expiry with no way to invalidate them: there was no logout
--   endpoint, and the frontend's "Sign out" only cleared its own copy. A token taken from
--   a shared kitchen tablet stayed usable for the whole access-token lifetime.
--
--   Rather than introduce server-side sessions, each user carries the instant from which
--   their tokens are accepted. Logging out (or changing a password) moves it to now, which
--   invalidates every token issued before that moment in one write.
-- ==========================================

ALTER TABLE users
    ADD COLUMN tokens_valid_from TIMESTAMP NULL;
