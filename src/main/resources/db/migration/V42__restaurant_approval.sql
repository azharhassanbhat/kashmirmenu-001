-- ==========================================
-- KashmirMenu
-- Version : V42
-- Description : Restaurant approval lifecycle (PENDING / APPROVED / REJECTED / SUSPENDED).
--
--   Every restaurant that exists when this runs is already trading, so the column defaults to
--   APPROVED and the backfill is simply that default: nothing that works today stops working.
--   New registrations are inserted by the application with an explicit status (PENDING unless
--   app.restaurant-approval.required is switched off).
--
--   The existing `active` flag is untouched. It is the owner's own on/off switch; approval_status
--   is the platform owner's decision, and the two are independent.
--
--   Who decided and when is kept on the row (reviewed_by_id / reviewed_at) for the current state;
--   the permanent trail of every decision lives in audit_log.
-- ==========================================

ALTER TABLE restaurants
    ADD COLUMN approval_status    VARCHAR(20)  NOT NULL DEFAULT 'APPROVED',
    ADD COLUMN reviewed_by_id     BIGINT       NULL,
    ADD COLUMN reviewed_at        DATETIME     NULL,
    ADD COLUMN rejection_reason   VARCHAR(500) NULL,
    ADD COLUMN suspension_reason  VARCHAR(500) NULL,
    ADD CONSTRAINT fk_restaurant_reviewed_by FOREIGN KEY (reviewed_by_id) REFERENCES users (id),
    ADD CONSTRAINT chk_restaurant_approval_status
        CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED'));

CREATE INDEX idx_restaurant_approval_status ON restaurants (approval_status);
