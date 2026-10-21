-- ==========================================
-- KashmirMenu
-- Version : V38
-- Description : Uploaded photos, stored in the database (Stage G).
--
--   A restaurant can upload a dish photo instead of pasting a link. The photo is kept HERE, in MySQL,
--   so it is backed up with everything else and the deployment needs no extra storage. The cost of that
--   choice is database size, so every photo is re-encoded to a small JPEG (at most 1200 pixels on its
--   longest side, typically 100 to 300 KB) before it is stored, and each restaurant has a quota.
--
--   The bytes live in their own table and are never part of the menu query, so reading the menu does not
--   drag photo data through the connection. A dish stores only the photo's public ADDRESS in its existing
--   image_url column ("/api/public/images/<key>"), so nothing else about dishes changes.
--
--   public_key is 128 random bits (32 hex characters). The address is public (guests are not signed in),
--   so it must be impossible to guess or count up to, which an id would not be.
--
--   Photos nothing refers to any more (a replaced photo, an upload never saved) are deleted by a
--   scheduled job after a day.
--
--   Additive only: one new table.
-- ==========================================

CREATE TABLE stored_images (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    public_key    VARCHAR(32) NOT NULL,
    restaurant_id BIGINT      NOT NULL,
    content_type  VARCHAR(30) NOT NULL,
    width         INT         NOT NULL,
    height        INT         NOT NULL,
    size_bytes    INT         NOT NULL,
    data          MEDIUMBLOB  NOT NULL,
    created_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT uk_stored_images_public_key UNIQUE (public_key),
    CONSTRAINT fk_stored_images_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurants (id),
    CONSTRAINT chk_stored_images_size CHECK (size_bytes > 0),

    INDEX idx_stored_images_restaurant (restaurant_id),
    INDEX idx_stored_images_created (created_at)
);
