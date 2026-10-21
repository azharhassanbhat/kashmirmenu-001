CREATE TABLE cart_items
(
    id BIGINT NOT NULL AUTO_INCREMENT,

    cart_id BIGINT NOT NULL,

    menu_item_id BIGINT NOT NULL,

    quantity INT NOT NULL,

    unit_price DECIMAL(10,2) NOT NULL,

    line_total DECIMAL(10,2) NOT NULL,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),

    CONSTRAINT fk_cart_item_cart
        FOREIGN KEY (cart_id)
            REFERENCES carts(id)
            ON DELETE CASCADE,

    CONSTRAINT fk_cart_item_menu_item
        FOREIGN KEY (menu_item_id)
            REFERENCES menu_items(id)
);