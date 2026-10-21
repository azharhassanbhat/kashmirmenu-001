CREATE TABLE restaurant_tables
(
    id BIGINT NOT NULL AUTO_INCREMENT,

    restaurant_id BIGINT NOT NULL,

    table_code VARCHAR(30) NOT NULL,

    table_name VARCHAR(100) NOT NULL,

    capacity INT NOT NULL,

    qr_identifier VARCHAR(100) NOT NULL,

    active BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),

    CONSTRAINT fk_restaurant_tables_restaurant
        FOREIGN KEY (restaurant_id)
            REFERENCES restaurants (id),

    CONSTRAINT uk_restaurant_table_code
        UNIQUE (restaurant_id, table_code),

    CONSTRAINT uk_restaurant_table_qr_identifier
        UNIQUE (qr_identifier)
);