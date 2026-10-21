-- ==========================================
-- KashmirMenu
-- Version : V28
-- Description : Make "one active cart per table" a database guarantee (DB-07).
--
--   The application already serialises cart operations by locking the table row, which closes
--   the find-then-insert race. But the database still permitted two ACTIVE carts for one table,
--   and if one ever appeared - a manual SQL fix, a restore, a future code path that forgets the
--   lock - then findByRestaurantTableAndStatus would match two rows, fail to fit an Optional and
--   throw on EVERY subsequent cart read for that table, permanently.
--
--   MySQL has no partial indexes, so the uniqueness is expressed through a generated column that
--   is NULL for any cart that is not ACTIVE. NULLs do not collide in a unique index, so any number
--   of CHECKED_OUT carts per table remain legal while only one ACTIVE one is.
--
--   This also gives findByRestaurantTableAndStatus an index it did not have: carts previously
--   carried foreign-key indexes only.
--
--   IF THIS MIGRATION FAILS with a duplicate-key error, duplicates already exist. Find them with:
--     SELECT restaurant_table_id, COUNT(*) FROM carts WHERE status = 'ACTIVE'
--     GROUP BY restaurant_table_id HAVING COUNT(*) > 1;
--   Keep the newest per table and mark the others CHECKED_OUT (their items are not lost; they
--   simply stop being the table's live cart), then re-run.
-- ==========================================

ALTER TABLE carts
    ADD COLUMN active_table_id BIGINT
        GENERATED ALWAYS AS (
            CASE WHEN status = 'ACTIVE' THEN restaurant_table_id END
        ) STORED;

ALTER TABLE carts
    ADD UNIQUE INDEX uk_carts_one_active_per_table (active_table_id);
