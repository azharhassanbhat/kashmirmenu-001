-- ==========================================
-- KashmirMenu
-- Version : V35
-- Description : Menu richness (Stage B) - option groups (portion sizes, add-ons, spice level),
--               allergens, dietary labels, chef's pick and today's special.
--
--   One generic model covers portion sizes, add-ons and spice level: a dish has option groups
--   (SINGLE or MULTI choice, optionally required) and each group has options with a price change
--   from the dish's base price (negative for a smaller portion).
--
--   A guest's choices are COPIED onto the cart line and then the order line (name and price change
--   as they were), so editing or deleting an option later never rewrites what a guest was charged
--   or what the kitchen was told. cart_item_options.option_id keeps a live link so placement can
--   refuse an option that has since been switched off; it is SET NULL if the option is deleted.
--
--   cart_items.options_key identifies a line: the chosen option ids, sorted. Two lines of the same
--   dish with different options are different lines; the same options merge.
--
--   Allergens and dietary labels are comma-separated codes validated by the application against a
--   fixed vocabulary. NULL means "the restaurant has not said", which is deliberately different from
--   an empty string ("declared: none") - the menu shows allergen information only once declared.
--
--   Additive only; existing rows get safe defaults and behave exactly as before.
-- ==========================================

ALTER TABLE menu_items
    ADD COLUMN chef_pick      BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN todays_special BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN allergens      VARCHAR(200)  NULL,
    ADD COLUMN dietary_labels VARCHAR(200)  NULL;

CREATE TABLE menu_option_groups (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    menu_item_id  BIGINT       NOT NULL,
    name          VARCHAR(80)  NOT NULL,
    kind          VARCHAR(10)  NOT NULL,
    required      BOOLEAN      NOT NULL DEFAULT FALSE,
    max_select    INT          NULL,
    display_order INT          NOT NULL DEFAULT 0,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT fk_option_groups_item
        FOREIGN KEY (menu_item_id) REFERENCES menu_items (id) ON DELETE CASCADE,
    INDEX idx_option_groups_item (menu_item_id, display_order)
);

CREATE TABLE menu_options (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    group_id      BIGINT        NOT NULL,
    name          VARCHAR(80)   NOT NULL,
    price_delta   DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    available     BOOLEAN       NOT NULL DEFAULT TRUE,
    is_default    BOOLEAN       NOT NULL DEFAULT FALSE,
    display_order INT           NOT NULL DEFAULT 0,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT fk_options_group
        FOREIGN KEY (group_id) REFERENCES menu_option_groups (id) ON DELETE CASCADE,
    INDEX idx_options_group (group_id, display_order)
);

ALTER TABLE cart_items
    ADD COLUMN options_key VARCHAR(255) NOT NULL DEFAULT '';

CREATE TABLE cart_item_options (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    cart_item_id BIGINT        NOT NULL,
    option_id    BIGINT        NULL,
    group_name   VARCHAR(80)   NOT NULL,
    option_name  VARCHAR(80)   NOT NULL,
    price_delta  DECIMAL(10,2) NOT NULL,
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT fk_cart_item_options_item
        FOREIGN KEY (cart_item_id) REFERENCES cart_items (id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_item_options_option
        FOREIGN KEY (option_id) REFERENCES menu_options (id) ON DELETE SET NULL,
    INDEX idx_cart_item_options_item (cart_item_id)
);

CREATE TABLE order_item_options (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    order_item_id BIGINT        NOT NULL,
    group_name    VARCHAR(80)   NOT NULL,
    option_name   VARCHAR(80)   NOT NULL,
    price_delta   DECIMAL(10,2) NOT NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT fk_order_item_options_item
        FOREIGN KEY (order_item_id) REFERENCES order_items (id) ON DELETE CASCADE,
    INDEX idx_order_item_options_item (order_item_id)
);
