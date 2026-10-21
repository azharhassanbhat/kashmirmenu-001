-- ==========================================
-- KashmirMenu
-- Version : V25
-- Description : Append-only record of security-relevant actions.
--
--   Nothing recorded logins, failed authentications, suspensions or account status changes, so
--   there was no way to answer "who did this, and when". Actor email and role are copied in as
--   plain values rather than only referenced, so an entry stays readable after the account is
--   renamed or removed.
--
--   The application writes rows and reads them back; it never updates or deletes them, and no
--   API is provided to do so. For real immutability also revoke UPDATE and DELETE on this table
--   from the application's database user - a deployment step, not something code can enforce.
-- ==========================================

CREATE TABLE audit_log (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    occurred_at   TIMESTAMP    NOT NULL,

    -- Null when the actor is unknown, e.g. a failed login for an address that has no account.
    actor_user_id BIGINT       NULL,
    actor_email   VARCHAR(150) NULL,
    actor_role    VARCHAR(40)  NULL,

    action        VARCHAR(60)  NOT NULL,
    resource_type VARCHAR(40)  NULL,
    resource_id   VARCHAR(64)  NULL,

    -- Human-readable context. Never credentials, tokens or card data.
    detail        VARCHAR(500) NULL,
    ip_address    VARCHAR(45)  NULL,

    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    -- The actor is deliberately not a foreign key: deleting a user must not delete the record of
    -- what they did, and must not fail because that record exists.
    INDEX idx_audit_time (occurred_at),
    INDEX idx_audit_actor (actor_user_id, occurred_at),
    INDEX idx_audit_action (action, occurred_at)
);
