-- ==========================================
-- KashmirMenu
-- Version : V32
-- Description : Each guest gets their own cart, and every order records who placed it
--               (QR ordering phase 2).
--
--   carts   +table_session_id, +diner_session_id
--           "One ACTIVE cart per table" (V28) becomes
--             - one ACTIVE cart per guest, and
--             - one ACTIVE staff cart per table (a cart with no guest, used by the staff API).
--           Both are generated-column unique indexes, the same device V28 used: the column is NULL
--           unless the cart qualifies, and NULLs never collide in a unique index.
--   orders  +table_session_id, +diner_session_id, +diner_name
--           All nullable: orders placed before this migration, and orders staff place themselves,
--           have no guest. diner_name is copied at placement so a listing of orders can show who
--           placed each one without a join.
--
--   EXISTING DATA: an ACTIVE cart with no guest is, by definition, a staff cart, and the new
--   per-table staff index holds for it exactly as V28's index did. So a cart a table's diners were
--   filling at the moment of deployment is not lost: it becomes that table's staff cart, which staff
--   can still place. Diners simply start their own carts. Deploy outside service hours if you can.
-- ==========================================

ALTER TABLE carts
    ADD COLUMN table_session_id BIGINT NULL,
    ADD COLUMN diner_session_id BIGINT NULL,
    ADD CONSTRAINT fk_carts_table_session
        FOREIGN KEY (table_session_id) REFERENCES table_sessions (id),
    ADD CONSTRAINT fk_carts_diner_session
        FOREIGN KEY (diner_session_id) REFERENCES diner_sessions (id);

-- The staff cart is found by table and status; V28's index used to serve that lookup.
ALTER TABLE carts
    ADD INDEX idx_carts_table_status (restaurant_table_id, status);

ALTER TABLE carts
    ADD COLUMN active_diner_id BIGINT
        GENERATED ALWAYS AS (
            CASE WHEN status = 'ACTIVE' THEN diner_session_id END
        ) STORED,
    ADD COLUMN active_staff_table_id BIGINT
        GENERATED ALWAYS AS (
            CASE WHEN status = 'ACTIVE' AND diner_session_id IS NULL THEN restaurant_table_id END
        ) STORED;

-- The new staff index is created BEFORE the old one is dropped, so there is no moment at which
-- nothing prevents two ACTIVE staff carts on a table.
ALTER TABLE carts
    ADD UNIQUE INDEX uk_carts_one_active_per_diner (active_diner_id),
    ADD UNIQUE INDEX uk_carts_one_active_staff_per_table (active_staff_table_id);

ALTER TABLE carts DROP INDEX uk_carts_one_active_per_table;
ALTER TABLE carts DROP COLUMN active_table_id;

ALTER TABLE orders
    ADD COLUMN table_session_id BIGINT NULL,
    ADD COLUMN diner_session_id BIGINT NULL,
    ADD COLUMN diner_name VARCHAR(60) NULL,
    ADD CONSTRAINT fk_orders_table_session
        FOREIGN KEY (table_session_id) REFERENCES table_sessions (id),
    ADD CONSTRAINT fk_orders_diner_session
        FOREIGN KEY (diner_session_id) REFERENCES diner_sessions (id);
