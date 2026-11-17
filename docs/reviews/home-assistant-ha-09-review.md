# HA-09 independent review

Verdict: **APPROVE**

- Candidate: `ha-ha-09-v1`.
- Manifest: `docs/reviews/home-assistant-ha-09-v1-candidate.json`.
- Manifest SHA-256: `293509f545137d676861c499208d8745e5f936f1c5e890bf58c4e2244fadc8f8`.
- Implementer: `/root/ha_events_implementer`.
- Independent reviewer: `/root/ha_events_reviewer`.
- Reviewed on 2026-11-17. No implementation or ledger changes by the reviewer.

## Scope and findings

No blocking findings. I verified the manifest digest, all 13 bound file hashes
(12 authored paths and the frozen ledger snapshot), the compressed tracked-diff
digest and the dependency manifest. HA-04 v2 is independently approved and
accepted at `a8a46645535397cf3b01b2298b7e96af1b441f78377d97acb07a899647928c96`.
Disjoint server/SDK work is outside this candidate's scope.

The typed `whenState` condition requires a selected alias, bounded nonblank text
and mutual exclusion with numeric `above` and `unit`. The existing nine- and
ten-argument route constructors remain available. Numeric configuration still
hashes its original raw configuration for binding identity. The new predicate
reuses the existing route state, held-event DTO/codec, journal and runtime owner;
it introduces no second scheduler or durable execution mechanism.

I inspected the policy and its callers for startup/resync suppression, fresh
edges, cooldown, held evidence, gap/availability/epoch cancellation and restart
behavior. The new tests exercise these through the actual integration runtime
with fake adapters. They also verify retained transitions at queue capacity,
runtime-owned acknowledged causal suppression and UNKNOWN-action non-replay.
The unchanged held-event name and `high` field remain documented compatibility
names for matching-condition state.

The narrow named-control guard refuses `conversation.process`,
`ai_task.generate_data` and `ai_task.generate_image` before replacing policy or
advancing its generation. The affected HA-04 approval covers those source paths.
I also checked the installed offline HA constants and service registrations;
their hashes match the implementation report. A fixed entity target cannot
establish the selected inference provider, so the current refusal has no unsafe
override. Read descriptions and explicitly configured manual aliases retain
their separate contracts. Arbitrary scripts and indirect service chains are not
claimed to have universal recursion detection.

The design alignment resolves the preflight documentation discrepancy: current
named controls refuse those services while read discovery can describe a
configured profile. The shared design and observation/action guides now agree.
Captured-input ORCHESTRATION routing retains its existing fresh-remote-wait
restriction.

## Supported feedback boundary

Approval covers preservation of the existing runtime-owned causal feedback
contract. Named SDK controls retain context receipts in their authoritative SDK
journal, but no automatic bridge inserts those contexts into the legacy runtime
feedback index. The documentation explicitly requires independent workflow
sources or runtime-owned actions when relying on that suppression policy.

This limitation is material: a post-acknowledgement callback alone cannot safely
order an earlier native event or reserve shared cause-index capacity. The
candidate does not conceal it with another journal or claim universal cross-host
loop prevention. Unknown effects receive no invented correlation or replay.

## Verification inspected

I independently parsed the saved JUnit XML under
`/private/tmp/ha09-verification-evidence`: **125 invocations in 15 classes**, with
zero failures, errors or skips. The selection comprises 82 runtime cases in
seven classes and 43 HA cases in eight classes. It includes 17 new invocations,
the existing held/feedback/ancestry/retention/recovery cases, all five action
fixtures, ten manual adapter cases and two fake pipeline invocations.
`IntegrationRuntimeTest` and `JournalTest` were not selected in this run and are
not claimed as current evidence.

- Focused checks: BUILD SUCCESSFUL in 49s; log SHA-256
  `5efc87aabe673be22459b4358fd91848d92cdb4ad65bdc122a37cf96c8f7580b`.
- Global format: BUILD SUCCESSFUL in 4s; log SHA-256
  `50c67403ce9ad4e9e43f93d34ad3dda3fa0144888fd47dc32fc37514ce21c78f`.
- Separate formatCheck: BUILD SUCCESSFUL in 4s; log SHA-256
  `ed44261c28861cf1c8e7a306e0b9ed263acf3be4e0b32cb7dc450bb1801498b0`.
- Current `git diff --check`: passed.

I inspected source, logs, hashes and saved results; I ran no Gradle or database
tests and made no live HA, model or device calls. This exact-candidate approval
does not establish repository-wide verification, packaged or deployed behavior,
physical device effects, prompt-dependent support or live acceptance.
