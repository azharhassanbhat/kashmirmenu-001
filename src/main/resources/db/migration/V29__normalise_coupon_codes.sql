-- ==========================================
-- KashmirMenu
-- Version : V29
-- Description : Store coupon codes upper-cased so the unique index can be used (DB-02).
--
--   Diners type codes by hand, so matching has to be case-insensitive. That was implemented as
--   upper(c.code) = upper(:code), which wraps the indexed column in a function and makes the
--   code half of UNIQUE (restaurant_id, code) unusable - the planner could narrow to the
--   restaurant, then had to evaluate upper() row by row.
--
--   Normalising on write instead lets the lookup be a plain equality against the index. Codes are
--   already displayed and entered in upper case (the admin form uppercases as you type, and so does
--   the diner's field), so this changes no visible behaviour.
--
--   IF THIS FAILS with a duplicate-key error, a restaurant has two codes differing only by case
--   (possible only under a case-sensitive collation). Find them with:
--     SELECT restaurant_id, UPPER(code), COUNT(*) FROM coupons
--     GROUP BY restaurant_id, UPPER(code) HAVING COUNT(*) > 1;
--   Rename or delete one of each pair, then re-run.
-- ==========================================

UPDATE coupons
SET code = UPPER(TRIM(code))
WHERE code <> UPPER(TRIM(code));
