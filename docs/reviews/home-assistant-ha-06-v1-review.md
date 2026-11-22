# HA-06 independent review — v1

Verdict: **APPROVE**. No open blocking findings remain for this frozen
Conversation/configuration deliverable. This does not approve the separately
owned HA-07 setup/journal implementation or enable installation packaging.

Reviewer: `/root/ha_conversation_sdk_reviewer`.
Implementer: `/root/ha_conversation_sdk_implementer`.
Date: 22 November 2026 (Australia/Melbourne).
Candidate: `docs/reviews/home-assistant-ha-06-v1-candidate.json`.
Candidate SHA-256:
`cf7eac366e2391654667b1b204b11eafee784cfcae92f72ef43882c2897e5434`.
Base: `6de685f63e060f3003cb93233d2567e004269755`.

## Independence and exact evidence

The reviewer authored no implementation, fixtures, configuration, ledger or
dependency code and ran no tests or builds. Review used the actual installed
HA 2026.9.4 source, adapter implementation, fixtures and coordinator-run evidence.
Only this new review report was written by the reviewer.

Independently verified all ten candidate file hashes, its frozen ledger and
scoped diff. The dependency is independently accepted HA-05 v2
`f165cb89ae87625c1dcaacad1eced1ed1e37224efb85dcd420167c1d12197f92`;
its current bound source/report files and approval-report digest match.
Implementer and reviewer identities are distinct. The frozen graph is acyclic
with 30 nodes and 56 edges, accepted dependency closure and independent roles.
The retired prompt gate has no consumers, and cache measurement does not block
release. All 4,042 protected core/protocol/SDK/shared-runtime file hashes match
the recorded baseline; no new untracked files exist under those protected roots.

The successful full shared check is `/private/tmp/ha-06-07-full-v3.log`, SHA-256
`f8fc9f4609779aca8afcd51a9bd2c3172f569c7678f6f8be26ec3fe322e0cceb`.
It reports Ruff lint and format success and **113 tests passing in 1.298 seconds**.
The 26-file shared source snapshot has SHA-256
`ca33183416f8f0d967cc93951e703fe16da2fea170cceccbe526996334940656`;
all current source hashes match, and the coordinator checked that they remained
unchanged throughout the run. Shared-suite success is supporting evidence, not
automatic approval of its sibling deliverable.

## Reviewed behavior

Configuration is converted to immutable typed profiles before SDK work. Enabled
Conversation routes explicitly to `read_only_agent` or the configured control
agent; enabled AI Task requires its own selection. Disabled platforms cannot
inherit another route. Malformed/present fields and unsupported HA LLM tool APIs
are rejected. The frontend CONTROL declaration does not grant or remove server
permissions. All enabled selections use one read-only agent-catalogue snapshot.

The entity prepares the actual HA ChatLog once, captures rendered SystemContent
unchanged and passes typed language/source context to the accepted SDK gateway.
Effective extra prompts remain present exactly once on same-owner follow-ups.
No native system role, cache optimization or special compaction retention is
claimed. Completed results, pending status speech and failures remain distinct.

HA caches logs by chat ID rather than caller. The adapter therefore checks its
bounded per-HA-process ownership table before opening a supplied log. Unknown or
foreign IDs fail before SDK submission or shared-log mutation. New chats start
without an ID; known-owner follow-ups preserve returned IDs and source partition.
Active ownership prevents same-ID concurrent mutation and survives entity reload.
Idle metadata can expire without deleting durable SDK receipts. A known HA ID
expiry returns an explicit error and fenced replacement ID without submitting
replacement work. Arbitrary first-call custom IDs and post-restart unknown-ID
continuation are intentionally unsupported and documented.

Configuration probes retain bounded physical owners beyond a flow object's
lifetime. Failed, canceled, false or stalled cleanup cannot acknowledge successful
configuration or release occupancy without closure proof. Explicit later attempts
can check retained cleanup once; active validators are not reaped. Outcome
consumption and identity-guarded deletion prevent stale callbacks from producing
unobserved failures or releasing a newer owner.

## Findings resolved before freeze

- SDK `Refusal` was incorrectly mapped universally to bad credentials. Both
  flow steps now map capacity to `validation_busy` and other declared negatives
  to `cannot_connect`, without inventing unsupported authentication codes.
- The initial continuation logic dropped Greek question-mark support. Completed
  replies now use HA's actual ChatLog continuation semantics; pending diagnostics
  never request continuation merely because they end in a question mark.
- The initial public-API fixture omitted HA's real device-registry setup/load
  ordering. It now exercises registered `conversation.process` and public
  `async_converse`, including actual internal entity state writes.
- Coordinator-observed stale/late cleanup-future warnings are addressed by
  observing settlement before identity checks and explicit retained false
  outcomes. Regression tests cover stale callbacks and post-wait failures.

## Limits

The actual HA prompt/session/service APIs are exercised locally; only unrelated
auth lookup and the selected gateway peer are faked in entity tests. The shared
suite separately exercises the public SDK over controlled local WebSockets.
These checks do not prove real server grants, live device effects, deployed voice
providers or target HA compatibility beyond the pinned baseline. Setup/runtime
composition and durable storage have their own independent HA-07 review; final
readiness and packaging remain HA-11 work. No PostgreSQL, live HA endpoint,
model invocation, device action, publication, deployment or core/SDK change was
performed by this review.
