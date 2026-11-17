# HA-03 v2 independent re-review

Verdict: **APPROVE**. HA03-R1 is resolved; no findings remain for this candidate.

Node: `HA-03`. Implementer: `/root/ha_provider_implementer`.
Independent reviewer: `/root/ha_provider_reviewer` (no implementation authorship).
Candidate: `ha-ha-03-v2`, manifest
`docs/reviews/home-assistant-ha-03-v2-candidate.json`.
Manifest SHA-256:
`8d29b5b267b8fb298b1a24d9e66e2c262b2f822981c0a3eb1838d5837bf99424`.
Date: 17 November 2026.

Verified the manifest digest, all 15 bound file hashes, frozen compressed tracked
diff digest and HA-02 dependency manifest digest. Compared the bound hashes with
v1: only the provider test and implementation report changed among shared paths;
the six production classes, manual, shared fixture and HA-01 source/tests are
unchanged. The new ledger snapshot is separately bound. The v1 findings report
remains preserved at its original digest
`82b45f1067ca9682c7b246389dc8d414947b215b78f5f0021c92dee9e9cb5c9d`.

Inspected the corrected fixture and scenarios. `newSearchInvocation` supplies
both a fresh invocation UUID and a fresh logical call identity. The sustained
stream now tracks 80 distinct invocation IDs across two host lifetimes, 40 reads
per lifetime, exceeding the full-receipt limit of 32 in each. It requires empty
full receipts after every settled read and verifies 80 distinct result event IDs,
all COMPLETED. It retains the first exact call and replays that same request at
its original retired position before shutdown, immediately after restart and
after the second stream; each attempt refuses before another resource handler.
The generation-change scenario and successor after result reconciliation now use
distinct logical invocations at their new positions. Deliberate duplicate and
lost-ACK tests keep their original exact invocation identities. The correction
does not weaken uncertainty, replay, handler-count or generation assertions.

The implementation report corrects the prior unrealistic-traffic evidence. The
unchanged production implementation retains the public SDK-only boundary, typed
read selections and host-policy ceiling, separate provider declarations/grants,
shared configurable deadline, NON_BLOCKING SDK dispatch, physical pass and
cancellation ownership, durable exclusive journal, original-identity read-only
reconciliation, and ACK plus DONE plus generation retirement proof reviewed in
v1. UNKNOWN/incomplete evidence remains bounded and is not evicted. The corrected
normal-traffic test now supplies the missing acceptance evidence for sustained
receipt retirement across restart. No action execution, packaged operator entry
point or prompt-dependent capability is included in this approval.

Independently inspected the canonical R1 log
`/private/tmp/ha03-v2-focused-check.log`: BUILD SUCCESSFUL in ten seconds, 24 tasks
(3 executed, 21 up-to-date), with production/server compilation dependencies
present and no skip flags. Current JUnit XML confirms 40 cases with zero failures,
errors or skips: ReadTools 5, Provider 17, Journal 5, Transport 2 and
ResourceContracts 11. Provider XML timestamp is `2026-11-17T01:07:40`, transport is
`01:07:47` and the other three are `01:07:38`. Separately inspected
`/private/tmp/ha03-v2-format-check.log`: explicit `:formatCheck`, BUILD SUCCESSFUL
in four seconds, 43 tasks (3 executed, 40 up-to-date). Reviewer `git diff --check`
passed. Earlier failed combined invocations remain failures and are not used as
whole-build success evidence.

No Gradle, database, live server/HA/model/device request, implementation fix or
ledger edit was performed by this reviewer. This approval covers the exact HA-03
read-provider candidate and its focused evidence. It does not establish a passing
repository-wide integrated check, live grants, cross-host installation, physical
device behavior, merge or deployment, nor satisfy a prompt-fidelity gate. Only
this new report was authored; root owns node closure and delivery state.
