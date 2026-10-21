CREATE TABLE orders
(
    id BIGINT NOT NULL AUTO_INCREMENT,

    order_number VARCHAR(50) NOT NULL,

    restaurant_id BIGINT NOT NULL,

    restaurant_table_id BIGINT NOT NULL,

    status VARCHAR(30) NOT NULL,

    subtotal DECIMAL(10,2) NOT NULL DEFAULT 0.00,

    tax DECIMAL(10,2) NOT NULL DEFAULT 0.00,

    discount DECIMAL(10,2) NOT NULL DEFAULT 0.00,

    total DECIMAL(10,2) NOT NULL DEFAULT 0.00,

    notes VARCHAR(1000),

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),

    CONSTRAINT uk_order_number
        UNIQUE (order_number),

    CONSTRAINT fk_order_restaurant
        FOREIGN KEY (restaurant_id)
        REFERENCES restaurants(id),

    CONSTRAINT fk_order_table
        FOREIGN KEY (restaurant_table_id)
        REFERENCES restaurant_tables(id)
);