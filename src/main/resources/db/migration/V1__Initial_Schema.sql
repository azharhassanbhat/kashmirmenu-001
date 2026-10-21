-- ==========================================
-- KashmirMenu
-- Version : V1
-- Description : Initial Database Schema
-- Author : Azhar Hassan Bhat
-- ==========================================

-- ==========================================
-- Restaurants Table
-- ==========================================
CREATE TABLE restaurants (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,

    name VARCHAR(100) NOT NULL,
    slug VARCHAR(100) NOT NULL UNIQUE,

    email VARCHAR(100),
    phone VARCHAR(20),

    address VARCHAR(255),
    city VARCHAR(100),
    state VARCHAR(100),
    country VARCHAR(100),

    logo VARCHAR(255),

    primary_color VARCHAR(20),
    secondary_color VARCHAR(20),

    active BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP
);

-- ==========================================
-- Categories Table
-- ==========================================
CREATE TABLE categories (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,

    restaurant_id BIGINT NOT NULL,

    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),

    display_order INT NOT NULL DEFAULT 0,

    active BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_category_restaurant
        FOREIGN KEY (restaurant_id)
        REFERENCES restaurants(id)
        ON DELETE CASCADE,

    CONSTRAINT uk_restaurant_category_name
        UNIQUE (restaurant_id, name)
);

-- ==========================================
-- Menu Items Table
-- ==========================================
CREATE TABLE menu_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,

    restaurant_id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,

    name VARCHAR(150) NOT NULL,
    description VARCHAR(1000),

    price DECIMAL(10,2) NOT NULL,

    image_url VARCHAR(255),

    veg BOOLEAN NOT NULL DEFAULT TRUE,

    available BOOLEAN NOT NULL DEFAULT TRUE,

    active BOOLEAN NOT NULL DEFAULT TRUE,

    display_order INT NOT NULL DEFAULT 0,

    preparation_time INT NOT NULL DEFAULT 0,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_menu_restaurant
        FOREIGN KEY (restaurant_id)
        REFERENCES restaurants(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_menu_category
        FOREIGN KEY (category_id)
        REFERENCES categories(id)
        ON DELETE CASCADE
);


CREATE INDEX idx_category_restaurant
ON categories(restaurant_id);

CREATE INDEX idx_menu_restaurant
ON menu_items(restaurant_id);

CREATE INDEX idx_menu_category
ON menu_items(category_id);

CREATE INDEX idx_menu_active
ON menu_items(active);

CREATE INDEX idx_category_name
ON categories(name);

CREATE INDEX idx_restaurant_active
ON restaurants(active);

CREATE INDEX idx_menu_available
ON menu_items(available);