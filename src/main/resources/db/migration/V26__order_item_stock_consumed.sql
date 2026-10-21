-- ==========================================
-- KashmirMenu
-- Version : V26
-- Description : Record how much stock an order line actually took.
--
--   Deduction and restoration were not symmetric. consumeForOrder skipped dishes with no
--   inventory row at the time; restoreForOrder added back for any dish that has a row NOW.
--   So an order placed before a dish was tracked, then cancelled after tracking was switched
--   on, credited stock that was never taken - inflating the count and allowing overselling,
--   the exact failure the stock feature exists to prevent.
--
--   Storing what was consumed per line removes the guesswork: restoration gives back exactly
--   what deduction took, and nothing else.
--
--   BACKFILL: existing rows default to 0, so cancelling an order placed before this migration
--   restores nothing. That is the safe direction - understating stock loses a sale, whereas
--   overstating it oversells and disappoints a diner who has already ordered. The alternative
--   (backfilling quantity for tracked dishes) would reproduce the bug for historical rows.
-- ==========================================

ALTER TABLE order_items
    ADD COLUMN stock_consumed INT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_order_item_stock_consumed_not_negative CHECK (stock_consumed >= 0);
