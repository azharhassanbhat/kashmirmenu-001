-- ==========================================
-- KashmirMenu
-- Version : V31
-- Description : Table visits and diner sessions (QR ordering phase 1).
--
--   table_sessions  One visit to a table: opened by the first guest to scan, closed by staff.
--                   A visit has its own identifier, so guests from yesterday's visit are never
--                   mixed with tonight's.
--   diner_sessions  One guest within a visit. Holds the guest's name and a HASH of their random
--                   token; the token itself is never stored.
--
--   Nothing else changes in this migration: carts, orders and payments are untouched and the
--   existing QR flow keeps working. Later phases link carts and orders to these tables.
--
--   MySQL has no partial indexes, so "at most one OPEN visit per table" is expressed the same way
--   as V28: a generated column that is NULL unless the visit is OPEN. NULLs never collide in a
--   unique index, so any number of CLOSED visits per table stay legal.
-- ==========================================

CREATE TABLE table_sessions (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    visit_code          VARCHAR(36)  NOT NULL,
    restaurant_id       BIGINT       NOT NULL,
    restaurant_table_id BIGINT       NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    opened_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_activity_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at           TIMESTAMP    NULL,
    closed_by_user_id   BIGINT       NULL,
    close_reason        VARCHAR(30)  NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    open_table_id       BIGINT
        GENERATED ALWAYS AS (
            CASE WHEN status = 'OPEN' THEN restaurant_table_id END
        ) STORED,

    PRIMARY KEY (id),

    CONSTRAINT uk_table_sessions_visit_code UNIQUE (visit_code),
    CONSTRAINT uk_table_sessions_one_open_per_table UNIQUE (open_table_id),

    CONSTRAINT fk_table_sessions_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurants (id),
    CONSTRAINT fk_table_sessions_table
        FOREIGN KEY (restaurant_table_id) REFERENCES restaurant_tables (id),
    CONSTRAINT fk_table_sessions_closed_by
        FOREIGN KEY (closed_by_user_id) REFERENCES users (id),

    INDEX idx_table_sessions_restaurant_status (restaurant_id, status),
    INDEX idx_table_sessions_table (restaurant_table_id, status)
);

CREATE TABLE diner_sessions (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    table_session_id BIGINT       NOT NULL,
    display_name     VARCHAR(60)  NOT NULL,
    join_number      INT          NOT NULL,
    token_hash       VARCHAR(64)  NOT NULL,
    last_seen_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),

    CONSTRAINT uk_diner_sessions_token_hash UNIQUE (token_hash),
    CONSTRAINT uk_diner_sessions_join UNIQUE (table_session_id, join_number),

    CONSTRAINT fk_diner_sessions_table_session
        FOREIGN KEY (table_session_id) REFERENCES table_sessions (id)
);
