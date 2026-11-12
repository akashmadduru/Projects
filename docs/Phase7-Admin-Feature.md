# Phase 7: Admin console completion

**Date:** 2026-11-12
**See also:** [Changes](Phase7-Admin-Changes.md), [Decision Log](Phase7-Admin-DecisionLog.md), [Tech debt](../techdebt/admin.md)

Built under the roadmap's working rules: no tests and no per-feature HLD/LLD. Every shortcut is recorded in [techdebt/admin.md](../techdebt/admin.md).

## What an operator can do now

| Screen | Route | Who | What |
|---|---|---|---|
| Overview | `/dashboard` | all staff | Revenue today and over 30 days (net of refunds, per currency), AOV, open orders, returns in progress, products, low stock, customers. Also an orders-by-status chart, the 5 most recent orders and a restock list. CATALOG_MANAGER sees only the catalog and stock panels. |
| Products | `/products`, `/products/new` | all staff (writes: CATALOG_MANAGER, ADMIN) | Existing CRUD, plus a **category picker** on create and edit. |
| Categories | `/categories` | all staff (writes: CATALOG_MANAGER, ADMIN) | Two-level tree; create, edit, add subcategory, activate/deactivate, delete. Delete is refused while products or subcategories use the category. |
| Inventory | `/inventory` | all staff | Unchanged. |
| Orders / Returns / Coupons | unchanged | ADMIN | Unchanged. Refunds still run from Returns. |
| Payments | `/payments` | ADMIN | Ledger filtered by status, method and order id; a detail view with refund history. Read-only. |
| Customers | `/customers`, `/customers/:id` | ADMIN | Search by email or name, filter by status and role. The detail page shows profile, roles, enable/disable/unlock and the customer's last 10 orders. |
| Reviews | `/reviews` | ADMIN | Search across all products by text, reviewer, star rating and product id; delete a review (the product rating is recalculated). |
| Settings | `/settings` | all staff | Shows the current user's effective access. |

## Safety rules (server-enforced)

- `/api/admin/**` requires `ROLE_ADMIN` at the **gateway** and again in each owning service.
- An admin cannot change their **own** status or roles (422 `SELF_MODIFICATION`).
- The **last active admin** cannot be disabled or demoted (422 `LAST_ADMIN`). The `ROLE_ADMIN` row is locked for the length of the transaction, so two concurrent demotions cannot both succeed.
- Disabling an account, or removing any of its roles, **revokes all refresh tokens**. Access tokens already issued expire on their own TTL (TD-ADM-02).
- Only ACTIVE and DISABLED can be set. LOCKED belongs to the login-attempt policy and can only be cleared by "Unlock" (which sets ACTIVE).

## New APIs

| Method | Path | Service | Notes |
|---|---|---|---|
| GET | `/api/admin/orders/stats` | order | `{ordersByStatus, returnsByStatus, revenueToday[], revenueLast30Days[]}` |
| GET | `/api/admin/orders?userId=` | order | New filter on the existing list |
| GET | `/api/admin/users?q=&status=&role=&page=&size=` | auth | `PagedModel<AdminUserResponse>`, max 50 per page |
| GET | `/api/admin/users/stats` | auth | `{total, byStatus}` |
| GET | `/api/admin/users/{id}` | auth | |
| PUT | `/api/admin/users/{id}/status` | auth | `{status: ACTIVE\|DISABLED}` |
| PUT | `/api/admin/users/{id}/roles` | auth | `{roles: ["ADMIN"\|"CATALOG_MANAGER"\|"VIEWER", ...]}`, replaces the whole set, at least one role |
| GET | `/api/admin/ratings?productId=&rating=&q=` | rating | Deletes go through the existing `DELETE /api/ratings/{id}` (admins already allowed) |
| GET | `/api/admin/payments?status=&method=&orderId=` | payment | Same JSON shape as `PagedModel` |
| GET | `/api/admin/payments/{id}` | payment | `{payment, refunds[]}` |
| GET | `/api/admin/payments/stats` | payment | Count by status |

## Bugs fixed along the way

1. **Category writes always returned 403.** `CategoryController` checked `hasAnyAuthority('CATALOG_MANAGER','ADMIN')`, but the shared JWT converter issues `ROLE_`-prefixed authorities. It now uses `hasAnyRole`, matching `ProductController`.
2. **Editing a product cleared its category.** Product `PUT` is full-replace for `categoryId`, and the admin form never sent it. Both product forms now send it.
3. **Deleting a category in use returned a raw integrity error.** It now returns a clean 409 that names the reason.

## How to verify by hand

1. Sign in as ADMIN, open Overview, and check the tiles against `/orders` and `/returns`.
2. Customers → open another user → toggle CATALOG_MANAGER → Save. The user's next refresh carries the new role.
3. Try to remove ADMIN from yourself: the controls are disabled, and calling the API directly returns 422.
4. With exactly one admin, call `PUT /api/admin/users/{thatAdmin}/roles {"roles":["VIEWER"]}` from a second admin account that has just been demoted. Expect 422 `LAST_ADMIN`.
5. Sign in as a VIEWER token and call `GET /api/admin/users`. Expect 403 from the gateway.
6. Categories: create a parent, add a sub, assign it to a product, then try to delete it. Expect a 409 message.
