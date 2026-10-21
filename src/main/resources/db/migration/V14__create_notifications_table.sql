CREATE TABLE notifications (

    id BIGINT AUTO_INCREMENT PRIMARY KEY,

    restaurant_id BIGINT NOT NULL,

    order_id BIGINT,

    title VARCHAR(150) NOT NULL,

    message VARCHAR(500) NOT NULL,

    notification_type VARCHAR(50) NOT NULL,

    is_read BOOLEAN NOT NULL DEFAULT FALSE,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_notification_restaurant
        FOREIGN KEY (restaurant_id)
        REFERENCES restaurants(id),

    CONSTRAINT fk_notification_order
        FOREIGN KEY (order_id)
        REFERENCES orders(id)
);