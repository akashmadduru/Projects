# Phase 7: Decision Log

**See also:** [Feature](Phase7-Admin-Feature.md), [Changes](Phase7-Admin-Changes.md)

## D1. Each service owns its admin endpoints; no admin BFF
The dashboard fans out from the browser (order stats, user stats, inventory, products) in parallel and renders each panel independently.
**Why:** No new service, no new Feign calls, and one service failing degrades one panel rather than the whole page.
**Rejected:** An aggregator endpoint in order-service. It would add three Feign dependencies to the service that is already the most coupled.

## D2. User management lives in auth-service
Roles and status are columns of `auth.users` and `auth.user_roles`, and auth-service mints the JWT `roles` claim from them.
**Why:** Any other owner would need a sync channel between two sources of truth for privileges.

## D3. Last-admin guard uses a row lock on `ROLE_ADMIN`
`RoleRepository.lockByName("ROLE_ADMIN")` with `PESSIMISTIC_WRITE` runs at the start of every status or role change, followed by a count of active admins.
**Why:** A count-then-update without a lock lets two concurrent requests each see "2 admins" and both demote. Serialising on one reference row is cheap, because admin changes are rare.
**Rejected:** A `SERIALIZABLE` transaction, which adds retry handling everywhere, and a DB trigger, which puts business logic in SQL with no error code.

## D4. Self-modification is refused outright
**Why:** It prevents accidental self-lockout and makes every privilege change involve two people. It also makes "the last admin demotes themselves" impossible without relying on D3.

## D5. Revoke refresh tokens on disable or role removal, not on grant
**Why:** Removing access should take effect as soon as possible. A grant is harmless to delay, and it shows up on the user's next refresh. Access-token revocation is deferred (TD-ADM-02).

## D6. Role change replaces the whole set
`PUT /roles {roles: [...]}` rather than grant/revoke endpoints.
**Why:** It matches the checkbox UI, is idempotent, and gives one place to run the last-admin check.

## D7. URL-level `/api/admin/**` rules at both the gateway and auth-service
**Why:** auth-service has no method security enabled, so a URL rule protects any future controller there by default. The gateway rule is defence in depth for services that forget an annotation. Trade-off: TD-ADM-13.

## D8. Payments ledger is read-only
**Why:** Refunds must keep order, stock and payment state consistent. The return flow (`ReturnService.refund`) already does this. A second refund path from the ledger would bypass restocking and the order's `refundedAmount`.

## D9. Review delete reuses `DELETE /api/ratings/{id}`
**Why:** That endpoint already allows ADMIN and publishes the event that recomputes the product's averages. A second delete path would have to duplicate both.

## D10. Category delete refused while in use (409), with deactivate as the normal path
**Why:** Both references are foreign keys. Cascading would orphan products from navigation, and the previous behaviour was a raw integrity error.

## D11. Fixed `CategoryController` RBAC as part of this change
Changed `hasAnyAuthority('CATALOG_MANAGER','ADMIN')` to `hasAnyRole(...)`.
**Why:** The Categories screen cannot work without it, and the fix aligns the controller with `ProductController` and the shared `RolesClaimAuthoritiesConverter` (which emits `ROLE_*`). This is a behaviour change only for requests that were always 403.
