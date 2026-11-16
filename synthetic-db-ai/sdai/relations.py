"""Cross-service ("logical") references.

The microservices deliberately have NO cross-database foreign keys, so integrity across services
is an application-level contract.  These are the contracts discovered from column naming and the
service code; `validate` checks every one of them.
"""
from __future__ import annotations

# column name -> (parent service db, parent table, parent column)
LOGICAL_COLUMNS = {
    "user_id": ("auth_db", "auth.users", "id"),
    "purchaser_user_id": ("auth_db", "auth.users", "id"),
    "referee_user_id": ("auth_db", "auth.users", "id"),
    "referrer_user_id": ("auth_db", "auth.users", "id"),
    "redeemed_by": ("auth_db", "auth.users", "id"),
    "actor_user_id": ("auth_db", "auth.users", "id"),
    "assignee_user_id": ("auth_db", "auth.users", "id"),
    "product_id": ("product_db", "product.products", "id"),
    "variant_id": ("product_db", "product.product_variants", "id"),
    "offer_id": ("product_db", "product.offers", "id"),
    "category_id": ("product_db", "product.categories", "id"),
    "order_id": ("order_db", "orders.orders", "id"),
    "shipment_id": ("order_db", "orders.order_shipments", "id"),
    "address_id": ("user_db", "users.addresses", "id"),
    "seller_id": ("seller_db", "sellers", "id"),
    "warehouse_id": ("inventory_db", "inventory.warehouses", "id"),
    "subscription_id": ("order_db", "orders.subscriptions", "id"),
    "exchange_quote_id": ("promotion_db", "promotion.exchange_quotes", "id"),
    "return_id": ("order_db", "orders.order_returns", "id"),
}

# (db, table, column) overrides where the generic name above is wrong or the column is not nullable-safe
SKIP = {
    ("product_db", "product.products", "seller_id"),    # may be the seeded platform seller
    ("order_db", "orders.order_shipments", "seller_id"),
    ("inventory_db", "inventory.reservations", "seller_id"),
}
