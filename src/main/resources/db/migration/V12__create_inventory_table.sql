CREATE TABLE inventory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,

    restaurant_id BIGINT NOT NULL,

    menu_item_id BIGINT NOT NULL,

    available_quantity INT NOT NULL DEFAULT 0,

    minimum_quantity INT NOT NULL DEFAULT 0,

    in_stock BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_inventory_restaurant
        FOREIGN KEY (restaurant_id)
        REFERENCES restaurants(id),

    CONSTRAINT fk_inventory_menu_item
        FOREIGN KEY (menu_item_id)
        REFERENCES menu_items(id),

    CONSTRAINT uk_inventory_menu_item
        UNIQUE (menu_item_id)
);