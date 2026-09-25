-- Sample restaurants, menus and limited daily stock. A repeatable migration: Flyway re-runs it
-- whenever this file changes, after all versioned migrations, so every statement must be safe
-- to run again. Rows use fixed ids and ON CONFLICT, and the sequences are moved past them.
-- Only loaded where FLYWAY_LOCATIONS includes classpath:db/seed (the default for demo setups).
-- Fictional restaurants; the pictures are illustrations generated from code (doordash-app/public/food).

INSERT INTO restaurants (id, name, address, image_url, phone)
VALUES (1, 'Ember & Bun', 'Smash burgers, fries and shakes', '/food/cover-burgers.svg', '(555) 010-0101'),
       (2, 'Stone Pot Tofu House', 'Korean soft tofu stews and pancakes', '/food/cover-tofu.svg', '(555) 010-0102'),
       (3, 'Juniper Wok', 'Sichuan classics, wok-fired to order', '/food/cover-wok.svg', '(555) 010-0103')
ON CONFLICT (id) DO UPDATE
    SET name = EXCLUDED.name, address = EXCLUDED.address, image_url = EXCLUDED.image_url, phone = EXCLUDED.phone;

INSERT INTO menu_items (id, description, image_url, name, price, restaurant_id)
VALUES (1, 'Two smashed beef patties, aged cheddar, pickles and house sauce on a toasted brioche bun.', '/food/burger.svg', 'Double Smash Burger', 12.50, 1),
       (2, 'Black truffle mayo, gruyère and caramelised onions. Limited batch every day.', '/food/truffle-burger.svg', 'Truffle Smash Burger', 16.00, 1),
       (3, 'Buttermilk-brined thigh, spicy slaw, pickles.', '/food/chicken-sandwich.svg', 'Hot Chicken Sandwich', 11.25, 1),
       (4, 'Skin-on fries with sea salt and rosemary.', '/food/fries.svg', 'Rosemary Fries', 4.75, 1),
       (5, 'Vanilla bean soft serve, malt and a cherry on top.', '/food/shake.svg', 'Vanilla Malt Shake', 6.00, 1),
       (6, 'Silken tofu, kimchi and pork belly in a bubbling chili broth, with rice.', '/food/tofu-stew.svg', 'Kimchi Soft Tofu Stew', 15.50, 2),
       (7, 'Shrimp, clams and squid in a mild seafood broth, with rice.', '/food/seafood-stew.svg', 'Seafood Soft Tofu Stew', 17.00, 2),
       (8, 'Crispy scallion and seafood pancake. Made in small batches.', '/food/pancake.svg', 'Seafood Scallion Pancake', 18.50, 2),
       (9, 'Soy-glazed short ribs, grilled and sliced, with rice.', '/food/short-ribs.svg', 'Galbi Short Ribs', 27.00, 2),
       (10, 'Silken tofu, minced pork, fermented bean paste and Sichuan pepper.', '/food/mapo-tofu.svg', 'Mapo Tofu', 14.00, 3),
       (11, 'Pork and chive dumplings in chili oil and black vinegar.', '/food/dumplings.svg', 'Chili Oil Dumplings', 10.50, 3),
       (12, 'Hand-pulled noodles, sesame paste, chili oil and crushed peanuts.', '/food/dan-dan.svg', 'Dan Dan Noodles', 13.00, 3),
       (13, 'Blistered green beans with garlic and preserved vegetables.', '/food/green-beans.svg', 'Dry-Fried Green Beans', 11.00, 3)
ON CONFLICT (id) DO UPDATE
    SET description = EXCLUDED.description, image_url = EXCLUDED.image_url, name = EXCLUDED.name,
        price = EXCLUDED.price, restaurant_id = EXCLUDED.restaurant_id;

-- Limited items: every checkout that includes them competes for these rows. Existing stock is
-- left alone (re-running this file must not restock a dish that has been selling).
INSERT INTO inventory (menu_item_id, available)
VALUES (2, 12),
       (8, 8),
       (10, 20)
ON CONFLICT (menu_item_id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('restaurants', 'id'), (SELECT max(id) FROM restaurants));
SELECT setval(pg_get_serial_sequence('menu_items', 'id'), (SELECT max(id) FROM menu_items));
