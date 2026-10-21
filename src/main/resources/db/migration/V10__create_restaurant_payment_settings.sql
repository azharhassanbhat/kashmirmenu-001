CREATE TABLE restaurant_payment_settings (

    id BIGINT PRIMARY KEY AUTO_INCREMENT,

    restaurant_id BIGINT NOT NULL UNIQUE,

    cash_enabled BOOLEAN NOT NULL DEFAULT TRUE,

    upi_enabled BOOLEAN NOT NULL DEFAULT FALSE,

    card_enabled BOOLEAN NOT NULL DEFAULT FALSE,

    wallet_enabled BOOLEAN NOT NULL DEFAULT FALSE,

    upi_id VARCHAR(100),

    merchant_name VARCHAR(100),

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_payment_setting_restaurant
        FOREIGN KEY (restaurant_id)
        REFERENCES restaurants(id)
);