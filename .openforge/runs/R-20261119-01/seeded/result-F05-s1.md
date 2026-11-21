# Seeded review result, F-20261119-05 slice 1 (2026-11-19)
Input: review-input-b.diff, the real 4ca6991b diff with 2 seeds. A fresh forge-reviewer reviewed it, was not told about the seeds, and was told not to read the worktree. Cost: 64,110 tokens, 16 tool calls, 114 s.

| Seed | Keyed severity | Caught? | Reported as | How it was found |
|---|---|---|---|---|
| S1: discard drops syncPrices (javadoc edited to match) | major | yes | major | Design section C and T2 say discard re-syncs prices. The design was the oracle, which is legitimate context. |
| S2: zeroStock sends variantId=null | major | yes | blocker | Compared against writeStock in the same diff, and traced the inventory upsert (WarehouseStockRepository.java:115-117) to show it re-points another offer's row. The reviewer found a worse impact than the key listed. |

Detection 2/2. No false positives: every other finding in the seeded review also appears in the real review or is a real issue at 4ca6991b, for example zeroing on validation failures and the PLATFORM default for an offer with a null sellerId.
The tests were not the oracle this time: no test asserted syncPrices or the zero write's variantId. One oracle remains: S1 contradicted the design text. S2 had no text oracle and was found by code comparison and reasoning across services.
Side benefit: the seeded reviewer found a real test gap, that nothing asserts the zero write's fields or the order of zeroing before discard. That gap is why S2 would pass the test suite. It is fed back into the real fix round.
