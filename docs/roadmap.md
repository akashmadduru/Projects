# Product roadmap

The owner's gap analysis against Amazon and Flipkart, and what comes next. Phases 1–9 are built
(prototype-grade from Phase 5 on), and Phase 10 is in the code but unverified. Every phase's
feature list, with status and tech-debt links, is in [phases/](phases/README.md).

Size: **S** = under a day, **M** = a few days, **L** = a week or more.

## Where the product stands (2026-11-14)

| Area | Have today | Biggest remaining gap |
|---|---|---|
| Browse and search | Keyword search with autocomplete, typo tolerance (Postgres full-text + trigram), facets, variants, deals, recommendations, compare, sitemap | In-memory ranking over capped id lists ([TD-P7-03](techdebt/phase7.md#td-p7-03)); no SSR/prerender |
| Product page | Gallery and zoom, variants, Q&A, review photos, offers, pincode delivery check, buy box across sellers | No 360° images; bank offers and EMI are display-only |
| Cart and checkout | Buy Now, save for later, coupons, coins, wallet, COD, shipping and GST, risk checks | Stripe live mode never exercised; only SIMULATED payments verified ([TD-P6-02](techdebt/phase6.md#td-p6-02)) |
| After purchase | Full order lifecycle, split shipments, tracking, returns and refunds, invoices, notifications, support tickets | No outbox: post-commit side effects can be lost ([TD-P6-01](techdebt/phase6.md#td-p6-01)) |
| Accounts | Email/password, Google SSO, OTP login, password reset, 2FA, session management, privacy export and deletion | SMS is a dev stub; login not blocked for an unverified email |
| Admin and sellers | Full admin console, seller portal, payouts, ads, audit log, support desk, FX rates | Seller products go live unmoderated ([TD-P9-03](techdebt/phase9.md#td-p9-03)) |
| Retention | Membership, coins, gift cards, subscriptions, referrals, lists, notification centre, campaigns, web push | Web push never delivered to a real push service |

The most important fact: **almost nothing from Phase 5 on has automated tests**, and the store
cannot yet take real money.

## What's next

The ordered plan is in the [technical audit](audit/technical-audit.md#e-implementation-roadmap). In short:

1. **Critical fixes:** simulated payment must fail closed; stop logging OTP codes; purge the DB dumps from git history and rotate credentials; atomic webhook dedup; get CI green.
2. **Real payments:** a provider port, plus Razorpay and/or Stripe end to end.
3. **Tests** for payment and order placement, and for every Phase 5–10 service.
4. **Reliability:** transactional outbox for `order.events`, dead-letter topics everywhere, gateway rate limiting.
5. **Production:** Helm, Terraform and observability ([deploy-aws](guides/deploy-aws.md)).
6. **Phase 10 verification:** an end-to-end run of every Phase 10 feature, then a WCAG 2.2 AA audit (the one Phase 10 item with no work found).

## Working rules

Set 2026-11-12 for Phases 6 to 10: build features as fast as possible, with no tests and no per-feature
HLD/LLD. Every shortcut goes into the [tech debt register](techdebt/README.md) with a stable ID in the
same change. When a roadmap item closes a tech-debt item, remove the item from the register.
