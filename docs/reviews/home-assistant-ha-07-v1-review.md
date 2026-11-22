# HA-07 independent review — v1

Verdict: **APPROVE**. No open blocking findings against this frozen text-task,
setup and correlation-persistence candidate.

Reviewer: `/root/ha_ai_task_sdk_reviewer`.
Implementer: `/root/ha_ai_task_sdk_implementer`.
Date: 22 November 2026 (Australia/Melbourne).
Candidate: `docs/reviews/home-assistant-ha-07-v1-candidate.json`.
Candidate SHA-256:
`68311df2e9c62d0a0ff547f39fb3fd35ef74e8f5d94ac6ed619949366eb30aef`.
Base: `6de685f63e060f3003cb93233d2567e004269755`.

## Independence and exact bytes

The reviewer authored no implementation, test, setup, SDK, core, plan or ledger
source. Review used actual owning HA 2026.9.4 source, the current adapter and
recorded coordinator evidence. No build, test, dependency installation, database,
model, device, endpoint or deployment action was run by this reviewer. Only this
new review report was written.

Independently verified the candidate digest, all ten bound file hashes, frozen
ledger snapshot and scoped diff. HA-07 implementer and reviewer are distinct,
its gateway dependency is accepted, and the node is not yet marked reviewed.
The accepted HA-05 v2 manifest, its current files and independent approval match.
The current HA-06 configuration/Conversation source and its separate approval
were also verified; those source bytes are the ones used by the combined check.

The frozen graph has 30 nodes and 56 edges, is acyclic and preserves independent
roles and accepted dependency closure. No node consumes the retired prompt/cache
gate; actual measurement, deployment and live authorizations remain unsatisfied.
The source snapshot contains 26 files, all matching current bytes. All 4,042
protected core/protocol/SDK/shared-runtime source hashes remain unchanged.

## Supported behavior

The AI Task entity advertises only `GENERATE_DATA`, consumes the actual prepared
HA system text and current task instructions through the accepted typed gateway,
and returns generated data only for completed text. The answer is mirrored into
HA's ChatLog. Pending, uncertain, refused, busy, unavailable and failed outcomes
are explicit HA errors. Structured output, attachments and HA LLM tool APIs are
refused before SDK submission in this slice. HA itself can prepare an explicitly
supplied LLM API before the subclass hook; the report accurately states that limit.

Fresh invocation identities isolate concurrent identical calls. No identity is
inferred from the entity's mutable `_context`; unavailable per-call provenance
stays absent. Explicit task configuration does not inherit a control agent.
The two optional profile gateways share one typed correlation set and writer,
with stable independent profile namespaces and current server-owned authority.

The specialist journal validates bounded UTF-8 JSON, duplicate/unknown fields,
version/key, malformed/deep input and typed lifecycle records. Nonblocking,
no-follow descriptor admission rejects FIFO/symlink input before reading it.
Corrupt and legacy bytes are preserved on load. Immutable snapshots enter one
bounded FIFO; file and directory fsync precede acknowledgment. `JournalUnavailable`
is an `OSError` under the accepted gateway failure contract, so a failed commit
cannot become an SDK mutation or a successful save. Caller expiry/cancellation
retains physical write ownership; later snapshots cannot overtake an older write.
Normal HA Store's best-effort behavior is not used as durability proof.

## Resolved pre-freeze findings

- The initial reader checked file type after a blocking open. It now admits a
  nonblocking/no-follow descriptor and checks a regular file before reading;
  an actual FIFO case verifies prompt rejection.
- Stop fencing now precedes filesystem and forwarding waits. New profile lookup
  closes synchronously, and failed setup cannot silently expose a replacement.
  Registry release checks owner identity, protecting a later owner from stale
  cleanup. Deep JSON failures become typed journal errors.
- Each platform forward has a separately retained task, observed without caller
  cancellation propagation. One failed forward cannot hide a still-live sibling.
  Close waits for its completion proof before cleanup. Unexpected cancellation
  remains uncertain and cannot authorize replacement.
- Failed setup, stop and ordinary unload use the same supported per-platform
  cleanup owner. Successful cleanup progress is retained, so a partial failure
  cannot cause repeated unloading of an already-removed sibling. Pending/failed
  cleanup retains ownership and blocks replacement. Gateways settle before the
  journal stops its existing receipt writes; remote accepted jobs are not cancelled.
- Coordinator evidence exposed a fast completed-read/load-state race. The reader
  now establishes its loaded-state invariant synchronously before returning, with
  a completed-future regression. Fixture registry and config-update errors were
  corrected through supported HA APIs, not weakened assertions.

## Actual verification

The coordinator's complete v3 Assist check passed Ruff lint, Ruff format checking
and **113 tests in 1.298 seconds**, with no failures, errors or skips. Of those,
37 cases belong to this slice: ten actual AI Task action/helper/entity cases,
fourteen entry lifecycle cases and thirteen journal/HA Store cases. The remaining
cases exercise the shared gateway, actual segmented SDK transport, Conversation
and configuration. Actual HA services/helpers run against typed scripted gateways;
setup failure/cleanup tests inject controlled platform outcomes.

The reviewer independently read the complete result and matched:

| Artifact | SHA-256 |
| --- | --- |
| `/private/tmp/ha-06-07-full-v3.log` | `f8fc9f4609779aca8afcd51a9bd2c3172f569c7678f6f8be26ec3fe322e0cceb` |
| `/private/tmp/ha-06-07-full-v3-source.json` | `ca33183416f8f0d967cc93951e703fe16da2fea170cceccbe526996334940656` |

The implementation report retains initial lint/fixture/load-race failures rather
than representing them as passes. No additional test run was needed for this
review because the frozen source and actual successful evidence matched.

## Acceptance limits

This approval closes HA-07 only after the coordinator records it. It does not
enable runtime publication/setup/configuration/installation guards, which remain
closed for HA-11's verified handoff. Structured requests remain HA-08 work.
Installation, actual target HA compatibility, real credentials/grants, model/device
behavior, crash/power-loss durability and deployed voice operation are not proved
by these local fixtures. No native system-role equivalence, special compaction
retention, cache optimization, infinite idempotency or instant physical I/O abort
is claimed. A cleanup refusal without public settlement proof remains retained
for inspection; it is not manufactured into successful disposal.
