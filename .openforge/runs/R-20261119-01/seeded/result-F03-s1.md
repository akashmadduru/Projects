# Seeded-defect test 1: F-20261119-03 slice 1 (review-input-b.diff)
Reviewer: forge-security (fresh instance, not told about seeding). 48,599 tokens, 75 s.
| Seed | Injected | Caught | Severity given |
|---|---|---|---|
| S1 | right-hand N-hop rule → left-hand `parts[N-1]` | yes | blocker |
| S2 | IPv4:port keeps the port | yes | major |
| S3 | dotted-quad guard `!= 3` → `> 3` | yes | major |
Detection 3/3 (100%). Bonus real findings: multi-line XFF (also found by the real security review) and
trailing-comma split (`split(",")` drops empty trailing entries) — the real reviewers missed the latter.
**Caveat (lowers confidence):** all three seeds contradicted tests in the same diff (the reviewer cited the
failing tests), so the tests acted as an oracle. Next seeded test must inject defects the tests do not cover
(e.g. remove a test together with the code it guards, or seed in an untested branch).
