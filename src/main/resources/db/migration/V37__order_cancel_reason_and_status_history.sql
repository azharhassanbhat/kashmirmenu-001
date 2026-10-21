-- ==========================================
-- KashmirMenu
-- Version : V37
-- Description : Kitchen refinements (Stage F) - why an order was cancelled, and a history of every
--               status change.
--
--   orders gets the cancellation reason (a fixed code), an optional staff note, when it was cancelled
--   and by whom. All NULL for every existing order and for any cancelled without a reason, which the
--   application still allows so older callers keep working.
--
--   order_status_events is an append-only record of each status change: from, to, who, and (for a
--   cancellation) the reason and note. The actor is copied as plain text (a staff email, or the guest's
--   display name) so the entry stays readable after an account is renamed or removed. Orders placed
--   before this migration have no rows; the screen says so rather than inventing a history.
--
--   The reason code is also kept on the event, so an order's cancellation can be read from either.
--   The guest-facing screen shows only the reason's label, never the note, which may be internal.
--
--   Additive only.
-- ==========================================

ALTER TABLE orders
    ADD COLUMN cancel_reason VARCHAR(30)  NULL,
    ADD COLUMN cancel_note   VARCHAR(200) NULL,
    ADD COLUMN cancelled_at  TIMESTAMP    NULL,
    ADD COLUMN cancelled_by  VARCHAR(120) NULL;

CREATE TABLE order_status_events (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    order_id    BIGINT       NOT NULL,
    from_status VARCHAR(20)  NULL,
    to_status   VARCHAR(20)  NOT NULL,
    actor_label VARCHAR(120) NOT NULL,
    reason      VARCHAR(30)  NULL,
    note        VARCHAR(200) NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT fk_order_status_events_order
        FOREIGN KEY (order_id) REFERENCES orders (id),
    INDEX idx_order_status_events_order (order_id, created_at)
);
