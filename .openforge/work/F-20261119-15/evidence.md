
## PR-A guard demos
Method: the new tests were run against base-like production code (mutants, never committed): OrderService restored to base
b7f5b97 (no ENSURE row, for AC-1/AC-2); the relay `case ENSURE_COD_PAYMENT -> {}` (no ensure call, for AC-3); and the relay
ignoring its narrowed kind list (a naive relay that takes kinds it does not know, for C9). The C9 mutant is needed because the
base relay already skips unknown kinds, so the test passes on it by design; it guards against a regression. Real assertion failures:

- AC-2 `OrderPlacementIntegrationTest.codPaymentDownStillPlaces` (placement with payment-service down records the repair durably):
  `Expecting actual: [] to contain exactly (and in same order): ["PENDING|true"] but could not find the following elements: ["PENDING|true"]`
- AC-1 `OrderPlacementIntegrationTest.codCreatesRowAndAttaches`:
  `Expecting actual: [] to contain exactly (and in same order): ["DONE"]`
- AC-3 `CodPaymentRowsIntegrationTest.relayCreatesRowOnce` (the relay creates the row once):
  `WantedButNotInvoked: PaymentFeignClient.ensureRow(<any>) ... Actually, there were zero interactions with this mock.`
- C9 `OutboxIntegrationTest.oldBuildRelayToleratesEnsureKind`:
  `Expecting actual: ["DONE:1"] to contain exactly (and in same order): ["PENDING:0"]`
Positive sanity: the same tests pass on the real head (86 run, only CartClearIntegrationTest context failures seen in a filtered
multi-class run; CartClear passes alone at base and at head, see report). The oldBuild kind list is asserted to contain
RESTORE_STOCK and not ENSURE_COD_PAYMENT; `cardWritesNoEnsureRow` is the positive control for the ENSURE row queries.

## CEO verify, PR-A at 60a105077f734f67cef5fee76ba5682f023d8c65 (detached worktree F-20261119-15A, 2026-11-23)
```
forge-verify: 15 changed file(s) in C:/Users/Akash/Desktop/Projects/.openforge/worktrees/F-20261119-15A (base b7f5b9746228)
  PASS gradle :services:order-service:order-service-app:test :services:payment-service:payment-service-app:test (exit 0)
      BUILD SUCCESSFUL in 1m 2s
      gradle_rc=0
forge-verify: PASS (1 checks)
rc=0
```
- PR-A production lines b7f5b97..60a1050: 242 (CEO recount).

## PR-B guard demos
Each guard was run against a mutant of the PR-B code (the base 60a1050 has no PR-B behaviour at all), then the mutant was reverted.
Mutant M1 = `if (credit != null && credit.found())` replaced by `if (false)` in `CodPaymentRows.prepareRefund` (legacy credit check removed).
- Legacy exact match, different user: `ReturnRefundLegacyCodIntegrationTest.differentUser` -> `java.lang.AssertionError: Expecting code to raise a throwable.` (no 409; the refund went on).
- Different amount (99.99 vs 100.00) and different currency (USD): same failure, "Expecting code to raise a throwable."
- Exact match: `exactMatch` -> `NeverWantedButInvoked: PaymentFeignClient.refund(<any>, <any>)` (the mutant sends the refund).
  Real code: all four pass with `never().refund` and `never().walletCredit` asserted, return REFUNDED on an exact match (amount "100" vs 100.00 by compareTo).
- Refund cap (AC-12): mutant M2 = `if (rest.compareTo(cardRoom) > 0)` -> `if (false)` in `OrderStore.freezeLegs`: `capHoldsAcrossReturns` -> `expected: 100.00 but was: 99.99` (wallet credits no longer sum to 100).
- Cancelled order, no COLLECTED row: mutant M3 = `if (spec.cancelled())` -> `if (false)` in `CodPaymentRows.ensure`: `relayAbandonedOrder` -> `NeverWantedButInvoked: PaymentFeignClient.ensureRow(<custom argument matcher>)`; `cancelledNotCollected` and `cancelledSkipped` fail with an NPE from the unstubbed ensureRow (the call was made).
- GET endpoint 401/403: mutant M4 = `InternalTokenFilter.shouldNotFilter` returns true: `InternalWalletControllerTest.tokenRequired` -> `Expecting actual: 200 to be in: [401, 403]`. Positive sanity in the same test: the right token returns the credit (and `found` asserts the body fields; the "doesNotContain" checks have positive cases).
- sec-fix 1: mutant M5 = PaymentFeignClient configuration back to `InternalFeignConfig.class` only: `PaymentFeignClientHeadersTest.clientReferencesBothConfigs` -> `Expecting Class[]: [InternalFeignConfig] to contain: [InternalFeignConfig, NoUserTokenFeignConfig]`. `withoutStripperBearerLeaks` is the positive sanity (bearer forwarded without the stripper).

## CEO verify at 8ff29132fe435135becbdb0ab53f431448d4fa87 (PR-B head, 2026-11-23)
```
forge-verify: 26 changed file(s) in C:/Users/Akash/Desktop/Projects/.openforge/worktrees/F-20261119-15 (base b7f5b9746228)
  PASS gradle :services:order-service:order-service-app:test :services:payment-service:payment-service-app:test (exit 0)
      BUILD SUCCESSFUL in 1m 18s
      gradle_rc=0
forge-verify: PASS (1 checks)
rc=0
```
- PR-B production lines 60a1050..8ff2913: 177 (CEO recount).

## QA (8ff2913)
Mapping only: no code changed, no gradle run. Head 8ff29132fe435135becbdb0ab53f431448d4fa87, worktree clean. Each test below was
found in the JUnit XML under services/ build/test-results/test/ with no failure or error element (suite totals: failures=0, errors=0, skipped=0).
Timestamps (UTC): order-service suites 07:48Z-08:01Z (after the head commit at 07:47:56Z); payment-service suites 07:09Z-07:11Z
and gateway suites 07:34Z (both before the head commit time; the CEO verify at head ran only the order and payment test tasks in 1m18s,
so the payment XML is most likely restored from the Gradle build cache for identical inputs, not re-executed; gateway was not part of that verify).
XML dirs: O = services/order-service/order-service-app/build/test-results/test/, P = services/payment-service/payment-service-app/build/test-results/test/, G = services/gateway/build/test-results/test/.

AC-1: pass — OrderPlacementIntegrationTest "AC-1: a COD order gets its row inline, payment_id is set and the repair row is DONE" (XML: O TEST-com.acme.ecommerce.order.service.OrderPlacementIntegrationTest.xml, 2026-11-23T07:57:21Z)
AC-2: pass — OrderPlacementIntegrationTest "AC-2: payment-service down still places the COD order; the repair is recorded" and "AC-2: a CARD order writes no ENSURE row (positive control for the COD cases)" (XML: O TEST-com.acme.ecommerce.order.service.OrderPlacementIntegrationTest.xml)
AC-3: pass — CodPaymentRowsIntegrationTest "AC-3: the relay creates the missing row once; a second pass makes no call" (XML: O TEST-com.acme.ecommerce.order.service.CodPaymentRowsIntegrationTest.xml, 2026-11-23T07:56:04Z)
AC-4: pass — CodEnsureConcurrencyIntegrationTest "AC-4: 8 threads with mixed amounts make one row; the first amount wins; no money" and "AC-4: create after create keeps the first amount" (XML: P TEST-com.acme.ecommerce.payment.service.CodEnsureConcurrencyIntegrationTest.xml, 2026-11-23T07:09:24Z)
AC-5: pass — CodEnsureConcurrencyIntegrationTest "AC-5: COD stays COD_PENDING through create and cancelUnpaid; only codCollected pays" (XML: P TEST-com.acme.ecommerce.payment.service.CodEnsureConcurrencyIntegrationTest.xml)
AC-6: pass — CodPaymentRowsIntegrationTest "AC-6/7: a collected rowless legacy order: ensure with its figures, then collect" (XML: O TEST-com.acme.ecommerce.order.service.CodPaymentRowsIntegrationTest.xml)
AC-7: pass — CodPaymentRowsIntegrationTest "AC-6/7: a collected rowless legacy order: ensure with its figures, then collect" and "AC-7: a COD_PENDING (undelivered) legacy order gets a row but is never collected" (XML: O TEST-com.acme.ecommerce.order.service.CodPaymentRowsIntegrationTest.xml)
AC-8: pass — CodPaymentRowsIntegrationTest "AC-8: two threads ensuring one order: one CREATED, one EXISTING, one attach" and "C1: ensure twice gives CREATED then EXISTING, one remote call and one attach" (XML: O TEST-com.acme.ecommerce.order.service.CodPaymentRowsIntegrationTest.xml)
AC-9: pass — CodPaymentRowsIntegrationTest "AC-9: a cancelled order gets no row and no collected mark" and "AC-7: a COD_PENDING (undelivered) legacy order gets a row but is never collected"; ReturnRefundLegacyCodIntegrationTest "COD_PENDING (undelivered) return: route NONE, no lookup, no ensure, no money" (XML: O TEST-com.acme.ecommerce.order.service.CodPaymentRowsIntegrationTest.xml; O TEST-com.acme.ecommerce.order.service.ReturnRefundLegacyCodIntegrationTest.xml, 2026-11-23T07:59:58Z)
AC-10: pass — ReturnRefundLegacyCodIntegrationTest "positive control: no legacy credit, rowless COD return -> ensure, refund once" (ensure, one refund of 100.00 to WALLET under key return-<id>, REFUNDED, second refund() adds no call) and "an exact legacy credit (amount by compareTo): no refund call, REFUNDED, retry quiet" (XML: O TEST-com.acme.ecommerce.order.service.ReturnRefundLegacyCodIntegrationTest.xml)
AC-11: pass — CodPaymentRowsIntegrationTest "AC-11: a re-armed COD_COLLECTED row ensures the row, then marks it collected" (XML: O TEST-com.acme.ecommerce.order.service.CodPaymentRowsIntegrationTest.xml)
AC-12: pass — ReturnRefundLegacyCodIntegrationTest "AC-12: 3 x 100 COD with wallet 100, three returns: credits sum 100, refunds sum 200" and ReturnRefundIntegrationTest "two sibling returns refunded at once freeze legs that stay inside the order totals" (XML: O TEST-com.acme.ecommerce.order.service.ReturnRefundLegacyCodIntegrationTest.xml; O TEST-com.acme.ecommerce.order.service.ReturnRefundIntegrationTest.xml, 2026-11-23T07:58:38Z)
AC-13: pass — ReturnRefundLegacyCodIntegrationTest "rowless and payment-service down on the ensure: 503, PICKED_UP, no money" and "lookup 404 and no row: 503, PICKED_UP, no ensure, no money"; ReturnServiceRefundTest "AC-5: payment-service down is 503 and nothing is finalized" (XML: O TEST-com.acme.ecommerce.order.service.ReturnRefundLegacyCodIntegrationTest.xml; O TEST-com.acme.ecommerce.order.service.ReturnServiceRefundTest.xml, 2026-11-23T08:01:20Z)
AC-14: pass — CodPaymentRowsIntegrationTest "AC-14: an ensure blocked on payment-service holds no transaction or row lock" and "AC-14: two repair rows, one failing: the other still goes DONE" (XML: O TEST-com.acme.ecommerce.order.service.CodPaymentRowsIntegrationTest.xml)
AC-15: pass — GatewayEdgeIntegrationTest.internalPathsAreNotRouted, display name "/internal/** is not routed: anonymous is 401, an authenticated caller gets 404" (XML: G TEST-com.acme.ecommerce.gateway.GatewayEdgeIntegrationTest$RouteAuthorizationContract.xml, 2026-11-23T07:34:04Z); order-first/payment-first compatibility is further shown by OutboxIntegrationTest "C9: an old-build relay (kind unknown to it) leaves an ENSURE row PENDING, no side effects" (XML: O TEST-com.acme.ecommerce.order.outbox.OutboxIntegrationTest.xml) and ReturnRefundLegacyCodIntegrationTest "lookup 404 (old payment-service): a row is there -> refund proceeds" (XML: O TEST-com.acme.ecommerce.order.service.ReturnRefundLegacyCodIntegrationTest.xml)

Supporting (PR-B security): InternalWalletControllerTest "a missing or wrong X-Internal-Token is refused, even for an existing credit" (XML: P TEST-com.acme.ecommerce.payment.web.InternalWalletControllerTest.xml); PaymentFeignClientHeadersTest "the client is wired to the token config and the stripper" (XML: O TEST-com.acme.ecommerce.order.client.PaymentFeignClientHeadersTest.xml).
C8 gateway item: GatewayEdgeIntegrationTest.internalPathsAreNotRouted (same XML as AC-15): pass.
All 15 ACs have a passing test. No defects found. Caveat: AC-4/AC-5 rest on payment XML timestamped before the head commit (see above).
