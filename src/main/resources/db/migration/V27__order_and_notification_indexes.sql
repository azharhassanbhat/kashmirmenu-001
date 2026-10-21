-- ==========================================
-- KashmirMenu
-- Version : V27
-- Description : Composite indexes for the order and notification queries (DB-01, DB-08).
--
--   Pure additions: no column, constraint or data changes, and no application behaviour
--   depends on them. Safe to apply and safe to drop again.
--
--   orders previously carried indexes only on order_number, public_token, coupon_id and the
--   two foreign keys. Four queries filter on restaurant_id AND a second column, three of them
--   also sorting, so the planner could narrow to the restaurant and then had to test the date
--   or status per row and sort the survivors.
--
--   Not added: an index for findByRestaurantOrderByIdDesc (the paged order list). InnoDB
--   appends the primary key to every secondary index, so the existing foreign-key index on
--   restaurant_id is physically (restaurant_id, id) and already satisfies both that filter and
--   its ORDER BY id DESC. A separate index would be redundant.
-- ==========================================

-- Serves: findSince (kitchen "today"), countByRestaurantIdAndCreatedAtBetween (dashboard).
CREATE INDEX idx_orders_restaurant_created
    ON orders (restaurant_id, created_at);

-- Serves: findByRestaurantAndStatusInOrderByCreatedAtAsc (the kitchen board, polled every 20s),
-- findByRestaurantAndStatus, and countByRestaurantIdAndStatus - which the dashboard calls four
-- times per page load, once per status tile.
CREATE INDEX idx_orders_restaurant_status_created
    ON orders (restaurant_id, status, created_at);

-- notifications had foreign-key indexes only, while its reads are "this restaurant's, newest
-- first" over the table's whole history. It is one of only two tables here that grows without
-- bound (audit_log is the other).
CREATE INDEX idx_notifications_restaurant_created
    ON notifications (restaurant_id, created_at);

-- Serves the unread list and the unread count, which the admin shell polls.
CREATE INDEX idx_notifications_restaurant_read_created
    ON notifications (restaurant_id, is_read, created_at);
