-- ==========================================
-- KashmirMenu
-- Version : V33
-- Description : Settlements - one act of paying, covering one or more orders (QR ordering phase 4).
--
--   A settlement is either SEPARATE (a guest paying their own orders) or COMBINED (one guest paying
--   the whole table's outstanding orders). It groups the per-order payments rows that make it up:
--   a combined payment is ONE payments row PER COVERED ORDER, each for that order's outstanding
--   amount at the moment the settlement started. So revenue analytics, refunds and the per-order
--   balance keep working unchanged, and "which payment covers which order" is simply a fact.
--
--   There is deliberately no status column: a settlement's status is worked out from its payments
--   rows, so the two can never disagree.
--
--   idempotency_key is scoped to the paying guest, so one guest cannot probe another's keys, and a
--   retried request with the same key finds the settlement it already made. NULLs do not collide, so
--   requests without a key are unaffected.
-- ==========================================

CREATE TABLE settlements (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    settlement_code   VARCHAR(36)   NOT NULL,
    restaurant_id     BIGINT        NOT NULL,
    table_session_id  BIGINT        NOT NULL,
    payer_id          BIGINT        NOT NULL,
    kind              VARCHAR(20)   NOT NULL,
    payment_method    VARCHAR(20)   NOT NULL,
    total             DECIMAL(10,2) NOT NULL,
    idempotency_key   VARCHAR(80)   NULL,
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),

    CONSTRAINT uk_settlements_code UNIQUE (settlement_code),
    CONSTRAINT uk_settlements_payer_key UNIQUE (payer_id, idempotency_key),

    CONSTRAINT fk_settlements_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurants (id),
    CONSTRAINT fk_settlements_table_session
        FOREIGN KEY (table_session_id) REFERENCES table_sessions (id),
    CONSTRAINT fk_settlements_payer
        FOREIGN KEY (payer_id) REFERENCES diner_sessions (id),

    INDEX idx_settlements_restaurant (restaurant_id),
    INDEX idx_settlements_table_session (table_session_id)
);

ALTER TABLE payments
    ADD COLUMN settlement_id BIGINT NULL,
    ADD CONSTRAINT fk_payments_settlement
        FOREIGN KEY (settlement_id) REFERENCES settlements (id);
