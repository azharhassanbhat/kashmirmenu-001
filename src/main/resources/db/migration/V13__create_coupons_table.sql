CREATE TABLE coupons (

    id BIGINT AUTO_INCREMENT PRIMARY KEY,

    restaurant_id BIGINT NOT NULL,

    code VARCHAR(50) NOT NULL,

    name VARCHAR(100) NOT NULL,

    description VARCHAR(500),

    discount_type VARCHAR(20) NOT NULL,

    discount_value DECIMAL(10,2) NOT NULL,

    minimum_order_amount DECIMAL(10,2) NOT NULL DEFAULT 0,

    maximum_discount DECIMAL(10,2),

    usage_limit INT NOT NULL DEFAULT 0,

    used_count INT NOT NULL DEFAULT 0,

    start_date TIMESTAMP NOT NULL,

    expiry_date TIMESTAMP NOT NULL,

    active BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_coupon_restaurant
        FOREIGN KEY (restaurant_id)
        REFERENCES restaurants(id),

    CONSTRAINT uk_coupon_code_restaurant
        UNIQUE (restaurant_id, code)
);