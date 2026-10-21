-- ==========================================
-- KashmirMenu
-- Version : V39
-- Description : Private customer feedback (Stage G+1): a guest rates a served order from 1 to 5 stars and may
--               add a comment. Only the restaurant's management sees it.
--
--   One row per order (UNIQUE order_id): a guest changing their mind updates the row, they do not add
--   another, so one order can never count twice in an average.
--   The rating is held to 1..5 by the database itself, not just by the application.
--   restaurant_id is stored on the row so the owner's list and averages are one indexed lookup, with no
--   join through orders.
--   No personal detail beyond the comment is stored: not the guest's name, phone or device.
--
--   Additive only: one new table.
-- ==========================================

CREATE TABLE order_feedback (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    order_id      BIGINT       NOT NULL,
    restaurant_id BIGINT       NOT NULL,
    rating        INT          NOT NULL,
    comment       VARCHAR(500) NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT uk_order_feedback_order UNIQUE (order_id),
    CONSTRAINT fk_order_feedback_order
        FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_order_feedback_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurants (id),
    CONSTRAINT chk_order_feedback_rating CHECK (rating BETWEEN 1 AND 5),

    INDEX idx_order_feedback_restaurant (restaurant_id, created_at)
);
