-- ==========================================
-- KashmirMenu
-- Version : V34
-- Description : Table-side service requests (call waiter, water, bill, ...) - Stage A.
--
--   A request belongs to one restaurant, one table and the table's current visit (table_session),
--   and records which guest raised it. It is independent of orders: a guest can ask for water
--   without ordering anything.
--
--   Lifecycle: PENDING -> ACKNOWLEDGED -> IN_PROGRESS -> COMPLETED, or CANCELLED. Only staff move a
--   request to COMPLETED; nothing completes one automatically.
--
--   duplicate guard: a guest tapping "Call waiter" five times must produce ONE open request.
--   open_dedupe_key is a generated column that is non-NULL only while a request is open and is not
--   OTHER, and it is UNIQUE, so the database refuses a second open request of the same type for
--   the same visit even if the application check is raced. NULLs never collide, so completed and
--   cancelled requests, and any number of OTHER requests, are unaffected.
--
--   Additive only: two new tables, no existing table or column is touched.
-- ==========================================

CREATE TABLE service_requests (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    request_number      VARCHAR(20)   NOT NULL,
    restaurant_id       BIGINT        NOT NULL,
    restaurant_table_id BIGINT        NOT NULL,
    table_session_id    BIGINT        NOT NULL,
    diner_session_id    BIGINT        NULL,
    request_type        VARCHAR(30)   NOT NULL,
    status              VARCHAR(20)   NOT NULL,
    note                VARCHAR(200)  NULL,
    assigned_to_user_id BIGINT        NULL,
    acknowledged_at     TIMESTAMP     NULL,
    completed_at        TIMESTAMP     NULL,
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    open_dedupe_key     VARCHAR(60)
        GENERATED ALWAYS AS (
            CASE WHEN status IN ('PENDING', 'ACKNOWLEDGED', 'IN_PROGRESS')
                      AND request_type <> 'OTHER'
                 THEN CONCAT(table_session_id, ':', request_type) END
        ) STORED,

    PRIMARY KEY (id),

    CONSTRAINT uk_service_requests_number UNIQUE (request_number),
    CONSTRAINT uk_service_requests_one_open_per_type UNIQUE (open_dedupe_key),

    CONSTRAINT fk_service_requests_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurants (id),
    CONSTRAINT fk_service_requests_table
        FOREIGN KEY (restaurant_table_id) REFERENCES restaurant_tables (id),
    CONSTRAINT fk_service_requests_table_session
        FOREIGN KEY (table_session_id) REFERENCES table_sessions (id),
    CONSTRAINT fk_service_requests_diner
        FOREIGN KEY (diner_session_id) REFERENCES diner_sessions (id),
    CONSTRAINT fk_service_requests_assignee
        FOREIGN KEY (assigned_to_user_id) REFERENCES users (id),

    -- The staff board: a restaurant's open requests, oldest first.
    INDEX idx_service_requests_restaurant_status (restaurant_id, status, created_at),
    -- A guest's own list for the current visit.
    INDEX idx_service_requests_session (table_session_id, created_at)
);

-- Append-only history of every status change, so "who acknowledged this and when" can be answered.
CREATE TABLE service_request_events (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    service_request_id BIGINT       NOT NULL,
    from_status        VARCHAR(20)  NULL,
    to_status          VARCHAR(20)  NOT NULL,
    -- Copied as plain text (the guest's display name, or the staff member's email) so the entry
    -- stays readable after an account is renamed or removed.
    actor_label        VARCHAR(120) NOT NULL,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),

    CONSTRAINT fk_service_request_events_request
        FOREIGN KEY (service_request_id) REFERENCES service_requests (id),

    INDEX idx_service_request_events_request (service_request_id, created_at)
);
