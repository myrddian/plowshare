# VERIFY-01 v1 independent review

Verdict: **APPROVE**.

Reviewer: `/root/ha_integrated_validation_reviewer`.
Implementer: `/root/ha_integrated_validation_implementer`.
Candidate: `docs/reviews/home-assistant-verify-01-v1-candidate.json`.
Candidate SHA-256:
`b9f3e57fa47090bd8ad2ac876fc6a2211f173ad68ef43bec9cb20138eea9ab0c`.

I independently read the two new fixtures, implementation report, final evidence,
owning production entry/entity paths and actual check logs. The candidate's four
file hashes, frozen ledger/diff, eight accepted dependency identities and review
hashes match. Implementer and reviewer are different agents. I authored no
implementation, changed no ledger or production source and ran no tests/builds.

## Review findings

No blocking findings remain. Preflight requested installed-module containment,
supported operation/authentication assertions, committed intent before wire work,
separate task routing and aliases, invalid-result preservation and lost-receipt
recovery without replay. The frozen fixtures implement those assertions.

The wrapper builds and extracts a fresh component outside the checkout and copies
the consumer there. Its bounded `-I` subprocess admits the extracted root
explicitly, verifies every loaded component/vendor module resolves beneath it,
and forbids importing the source staging helper. The consumer uses actual entry
composition, gateways, atomic journal, HA Conversation entity, AI Task component
and service, bundled public SDK and segmented WebSocket transport. Only unrelated
HA user lookup and platform forwarding/unload are isolated.

The three installed cases establish the following composition behavior:

- Assist preserves the HA-returned chat ID, exact utterance whitespace and HA's
  effective context, including extra context exactly once. The second utterance
  uses the same Ex alias. Foreign caller reuse fails before another wire frame.
- Text and structured AI Tasks use the explicit task agent and independent fresh
  aliases. Invalid completed structured output produces one HA error, no repair
  run and no successful assistant-log entry. Completed work remains retained.
- A peer-accepted run with a lost acknowledgement leaves an uncertain receipt.
  Real entry unload/reload preserves its bytes and identity. The next same-chat
  call makes no connection/open/run and does not replace the accepted work.

The scripted peer verifies bearer/subprotocol/envelopes and admits only Ex
open/run plus receipt reads. Before each Ex mutation it reads the real journal
and requires the exact alias's committed sending intent. Agent/project and
ordinary task/user context semantics are asserted. Peer, HA shutdown and isolated
subprocess cleanup have finite waits. These assertions exercise adapter
composition, not live server authorization or model/device behavior.

## Verification checked

I verified the evidence JSON SHA-256
`dbf472faa3aa0a62a832698e097ef77d0790da169b909bf062324dbc601a5e67`
and independently checked its 116 current source hashes, the retained Assist ZIP
SHA and all 50 contents hashes, all 56 installed outbound payload hashes, ten
recorded dependency candidate/review pairs and four check-log hashes. The ZIP
has 51 entries including its manifest. All 4,042 protected core/protocol/SDK/shared
runtime hashes still match the starting snapshot; there are no new untracked
files in those protected roots.

- The final Assist log reports Ruff lint/format success and 135 outer tests
  passing in 3.265 seconds. Its outer installed-contract test requires all three
  subprocess cases to pass; these are not counted as additional outer tests.
  All 31 captured source/test files still match the final checked snapshot.
- Repository `check` and HA `installDist` succeeded in 1 minute 14 seconds across
  144 tasks. Evidence includes 184 HA Java and 8,627 default server cases with
  zero failures. This is the default non-database check, not a full-db run.
- Fresh installed outbound validation passed eight offline checks, including
  journal preservation, artifact namespace/docs and trap-listener checks.
- Explicit SDK package verification succeeded in 4 minutes 44 seconds, covering
  the native/shared SDK builds and fresh installed consumers. Reusing that check
  is justified by unchanged protected SDK/source hashes; this node adds only
  adapter fixtures and evidence.

## Acceptance limits

Approval establishes independently reviewed offline integrated validation for
the exact source/artifacts identified above. HA 2026.9.4/Python 3.14 is the tested
baseline; the target HA version is unverified. Prepared HA text remains ordinary
task/user input with no native system-role, special compaction or cache rewrite
guarantee. Optional backend cache measurement is not a release prerequisite.

No new production adapter/core/SDK/shared-contract/schema change is part of this
node. No PostgreSQL, live HA/server/provider/model/device action, speech-engine
acceptance, publication, merge or deployment was performed. Process-restart chat
ownership, live grants, physical state and deployed health are not established
by the in-process reload fixture. RELEASE-01 and separately authorized operational
nodes retain their own acceptance requirements.
