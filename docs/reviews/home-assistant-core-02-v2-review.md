# CORE-02 independent storage re-review

Reviewer: `/root/ha_core_storage_reviewer`.
Implementer: `/root/ha_core_storage_implementer`.
Candidate: `ha-core-02-v2`.
Manifest: `docs/reviews/home-assistant-core-02-v2-candidate.json`.
Manifest SHA-256: `f3985870a2759ee3e6a111fc06d05c7e84a0d043540b62983a6daf59c4996739`.
Verdict: **APPROVE**.

## Verification and correction

Independently verified the manifest, all 13 bound file/snapshot hashes and frozen
tracked-diff hash. Compared the v1 and v2 manifests: the only implementation changes
are the JDBC repository's explicit isolation setting and its tagged PostgreSQL
regression; the implementation report and ledger snapshot also changed. Migration,
creation contract, deadline owner, ordinary store seam and existing mock/guard
tests retain their reviewed v1 hashes.

**CORE02-R1 is resolved.** The repository now sets READ COMMITTED on its private
TransactionTemplate and explains why lookup after the advisory-lock wait requires
a fresh statement snapshot. It still refuses an enclosing transaction, so it does
not lower a caller-owned transaction's isolation. The ordinary creation and
current-authority owners and existing identity row-lock fences are unchanged.

The new PostgreSQL regression verifies physical pool defaults of both REPEATABLE
READ and SERIALIZABLE. A controlled creator barrier prevents the first commit
until PostgreSQL reports the other three openers waiting on its advisory lock,
after their scope reads. All four must then return one root, with one creator.
The test also verifies restoration of the pool's original isolation after Spring
transaction cleanup. This directly exercises the reported stale-snapshot condition
rather than relying only on an unsynchronized concurrent race.

Inspected the current actual PostgreSQL JUnit result: 13 invocations across eight
scenarios, including both isolation variants and five bounded lock/rollback race
repetitions; zero failures, errors or skips. The current focused mock log contains
26 passing cases. The v2 mock and selected PostgreSQL commands passed in 9s and
7s respectively; current server formatter/spotless checks passed. No additional
Gradle/database/model/live run was started by this reviewer.

The earlier repository-wide `check`, global formatCheck and 37 HA fixture passes
belong to v1 and are correctly distinguished from v2 scoped proof. Broader current
integration/facade/SDK verification remains later delivery evidence. The earlier
review's PostgreSQL-specific cancellation assumption and wider admission/deadline
limits remain explicit; this approval does not establish a universal SQL deadline,
public Ex API, deployment or live validation.

No blocking findings remain. The v1 review and its finding are preserved.
