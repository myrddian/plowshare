# OUT-PACK-01 independent review — v1

Verdict: **APPROVE**.

Reviewer: `/root/ha_outbound_pack_reviewer`.
Implementer: `/root/ha_outbound_pack_implementer`.
Candidate: `docs/reviews/home-assistant-out-pack-01-v1-candidate.json`.
Candidate SHA-256:
`ef835127bb7caaf71b574136adad0e0528a70eee967cd25485f26a44a4b33f7f`.
Base: `6de685f63e060f3003cb93233d2567e004269755`.

This is approval of the exact outbound packaging/host deliverable. It does not
approve original VERIFY-01, prompt fidelity, an HA component, release/rollout or
live model/device behavior. The reviewer authored no feature source, profiles,
plan, ledger or verification fixtures and ran no Gradle/database/live operation.
The review used owning source, frozen evidence and coordinator-run results.

## Integrity and ownership

Independently recomputed the candidate digest and all thirteen bound source,
documentation, implementation-report and package-evidence hashes. Verified the
frozen ledger snapshot, scoped diff and all five dependency manifest hashes.
Snapshot implementation/reviewer identities are distinct and match this report.
All four gate records and all eleven original awaiting-gate node records remain
byte-equivalent as JSON values to the baseline ledger. No Assist/AI Task source,
core, shared SDK, shipped migration or existing adapter/provider lifecycle was
changed by this deliverable.

The source follows the approved v2 common contract. Separate logical read/control
providers retain distinct prefixes and private journals while sharing the explicit
Application execution principal, credential reference and one caller-owned SDK
connection. No client-side grant issuance, tool.scope.connect substitution or
server-internal access is introduced. Provider/manifest declarations remain
separate from authenticated live authority.

## Source review

- Configuration is bounded before decoding and converted from strict UTF-8/JSON
  at the boundary into immutable typed records. New required fields, unknown/null
  inputs, duplicate keys, trailing tokens, origin/identity/timing bounds, shared
  login consistency, journal overlap and selected complete fixed-target policies
  fail before resource ownership. Raw JSON remains at the configuration boundary.
- Offline provider check reaches neither credentials, launcher, journals nor
  network. Only explicit provider mode selects the new host; legacy arguments
  delegate to the existing main. Named mode admits no workflows/subscriptions or
  recursive model controls. Read-only composition constructs no action handler.
- Startup publishes the exact owner under a live startup fence before transport
  or journal effects. Run/cycle/close cannot race its mutable factory resources.
  Stop is checked between owner operations; immutable configured provider count
  supplies shutdown sizing. Failure releases the startup fence before its own
  reverse-order cleanup and retains the same typed owner if draining fails.
- CLI hook registration precedes resource factory entry. Partial startup remains
  reachable through that original hook; no second hook/owner or unconditional
  System.exit discards uncertain work. Safe bounded diagnostics hide dependency
  messages, credentials, payloads and private paths. Interrupt state is preserved.
- Startup reconciliation does not treat a zero result as proof of non-submission;
  existing journal intent remains authoritative before renewal. Scheduled lease
  renewals are new normal operations, with no generic failure retry, SDK reconnect,
  action replay or competing receipt store. Lease unavailability fails explicitly.
- Active passes retain existing resource budgets, not an aggregate two-second
  agent lifetime. Stop does not manufacture remote abort. Provider journals settle
  before shared SDK/HA cleanup. Shared cleanup has one retained physical task and
  finite caller joins; later close joins the same task. Successful completion is
  published through the closed latch before the future. Runtime withdrawal/cleanup
  failures preserve uncertainty, continue safe draining and do not permit a later
  replacement withdrawal.
- Existing symbolic-link components are conservatively refused before journal
  creation. Existing atomic-file/lock defenses remain intact. Production uses only
  public SDK/runtime capabilities; Java imports and interface/composition boundaries
  were reviewed separately from formatter output.

Preflight findings about hook-registration ownership, startup publication races,
mutable shutdown sizing, cleanup completion ordering and unexpected cleanup
exceptions are resolved in this frozen source and covered by focused regressions.
There are no remaining blocking findings in the reviewed scope.

## Verification and installed bytes

Inspected the coordinator's final formatter result and the exact final check log:
`formatCheck :plowshare-integration-home-assistant:test
:plowshare-integration-home-assistant:installDist integrationBoundaryCheck
sdkContractCheck` passed in 33 seconds / 72 tasks. Log SHA-256:
`bb2a764d751c9b55746b6b2d4f213f926ea487809d9cc8afb91364c5cc1af83e`.
Independent XML aggregation confirms **163 tests, zero failures, errors or skips**,
including 24 configuration, nine host, eight CLI and twelve separately authored
profile cases. The existing adapter/provider tests remain included. Shared SDK
checks report 296 operations, twenty outcome codes and 1,012 canonical DTO cases.
The earlier macOS temporary-path fixture failure is retained in implementation
evidence; the correction canonicalizes the trusted fixture without relaxing the
production symbolic-link guard.

After the documentation-only Java 21 prerequisite clarification, installDist was
refreshed successfully. Independently verified its log digest against package
evidence and all **44 installed file hashes/sizes**, including 26 production jars.
The installed guide and examples equal source bytes, the generated launcher names
the new HA main, and the HA jar contains all four new production classes. No
plowshare-server jar is shipped. The guide clearly states external Java 21/runtime
and explicit endpoint requirements; no JRE or live installation is claimed.

## Remaining boundaries

OUT-VERIFY-01 remains responsible for frozen whole-source checks and fresh
installed/public-transport integration fixtures. This node's fakes and local
fixtures do not prove live grant provisioning, real HA service/version support,
physical effects or deployment readiness. Existing filesystem size/lock bounds
are preserved; a kernel filesystem stall is not proven to terminate. Incomplete
physical cleanup remains explicit rather than being called successful abort.
Named-control journals are not merged into legacy workflow feedback, and the
host rejects the configuration that would imply that unsupported composition.
All original prompt/context, cache backend, deployment and live-acceptance gates
remain unchanged.
