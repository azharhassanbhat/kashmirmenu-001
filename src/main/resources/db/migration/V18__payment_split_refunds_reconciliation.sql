-- ==========================================
-- KashmirMenu
-- Version : V18
-- Description : Payments & revenue analytics foundation.
--   * several payments per order (split bills, retries after a failure)
--   * UPI app / wallet provider and card type capture
--   * payment_timestamp, restaurant_id, idempotency key, who recorded it, settlement marker
--   * dedicated refund records (multiple partial refunds per payment)
--   * cash count records for daily reconciliation
--
-- Safe for existing data: nothing is deleted, no provider is guessed (historical
-- rows keep payment_provider = NULL and are reported as "Unknown").
-- ==========================================

-- ------------------------------------------
-- 1. Allow more than one payment per order.
--    The FK fk_payment_order needs an index on order_id, so the plain index is
--    added before the unique one (created by V11 as `order_id`) is dropped.
-- ------------------------------------------
ALTER TABLE payments ADD INDEX idx_payments_order (order_id);
ALTER TABLE payments DROP INDEX order_id;

-- ------------------------------------------
-- 2. New payment columns (nullable first so existing rows can be backfilled).
-- ------------------------------------------
ALTER TABLE payments
    ADD COLUMN restaurant_id       BIGINT       NULL,
    ADD COLUMN payment_provider    VARCHAR(30)  NULL,
    ADD COLUMN card_type           VARCHAR(20)  NULL,
    ADD COLUMN currency            VARCHAR(3)   NOT NULL DEFAULT 'INR',
    ADD COLUMN payment_timestamp   TIMESTAMP    NULL,
    ADD COLUMN idempotency_key     VARCHAR(100) NULL,
    ADD COLUMN recorded_by_user_id BIGINT       NULL,
    ADD COLUMN settled_at          TIMESTAMP    NULL;

-- ------------------------------------------
-- 3. Backfill from existing data.
--    restaurant_id comes from the order; payment_timestamp is when the money was
--    received if it was, otherwise when the payment record was created.
-- ------------------------------------------
UPDATE payments p
    JOIN orders o ON o.id = p.order_id
SET p.restaurant_id = o.restaurant_id;

UPDATE payments
SET payment_timestamp = COALESCE(paid_at, created_at, CURRENT_TIMESTAMP);

ALTER TABLE payments
    MODIFY COLUMN restaurant_id     BIGINT    NOT NULL,
    MODIFY COLUMN payment_timestamp TIMESTAMP NOT NULL;

ALTER TABLE payments
    ADD CONSTRAINT fk_payment_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurants (id),
    ADD CONSTRAINT fk_payment_recorded_by
        FOREIGN KEY (recorded_by_user_id) REFERENCES users (id) ON DELETE SET NULL,
    ADD INDEX idx_payments_restaurant_time (restaurant_id, payment_timestamp),
    ADD INDEX idx_payments_restaurant_status_time (restaurant_id, payment_status, payment_timestamp),
    -- Replaying the same client request must not create a second payment. NULL keys never collide.
    ADD UNIQUE INDEX uk_payments_idempotency (restaurant_id, idempotency_key);

-- ------------------------------------------
-- 4. Refund records. A payment can be refunded several times, each linked to it.
-- ------------------------------------------
CREATE TABLE payment_refunds (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    payment_id          BIGINT         NOT NULL,
    amount              DECIMAL(10, 2) NOT NULL,
    reason              VARCHAR(255)   NULL,
    refunded_at         TIMESTAMP      NOT NULL,
    refunded_by_user_id BIGINT         NULL,
    created_at          TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_refund_payment
        FOREIGN KEY (payment_id) REFERENCES payments (id) ON DELETE CASCADE,
    CONSTRAINT fk_refund_user
        FOREIGN KEY (refunded_by_user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT chk_refund_amount_positive CHECK (amount > 0),

    INDEX idx_refunds_payment (payment_id),
    INDEX idx_refunds_time (refunded_at)
);

-- Before this migration a payment could only be flagged REFUNDED as a whole, with no
-- amount or date. Give each such payment one full refund record so net collections
-- are correct. The date is the last time the row changed (the best information that
-- exists); review these rows if exact refund dates matter for a past period.
INSERT INTO payment_refunds (payment_id, amount, reason, refunded_at)
SELECT id,
       amount,
       'Migrated from legacy REFUNDED status',
       COALESCE(updated_at, paid_at, created_at, CURRENT_TIMESTAMP)
FROM payments
WHERE payment_status = 'REFUNDED';

-- ------------------------------------------
-- 5. Cash counts for daily reconciliation. Several counts per day are allowed
--    (e.g. per shift); the history is kept.
-- ------------------------------------------
CREATE TABLE cash_reconciliations (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    restaurant_id      BIGINT         NOT NULL,
    business_date      DATE           NOT NULL,
    expected_cash      DECIMAL(12, 2) NOT NULL,
    counted_cash       DECIMAL(12, 2) NOT NULL,
    difference         DECIMAL(12, 2) NOT NULL,
    note               VARCHAR(500)   NULL,
    counted_by_user_id BIGINT         NULL,
    created_at         TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_cashrec_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurants (id),
    CONSTRAINT fk_cashrec_user
        FOREIGN KEY (counted_by_user_id) REFERENCES users (id) ON DELETE SET NULL,

    INDEX idx_cashrec_restaurant_date (restaurant_id, business_date)
);
