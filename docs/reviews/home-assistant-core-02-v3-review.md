# CORE-02 independent compatibility re-review

Reviewer: `/root/ha_core_storage_reviewer`.
Implementer: `/root/ha_core_storage_implementer`.
Candidate: `ha-core-02-v3`.
Manifest: `docs/reviews/home-assistant-core-02-v3-candidate.json`.
Manifest SHA-256: `f428a11b4894d58648680361440737b0fead49d567ae665198219c53e6e7e05b`.
Verdict: **APPROVE**.

Independently verified the manifest, all 13 bound file/snapshot hashes and frozen
diff hash. Compared with v2: only Opening's value/factory contract, its owning test,
the implementation report and ledger snapshot changed. Nine other bound source/test
files retain their approved v2 hashes, including the transaction/isolation owner,
deadline guard, ordinary creation seam/store, migration and PostgreSQL tests.

The compatibility correction follows the actual ordinary owners.
ConversationsProperties permits a configured default of zero; RequestedBudget
uses SpendingPolicy for an omitted request limit, while explicit zero still
reaches Budget.of and is refused. Opening now preserves that already-resolved
nonnegative policy and uses SpendingPolicy to create a fresh budget each time.
Zero therefore remains capped, exhausted and unable to spend, with distinct
allowance identities; it is not coerced to an unlimited or positive allowance.
Negative internal limits and inconsistent cap choices remain refused. Existing
conversation and canonical allowance constraints already support zero; no SQL or
DDL change is required. Public request validation is unchanged and remains a
CORE-03 boundary responsibility.

Inspected the current focused evidence: all 20 ConversationAliasRepositoryTest
cases pass, including the new configured-zero, fresh-identity, explicit-zero and
negative-limit assertions. Current server formatting/spotless checks pass; the
recorded scoped command completed in 8s. The unchanged seven deadline-guard cases
and 13 actual PostgreSQL invocations retain v2 evidence, accurately described as
unchanged-path proof rather than v3 reruns. No additional Gradle/database/live/model
operation was performed by this reviewer. Repository-wide checks and future
facade/SDK integration are not claimed for this amendment.

CORE02-R1's explicit READ COMMITTED correction remains unchanged. No blocking
findings or new contradictions remain. Earlier review reports are preserved.
