CREATE TABLE order_items
(
    id BIGINT NOT NULL AUTO_INCREMENT,

    order_id BIGINT NOT NULL,

    menu_item_id BIGINT NOT NULL,

    item_name VARCHAR(150) NOT NULL,

    quantity INT NOT NULL,

    unit_price DECIMAL(10,2) NOT NULL,

    line_total DECIMAL(10,2) NOT NULL,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),

    CONSTRAINT fk_order_item_order
        FOREIGN KEY (order_id)
        REFERENCES orders(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_order_item_menu_item
        FOREIGN KEY (menu_item_id)
        REFERENCES menu_items(id)
);