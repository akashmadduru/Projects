# Seeded-defect test 2: F-20261119-04 slice 1 (review-input-b.diff)
Reviewer: forge-security (fresh instance, not told), 45,514 tokens, 54 s. Seeds placed in lines no test asserts textually.
| Seed | Injected | Caught | Severity given |
|---|---|---|---|
| S1 | settle UPDATE lost `AND status = 'PENDING'` (refund state not once-only → double release → double refund) | yes | blocker |
| S2 | refund lookup lost `FOR UPDATE` | yes | minor (correct: the payment-row lock serialises writers) |
Detection 2/2. **Caveat:** the reviewer used the Javadoc ("PENDING to a terminal status, once"), design §2 and
test names (settleIsOnce) as oracles. Seeds that also update the comment/design would be a harder test.
Additional findings from this pass that apply to the REAL code (to compare with the real reviewers): clamp
`.max(ZERO)` hides ledger mismatch; stale Payment snapshot across parked events; LIMIT 1 on non-unique
provider_payment_id; V7 migration lock profile.
