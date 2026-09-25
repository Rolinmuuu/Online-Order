-- Baseline schema. Migrations are append-only: never edit a file once it has run anywhere;
-- change the schema with a new V<n>__*.sql (see docs/ARCHITECTURE.md, ADR 9).

CREATE TABLE customers
(
    id         SERIAL PRIMARY KEY   NOT NULL,
    email      TEXT UNIQUE          NOT NULL,
    enabled    BOOLEAN DEFAULT TRUE NOT NULL,
    password   TEXT                 NOT NULL,
    first_name TEXT,
    last_name  TEXT
);


CREATE TABLE carts
(
    id          SERIAL PRIMARY KEY NOT NULL,
    customer_id INTEGER UNIQUE     NOT NULL,
    total_price NUMERIC            NOT NULL,
    version     BIGINT             NOT NULL DEFAULT 0,
    CONSTRAINT fk_customer FOREIGN KEY (customer_id) REFERENCES customers (id) ON DELETE CASCADE
);


CREATE TABLE restaurants
(
    id        SERIAL PRIMARY KEY NOT NULL,
    name      TEXT               NOT NULL,
    address   TEXT,
    image_url TEXT,
    phone     TEXT
);


CREATE TABLE menu_items
(
    id            SERIAL PRIMARY KEY NOT NULL,
    restaurant_id INTEGER            NOT NULL,
    name          TEXT               NOT NULL,
    price         NUMERIC            NOT NULL,
    description   TEXT,
    image_url     TEXT,
    CONSTRAINT fk_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE
);


CREATE TABLE order_items
(
    id           SERIAL PRIMARY KEY NOT NULL,
    menu_item_id INTEGER            NOT NULL,
    cart_id      INTEGER            NOT NULL,
    price        NUMERIC            NOT NULL,
    quantity     INTEGER            NOT NULL,
    -- one row per item per cart (a global UNIQUE on menu_item_id let only one cart in the
    -- whole system hold a given dish)
    CONSTRAINT uq_order_items_cart_menu_item UNIQUE (cart_id, menu_item_id),
    CONSTRAINT fk_cart FOREIGN KEY (cart_id) REFERENCES carts (id) ON DELETE CASCADE,
    CONSTRAINT fk_menu_item FOREIGN KEY (menu_item_id) REFERENCES menu_items (id) ON DELETE CASCADE
);


CREATE TABLE authorities
(
    id        SERIAL PRIMARY KEY NOT NULL,
    email     TEXT               NOT NULL,
    authority TEXT               NOT NULL,
    CONSTRAINT fk_customer FOREIGN KEY (email) REFERENCES customers (email) ON DELETE CASCADE
);


CREATE INDEX idx_menu_items_restaurant_id ON menu_items(restaurant_id);
CREATE INDEX idx_order_items_cart_id ON order_items(cart_id);
CREATE INDEX idx_customers_email ON customers(email);




-- ─────────────────────────── Ordering (strongly consistent core) ───────────────────────────
-- Everything below lives in the same PostgreSQL database and changes inside ACID transactions.
-- Money is stored as integer cents (BIGINT), never as floating point.

-- Staff accounts allowed to run a restaurant's kitchen board.
CREATE TABLE restaurant_staff
(
    email         TEXT    NOT NULL REFERENCES customers (email) ON DELETE CASCADE,
    restaurant_id INTEGER NOT NULL REFERENCES restaurants (id) ON DELETE CASCADE,
    PRIMARY KEY (email, restaurant_id)
);

-- Limited daily stock. Items without a row are unlimited. The CHECK is the last line of
-- defence: whatever the application does, available can never go below zero.
CREATE TABLE inventory
(
    menu_item_id INTEGER PRIMARY KEY REFERENCES menu_items (id) ON DELETE CASCADE,
    available    INTEGER NOT NULL CHECK (available >= 0)
);

CREATE TABLE orders
(
    id            BIGSERIAL PRIMARY KEY,
    customer_id   INTEGER     NOT NULL REFERENCES customers (id),
    restaurant_id INTEGER     NOT NULL REFERENCES restaurants (id),
    status        TEXT        NOT NULL CHECK (status IN ('PLACED', 'PAID', 'ACCEPTED', 'READY', 'COMPLETED', 'CANCELLED')),
    total_cents   BIGINT      NOT NULL CHECK (total_cents >= 0),
    version       BIGINT      NOT NULL DEFAULT 0,
    pay_by        TIMESTAMPTZ NOT NULL,
    cancel_reason TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_orders_customer ON orders (customer_id, id DESC);
CREATE INDEX idx_orders_restaurant_status ON orders (restaurant_id, status, id);
-- Partial index: the expiry sweeper only ever looks at unpaid orders.
CREATE INDEX idx_orders_unpaid_pay_by ON orders (pay_by) WHERE status = 'PLACED';

-- Price and name are copied at checkout: later menu edits must not change past orders.
CREATE TABLE order_lines
(
    order_id         BIGINT  NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    menu_item_id     INTEGER NOT NULL REFERENCES menu_items (id),
    name             TEXT    NOT NULL,
    unit_price_cents BIGINT  NOT NULL CHECK (unit_price_cents >= 0),
    quantity         INTEGER NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (order_id, menu_item_id)
);

-- Append-only audit trail of every status change.
CREATE TABLE order_events
(
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT      NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    from_status TEXT,
    to_status   TEXT        NOT NULL,
    actor       TEXT        NOT NULL,
    reason      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_order_events_order ON order_events (order_id, id);

-- Idempotent checkout: the key is claimed by an INSERT in the checkout transaction itself,
-- so the key and the order it produced commit (or roll back) together.
CREATE TABLE idempotency_keys
(
    customer_id  INTEGER     NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    key          TEXT        NOT NULL,
    request_hash TEXT        NOT NULL,
    order_id     BIGINT REFERENCES orders (id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (customer_id, key)
);

-- Transactional outbox: side effects recorded in the same transaction as the state change,
-- delivered by a dispatcher that claims rows with FOR UPDATE SKIP LOCKED.
CREATE TABLE outbox
(
    id           BIGSERIAL PRIMARY KEY,
    topic        TEXT        NOT NULL,
    payload      JSONB       NOT NULL,
    attempts     INTEGER     NOT NULL DEFAULT 0,
    last_error   TEXT,
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_pending ON outbox (available_at, id) WHERE processed_at IS NULL;

-- What the outbox handlers produce in this demo (stands in for e-mail / push delivery).
CREATE TABLE notifications
(
    id          BIGSERIAL PRIMARY KEY,
    outbox_id   BIGINT      NOT NULL UNIQUE,
    customer_id INTEGER     NOT NULL,
    order_id    BIGINT      NOT NULL,
    message     TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ─────────────────────────── Payments ───────────────────────────

CREATE TABLE payments
(
    id           BIGSERIAL PRIMARY KEY,
    order_id     BIGINT      NOT NULL UNIQUE REFERENCES orders (id),
    amount_cents BIGINT      NOT NULL CHECK (amount_cents >= 0),
    status       TEXT        NOT NULL CHECK (status IN ('PENDING', 'CAPTURED', 'REFUNDED', 'FAILED')),
    provider_ref TEXT UNIQUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Webhook de-duplication: providers deliver at least once.
CREATE TABLE payment_events
(
    provider_event_id TEXT PRIMARY KEY,
    type              TEXT        NOT NULL,
    payment_id        BIGINT,
    received_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Double-entry ledger. Positive = debit, negative = credit. Every transaction (txn_id) must
-- sum to zero; entries are never updated or deleted, corrections are new transactions.
CREATE TABLE ledger_entries
(
    id           BIGSERIAL PRIMARY KEY,
    txn_id       UUID        NOT NULL,
    account      TEXT        NOT NULL,
    amount_cents BIGINT      NOT NULL CHECK (amount_cents <> 0),
    order_id     BIGINT REFERENCES orders (id),
    memo         TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ledger_txn ON ledger_entries (txn_id);
CREATE INDEX idx_ledger_account ON ledger_entries (account);
CREATE INDEX idx_ledger_order ON ledger_entries (order_id);

-- Checked at COMMIT (deferred), so a transaction may insert its legs one by one.
CREATE FUNCTION assert_ledger_txn_balanced() RETURNS trigger LANGUAGE plpgsql AS
'BEGIN
    IF (SELECT COALESCE(SUM(amount_cents), 0) FROM ledger_entries WHERE txn_id = NEW.txn_id) <> 0 THEN
        RAISE EXCEPTION ''ledger transaction % does not balance'', NEW.txn_id USING ERRCODE = ''23514'';
    END IF;
    RETURN NULL;
END';

CREATE CONSTRAINT TRIGGER ledger_txn_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_ledger_txn_balanced();

CREATE FUNCTION forbid_ledger_changes() RETURNS trigger LANGUAGE plpgsql AS
'BEGIN
    RAISE EXCEPTION ''ledger entries are append-only'' USING ERRCODE = ''42501'';
END';

CREATE TRIGGER ledger_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_changes();


