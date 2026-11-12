# Tech Debt: Ratings & reviews

Index: [README](README.md)

<a id="td-rt-01"></a>
## TD-RT-01 Demo product averages disagree with rating-service
- **Severity / Effort:** Med / S
- **Where:** `infra/scripts/backfill-product-ratings.sh` (default run updated only the 18 products that have ratings in `rating_db`).
- **Risk:** The other 17 seed products keep catalog-seeded demo averages, so their cards show ratings with no reviews behind them.
- **Fix:** Run `infra/scripts/backfill-product-ratings.sh --reset-unrated` (discards the demo numbers), or seed matching reviews.

<a id="td-rt-02"></a>
## TD-RT-02 No scheduled reconcile of product rating fields
- **Severity / Effort:** Med / M
- **Where:** `services/product-service/.../event/RatingEventListener.java`, `client/RatingFeignClient.java`.
- **Risk:** If rating-service is down when an event is processed, the product stays stale until the next event for that product or a manual backfill.
- **Fix:** Periodic reconcile job, or retry topic / DLT for failed summary fetches.

<a id="td-rt-03"></a>
## TD-RT-03 Rating updates bump `Product.version`
- **Severity / Effort:** Low / S
- **Where:** `ProductCatalogService.applyRatingSummary`.
- **Risk:** A concurrent admin product edit can get a 409 and must retry.
- **Fix:** Move rating aggregates to a separate table/entity, or update via a query that skips the version.

<a id="td-rt-04"></a>
## TD-RT-04 Reviewer display name is a snapshot
- **Severity / Effort:** Low / S
- **Where:** rating-service `RatingService` (user-service profile -> auth-service `/api/auth/me` -> "Verified buyer").
- **Risk:** Later profile renames do not propagate to existing reviews.
- **Fix:** Consume a user-profile-updated event, or resolve names at read time with caching.

<a id="td-rt-05"></a>
## TD-RT-05 Seed reviews have synthetic authors
- **Severity / Effort:** Low / S
- **Where:** `services/rating-service/.../db/migration/V2__rating_seed_data.sql`.
- **Risk:** Seed reviews are flagged verified but no real user owns them; only an admin can edit or delete them.
- **Fix:** Mark seed rows unverified or replace with reviews from seeded demo accounts.

<a id="td-rt-06"></a>
## TD-RT-06 Duplicated event/summary shapes, no contract tests
- **Severity / Effort:** Low / M
- **Where:** product-service `event/RatingEvent.java`, `client/RatingSummary.java` duplicate rating-service shapes.
- **Risk:** A field rename in rating-service silently breaks product denormalization (the original bug was this class of drift).
- **Fix:** Consume DTOs from `rating-service-api`, add a contract test.

<a id="td-rt-07"></a>
## TD-RT-07 Frontend cleanups
- **Severity / Effort:** Low / S
- **Where:** `apps/product-catalog/src`: legacy `ProductRating` type, `productApi.getRatings`, `useProductRatings` unused; vote handler re-fetches all loaded pages; header `RatingChip` can lag the live summary until reload; `ReviewForm` / `StarPicker` / `ReviewsSection` untested.
- **Fix:** Remove dead code, use returned counts after a vote, refresh product after review writes, add component tests.
