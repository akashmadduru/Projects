-- Phase 8: abandoned-cart reminders + multiple/shareable lists.

ALTER TABLE cart.carts
    ADD COLUMN email            varchar(320),
    ADD COLUMN reminder_sent_at timestamptz;

CREATE INDEX ix_carts_reminder ON cart.carts (updated_at) WHERE email IS NOT NULL;

-- The default list ("Wishlist") keeps its items in cart.wishlist_items so /api/wishlist/** is unchanged;
-- other lists keep theirs in cart.list_items.
ALTER TABLE cart.wishlist_items
    ADD COLUMN note varchar(300);

CREATE TABLE cart.lists
(
    id          uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    user_id     uuid         NOT NULL,
    name        varchar(60)  NOT NULL,
    is_default  boolean      NOT NULL DEFAULT false,
    visibility  varchar(10)  NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE', 'SHARED')),
    share_token varchar(40) UNIQUE,
    owner_name  varchar(60),
    created_at  timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_lists_user ON cart.lists (user_id);
CREATE UNIQUE INDEX ux_lists_one_default ON cart.lists (user_id) WHERE is_default;

CREATE TABLE cart.list_items
(
    id            uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    list_id       uuid           NOT NULL REFERENCES cart.lists (id) ON DELETE CASCADE,
    product_id    uuid           NOT NULL,
    variant_id    uuid,
    variant_label varchar(200),
    variant_key   uuid GENERATED ALWAYS AS (COALESCE(variant_id, '00000000-0000-0000-0000-000000000000'::uuid)) STORED,
    name          varchar(300)   NOT NULL,
    price         numeric(19, 4) NOT NULL CHECK (price >= 0),
    currency      char(3)        NOT NULL,
    image_url     varchar(2048),
    note          varchar(300),
    added_at      timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT ux_list_items UNIQUE (list_id, product_id, variant_key)
);
