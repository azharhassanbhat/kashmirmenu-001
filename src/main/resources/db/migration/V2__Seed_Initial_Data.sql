-- ==========================================
-- KashmirMenu
-- Version : V2
-- Description : Seed Initial Data
-- Author : Azhar Hassan Bhat
-- ==========================================

INSERT INTO restaurants
(
    name,
    slug,
    email,
    phone,
    address,
    city,
    state,
    country,
    logo,
    primary_color,
    secondary_color,
    active
)
VALUES
(
    'Kashmir Cafe',
    'kashmir-cafe',
    'contact@kashmircafe.com',
    '+91-9876543210',
    'Lal Chowk',
    'Srinagar',
    'Jammu & Kashmir',
    'India',
    NULL,
    '#00843D',
    '#FFFFFF',
    TRUE
);

INSERT INTO categories
(
    restaurant_id,
    name,
    description,
    display_order,
    active
)
VALUES
(1,'Breakfast','Morning Specials',1,TRUE),
(1,'Main Course','Main Dishes',2,TRUE),
(1,'Beverages','Hot and Cold Drinks',3,TRUE),
(1,'Desserts','Sweet Items',4,TRUE);

INSERT INTO menu_items
(
    restaurant_id,
    category_id,
    name,
    description,
    price,
    veg,
    available,
    active,
    display_order,
    preparation_time
)
VALUES
(1,1,'Kashmiri Kahwa','Traditional Kashmiri Tea',80.00,TRUE,TRUE,TRUE,1,5),

(1,1,'Bread Omelette','Egg Omelette with Bread',120.00,FALSE,TRUE,TRUE,2,10),

(1,2,'Chicken Biryani','Hyderabadi Style',320.00,FALSE,TRUE,TRUE,1,20),

(1,2,'Paneer Butter Masala','Creamy Paneer Curry',260.00,TRUE,TRUE,TRUE,2,18),

(1,3,'Cold Coffee','Fresh Cold Coffee',140.00,TRUE,TRUE,TRUE,1,5),

(1,3,'Fresh Lime Soda','Refreshing Drink',90.00,TRUE,TRUE,TRUE,2,4),

(1,4,'Gulab Jamun','Indian Dessert',70.00,TRUE,TRUE,TRUE,1,3),

(1,4,'Ice Cream','Vanilla Ice Cream',110.00,TRUE,TRUE,TRUE,2,2);