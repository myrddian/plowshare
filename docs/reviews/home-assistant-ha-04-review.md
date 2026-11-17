# HA-04 independent review

Verdict: **APPROVE**

- Candidate: `ha-ha-04-v1`.
- Manifest: `docs/reviews/home-assistant-ha-04-v1-candidate.json`.
- Manifest SHA-256: `6e5eda89cd4b86fe566662981fc4328a9828e0d95cd2c97c2fc76addfc074f0a`.
- Implementer: `/root/ha_action_implementer`.
- Independent reviewer: `/root/ha_action_reviewer`.
- Reviewed on 2026-11-17. No implementation or ledger edits by the reviewer.

## Scope and findings

No blocking findings. I inspected the seven new production capabilities/classes,
five new test classes, action-provider contract and implementation report, plus the
additive adapter, native codec and catalogue seams. I traced their composition
through the already reviewed public SDK provider, exclusive file journal and
native command owner. All manifest-bound files, the frozen tracked-diff digest
and the HA-03 dependency manifest digest match the candidate.

The implementation stays within the public SDK integration boundary. Control has
a separate provider identity and nonoverlapping prefix; the read provider retains
its four declarations. Server-owned tool admission remains distinct from the
explicit revocable host ceiling. The documentation accurately identifies the
current SDK's inability to perform another server permission RPC immediately
before native transmission, rather than claiming a new authority guarantee.

Execution requires the offered service, ordered complete target bundle, primitive
parameters and profile fingerprint. The original SDK call retains that complete
intent before acknowledgement and handler work. The native executor independently
matches the configured profile and credential binding. The bounded fixed-target
subset refuses targetless/global, nested and unsupported response profiles rather
than weakening them.

The local policy generation and cached catalogue offer are checked after command
slot waiting. The catalogue fence performs no implicit refresh or transport call,
samples native connection state outside its lock and verifies the cached policy
and snapshot remain current. The final local policy recheck closes the revocation
window introduced by source sampling. The native command additionally checks the
captured connection epoch, remaining budget and interruption before possible
transmission. These are admission checks; the contract correctly allows an
already admitted effect to race subsequent changes.

Possible transmission is recorded before the native send. A timeout, interrupt,
disconnect, malformed acknowledgement or service refusal cannot turn into a
known unperformed action or automatic resend. A service refusal conservatively
retains unknown effect evidence because HA may have partially acted. Restart and
lost-publication handling reuse the SDK's original invocation and receipt owners;
UNKNOWN results are retained. No second durable runtime or journal was added.
The native request ID is paired with its epoch, bounded HA context is retained
without the HA user identity, and acknowledgement remains distinct from an
observed physical result.

Verification is a separate explicit API: selected references must belong to the
receipt's target bundle, one fresh batched read must return the exact selected
revision and reference set, and stale, unavailable or pre-receipt observations
cannot match. A matching predicate is documented as correlation evidence, not
proof that the original action caused the state. It never re-actuates.

## Verification inspected

I inspected the coordinator's logs and saved JUnit XML, rather than inferring a
passing result from compilation or test names:

- Focused action and HA-01/02/03 regressions: **90 tests in 13 classes**, including
  the **24 new action tests**, zero failures, errors or skips; BUILD SUCCESSFUL in
  14s. Log `/private/tmp/ha04-focused-check.log`, SHA-256
  `9b3b741d2d686bd65b25c4f6f6a6024db12b3b053706f482df70b60d59a716e4`.
- Separate global `formatCheck`: BUILD SUCCESSFUL in 4s. Log
  `/private/tmp/core03a-v2-format-check.log`, SHA-256
  `5328630a9154614956f2a4b3224ba0adea1fc89c1ec16b9dece4e45c87519d8a`.
- Supplemental current legacy coverage: **10 HomeAssistantAdapterTest cases and
  two PipelineTest parameterized invocations**, all passed; BUILD SUCCESSFUL in
  15s. Log `/private/tmp/ha04-legacy-regression-check.log`, SHA-256
  `6f2952429c7b842145a883a0b128dbe368a9ab8047f917bfa116a78f3dd43d4d`.
  The supplemental run does not alter the frozen candidate or its historical
  implementation report.

The tests exercise exact offers and malformed inputs, current-policy refusal,
unknown native delivery, cancellation around transmission, durable intent before
one fake effect, duplicate/restart suppression, lost broker/result acknowledgements
and separate observation. I made no Gradle, database, model or live-device calls.

## Acceptance limits

This approves the exact HA-04 source candidate. I also inspected the separate
HA-02 v3 affected-seam APPROVE report and its accepted ledger entry, manifest
`9612efe6a189eb2f0b61d3e353d5feb5714cfb4f35fe4459a73fd5c0b4061997`.
Repository-wide verification and later integrated/package acceptance remain
separate deliverables.
The current slice is a Java composition API; it does not install a live control
provider, establish live server grants or physical device success, satisfy the
prompt-fidelity gate, or authorize deployment.
