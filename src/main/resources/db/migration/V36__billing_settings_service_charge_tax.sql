-- ==========================================
-- KashmirMenu
-- Version : V36
-- Description : Configurable service charge and tax (Stage C).
--
--   Each restaurant sets its own tax name and rate, service charge rate, and whether its menu prices
--   already include tax. NOTHING IS BUILT IN: every rate defaults to 0, so a restaurant that never
--   opens the settings gets totals identical to before this migration.
--
--   The rates in force are COPIED onto the cart and onto the order (tax_name, tax_rate,
--   service_charge_rate, prices_include_tax), and so are the amounts they produced. An order is
--   therefore a complete record of how it was priced: changing the settings tomorrow never alters an
--   order placed today, and a receipt can always be reproduced.
--
--   orders.total already means "what the guest owes" everywhere (payments, bills, analytics all read
--   it), so service_charge and tax are simply part of it from now on.
--
--   Additive only; existing rows get 0 / FALSE and behave as before.
-- ==========================================

CREATE TABLE restaurant_billing_settings (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    restaurant_id       BIGINT        NOT NULL,
    tax_name            VARCHAR(30)   NOT NULL DEFAULT 'Tax',
    tax_rate            DECIMAL(5,2)  NOT NULL DEFAULT 0.00,
    service_charge_rate DECIMAL(5,2)  NOT NULL DEFAULT 0.00,
    prices_include_tax  BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT uk_billing_settings_restaurant UNIQUE (restaurant_id),
    CONSTRAINT fk_billing_settings_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurants (id),
    CONSTRAINT chk_billing_tax_rate CHECK (tax_rate >= 0 AND tax_rate <= 100),
    CONSTRAINT chk_billing_service_rate CHECK (service_charge_rate >= 0 AND service_charge_rate <= 100)
);

ALTER TABLE carts
    ADD COLUMN service_charge       DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    ADD COLUMN tax_included         DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    ADD COLUMN tax_name             VARCHAR(30)   NULL,
    ADD COLUMN tax_rate             DECIMAL(5,2)  NOT NULL DEFAULT 0.00,
    ADD COLUMN service_charge_rate  DECIMAL(5,2)  NOT NULL DEFAULT 0.00,
    ADD COLUMN prices_include_tax   BOOLEAN       NOT NULL DEFAULT FALSE;

ALTER TABLE orders
    ADD COLUMN service_charge       DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    ADD COLUMN tax_included         DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    ADD COLUMN tax_name             VARCHAR(30)   NULL,
    ADD COLUMN tax_rate             DECIMAL(5,2)  NOT NULL DEFAULT 0.00,
    ADD COLUMN service_charge_rate  DECIMAL(5,2)  NOT NULL DEFAULT 0.00,
    ADD COLUMN prices_include_tax   BOOLEAN       NOT NULL DEFAULT FALSE;
