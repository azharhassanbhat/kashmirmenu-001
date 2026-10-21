-- ==========================================
-- KashmirMenu
-- Version : V41
-- Description : QR landing page, per restaurant.
--
--   restaurant_landing_settings  one optional row per restaurant: tagline, cover image, links (Google review
--                                link, Instagram, YouTube, ...), opening hours, guest Wi-Fi, which actions
--                                show and in what order, and the rewards rules. Kept apart from `restaurants`
--                                on purpose: the Wi-Fi password lives here and must never ride along on
--                                RestaurantResponse. A restaurant with no row simply gets the defaults.
--   anonymous_feedback           feedback from the landing page: rating, category, comment. No sign-in; no
--                                name, phone or device is stored. `contact` is filled only when the guest
--                                explicitly asks to be contacted. `order_id` is set only when the guest gave
--                                an order they own.
--   reward_members               an optional rewards membership per restaurant. The guest's secret is held
--                                only on their device; only its SHA-256 hash is stored here.
--   reward_ledger                every change to a balance. UNIQUE(order_id) means one order can earn points
--                                once, ever; the balance can never go below zero (CHECK).
--
--   Additive only: four new tables, nothing existing is altered. The Google Place ID column from V17 is left
--   as it was (it still serves the order screen's review prompt).
--   Rollback: DROP TABLE reward_ledger, reward_members, anonymous_feedback, restaurant_landing_settings;
--   and delete the flyway_schema_history row for version 41.
-- ==========================================

CREATE TABLE restaurant_landing_settings (
    id                       BIGINT        NOT NULL AUTO_INCREMENT,
    restaurant_id            BIGINT        NOT NULL,

    landing_enabled          BOOLEAN       NOT NULL DEFAULT TRUE,
    tagline                  VARCHAR(120)  NULL,
    cover_image_url          VARCHAR(500)  NULL,
    announcement             VARCHAR(300)  NULL,
    opening_hours            VARCHAR(700)  NULL,

    google_review_url        VARCHAR(500)  NULL,
    instagram_url            VARCHAR(500)  NULL,
    youtube_url              VARCHAR(500)  NULL,
    facebook_url             VARCHAR(500)  NULL,
    website_url              VARCHAR(500)  NULL,
    map_url                  VARCHAR(500)  NULL,

    wifi_enabled             BOOLEAN       NOT NULL DEFAULT FALSE,
    wifi_ssid                VARCHAR(64)   NULL,
    wifi_security            VARCHAR(8)    NOT NULL DEFAULT 'WPA',
    wifi_password            VARCHAR(63)   NULL,

    feedback_enabled         BOOLEAN       NOT NULL DEFAULT TRUE,
    assistance_enabled       BOOLEAN       NOT NULL DEFAULT TRUE,

    rewards_enabled          BOOLEAN       NOT NULL DEFAULT FALSE,
    rewards_spend_per_point  DECIMAL(10,2) NOT NULL DEFAULT 10.00,
    rewards_redeem_points    INT           NOT NULL DEFAULT 100,
    rewards_reward_text      VARCHAR(150)  NULL,
    rewards_terms            VARCHAR(300)  NULL,

    action_order             VARCHAR(100)  NULL,

    created_at               TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT uk_landing_restaurant UNIQUE (restaurant_id),
    CONSTRAINT fk_landing_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurants (id),
    CONSTRAINT chk_landing_rewards_rules
        CHECK (rewards_spend_per_point > 0 AND rewards_redeem_points >= 1)
);

CREATE TABLE anonymous_feedback (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    restaurant_id BIGINT        NOT NULL,
    order_id      BIGINT        NULL,
    rating        INT           NOT NULL,
    category      VARCHAR(20)   NOT NULL,
    comment       VARCHAR(1000) NULL,
    contact       VARCHAR(100)  NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT fk_anon_feedback_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurants (id),
    CONSTRAINT fk_anon_feedback_order
        FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT chk_anon_feedback_rating CHECK (rating BETWEEN 1 AND 5),

    INDEX idx_anon_feedback_restaurant (restaurant_id, created_at)
);

CREATE TABLE reward_members (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    restaurant_id   BIGINT      NOT NULL,
    member_code     VARCHAR(8)  NOT NULL,
    secret_hash     VARCHAR(64) NOT NULL,
    points_balance  INT         NOT NULL DEFAULT 0,
    lifetime_points INT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT uk_reward_member_code UNIQUE (restaurant_id, member_code),
    CONSTRAINT uk_reward_member_secret UNIQUE (restaurant_id, secret_hash),
    CONSTRAINT fk_reward_member_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurants (id),
    CONSTRAINT chk_reward_member_balance CHECK (points_balance >= 0)
);

CREATE TABLE reward_ledger (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    restaurant_id BIGINT       NOT NULL,
    member_id     BIGINT       NOT NULL,
    kind          VARCHAR(10)  NOT NULL,
    points        INT          NOT NULL,
    order_id      BIGINT       NULL,
    note          VARCHAR(200) NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT uk_reward_ledger_order UNIQUE (order_id),
    CONSTRAINT fk_reward_ledger_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurants (id),
    CONSTRAINT fk_reward_ledger_member
        FOREIGN KEY (member_id) REFERENCES reward_members (id),
    CONSTRAINT fk_reward_ledger_order
        FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT chk_reward_ledger_kind CHECK (kind IN ('EARN', 'REDEEM')),
    CONSTRAINT chk_reward_ledger_sign
        CHECK ((kind = 'EARN' AND points > 0) OR (kind = 'REDEEM' AND points < 0)),

    INDEX idx_reward_ledger_member (member_id, created_at)
);
