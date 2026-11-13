# Phase 4: Ratings and reviews

**Status:** Built.
**Service:** rating-service (:8084), database `rating_db`.
**Tech debt:** [techdebt/ratings.md](../techdebt/ratings.md).
**API:** [Rating Service API](../reference/api.md#rating-service-api) · **Schema:** [Rating Service Schema](../reference/data-model.md#rating-service-schema)

## What was built

- Reviews with star ratings, one review per user per product (Flyway `V3__rating_one_review_per_user`),
  review votes and rating summaries.
- `rating.events`, consumed by product-service to keep the denormalized `average_rating`,
  `rating_count`, `review_count` and `rating_distribution` columns current.
- The storefront review UI.
- `infra/scripts/backfill-product-ratings.sh`, a dev-only repair for seed ratings that were inserted
  without publishing events ([TD-RT-01](../techdebt/ratings.md#td-rt-01)).

## Changed since

- Phase 7 added customer Q&A, review photos and review filters (`V4__qa_and_review_media`) and admin
  review moderation.
- Phase 10 added fraud flags for fake reviews (`V5__rating_fraud_flags`).
