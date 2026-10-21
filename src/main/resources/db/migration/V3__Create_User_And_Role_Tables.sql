-- ==========================================
-- KashmirMenu
-- Version : V3
-- Description : Create User and Role Tables
-- Author : Azhar Hassan Bhat
-- ==========================================
-- ==========================================
-- Roles Table
-- ==========================================

CREATE TABLE roles (

    id BIGINT AUTO_INCREMENT PRIMARY KEY,

    name VARCHAR(50) NOT NULL,

    active BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT uk_role_name
        UNIQUE (name)
);

-- ==========================================
-- Users Table
-- ==========================================

CREATE TABLE users (

    id BIGINT AUTO_INCREMENT PRIMARY KEY,

    name VARCHAR(100) NOT NULL,

    email VARCHAR(150) NOT NULL,

    password VARCHAR(255) NOT NULL,

    active BOOLEAN NOT NULL DEFAULT TRUE,

    role_id BIGINT NOT NULL,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT uk_user_email
        UNIQUE (email),

    CONSTRAINT fk_user_role
        FOREIGN KEY (role_id)
        REFERENCES roles(id)
        ON DELETE RESTRICT
);

-- ==========================================
-- Indexes
-- ==========================================

CREATE INDEX idx_user_role
ON users(role_id);

CREATE INDEX idx_user_active
ON users(active);

CREATE INDEX idx_role_active
ON roles(active);

-- ==========================================
-- Default Roles
-- ==========================================

INSERT INTO roles (name)
VALUES
('ADMIN'),
('CUSTOMER');