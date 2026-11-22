# HA-10 v1 independent review

Verdict: **APPROVE**.

Reviewer: `/root/ha_application_profiles_reviewer`.
Implementer: `/root/ha_application_profiles_implementer`.
Candidate: `docs/reviews/home-assistant-ha-10-v1-candidate.json`.
Candidate SHA-256:
`b04aee138fa8d2cd36f88f6fee498ce723052006307b3a31b09b8667e871ea9f`.

This approval binds only the candidate's exact 14 authored files: 11 standalone
profile files, the Application README, the formatted loader test and the
implementation report. I verified their current SHA-256 values, the frozen
ledger snapshot and diff digests, and the four dependency manifest digests.
HA-04, HA-06, HA-07 and HA-09 are accepted in that snapshot. The implementer and
reviewer are independent. I made no implementation or ledger changes and ran no
builds or tests during review.

## Source and authority review

I read the full scoped source and checked its contract against the current
`RuntimeApplicationPackageValidator`, `WorkspaceApplicationPolicy`,
`ApplicationToolScopes`, `ApplicationServerSettings`, `AgentRegistry` and HA
configuration routing code.

- Each source root loads as its own explicitly named Application. The readonly
  root exports reader and task agents; control exports separate reader,
  controller and task agents; task exports only the task agent. There is no
  implicit default route. HA route fields and defaults in the README match the
  actual adapter configuration contract and do not imply fallback or flag-based
  permission changes.
- The reader receives exactly four read tools. Only the controller receives the
  two control tools, in addition to those reads. Assignments bind exact
  execution principal, provider and agent identities. There are no wildcard or
  future-catalogue grants. Static publication declarations match the accepted
  outbound host templates, including project, principal, prefix and lease.
- Generation agents explicitly disable dynamic tools and have no built-in tools,
  workspace scopes, call edges, skills or orchestrations. The task-only root has
  no server/provider directory or host dependency. Empty access ceilings remain
  inert until an operator configures actual membership and Application access.
- Exported agents are nondelegable; commands are off. Agent and manifest ceilings
  are finite at 20 steps and 40 model calls, without automatic increases. Prose
  uses approximate spoken-word targets with tolerance and does not authorize
  retries of uncertain actions.
- Documentation distinguishes the unchanged legacy observation/Relay package
  from standalone Assist/task roots. It states one HA entry uses one project and
  credential, requires exact provider/caller identity agreement, and explains
  that a disabled HA control flag cannot strip a controller's server grants.
  Supplied HA text keeps ordinary SDK task/user semantics; no native system-role
  fidelity or prompt/cache rewriting is claimed.
- README relative links resolve in their intended source/Java artifact context;
  the referenced canonical authority and composition-limit source targets exist.
  The HA-11 packaging owner has been told to preserve that context for copied
  documentation rather than modifying these accepted profile files.

No blocking findings remain.

## Verification inspected

I read the coordinator's actual profile-check log and the three resulting JUnit
XML suites. Google Java formatting passed. The selected loader run,
`formatCheck` and `integrationBoundaryCheck` passed in 7 seconds across 68 tasks.
Actual selected results are 18 `ApplicationProfilesTest` cases, one legacy
`ProjectExamplesTest` case and 12 `OutboundProfilesTest` cases: 31 total, zero
failures, errors or skips. The profile cases exercise actual owner parsers with
mocked nonessential dependencies, including missing/non-dynamic assignments,
wrong project/principal/provider, unassigned agents and future tool denial.
They do not require PostgreSQL.

The inspected log is `/private/tmp/ha-10-profile-check-v1.log`, SHA-256
`c8231daff10108d8b78123d6d1153de951196317c5e0a3402c81035ad04eda00`.
The frozen implementation report records the validation and post-format source
inventory. I independently compared all 4,042 protected source hashes with the
start-of-work snapshot: core, protocol, SDK and shared integration runtime bytes
are unchanged. All nine files in the previously accepted outbound examples
candidate remain unchanged, as do the tracked legacy Application and Java
configuration. HA-10 introduces no production server imports, build-dependency
changes, SQL or schema changes; real server loaders are used only in tests via
existing test dependencies.

## Acceptance limits

This is verified and reviewed source/template work. It does not establish live
membership/grants, model availability, provider publication, deployed HA version,
device behavior or deployment acceptance. Publication, deployment, restarts,
live model/device checks and optional backend cache measurements were not
performed. Native system-role parity and cache optimization are not release
prerequisites or claims. The coordinator may mark HA-10 done for this exact
candidate; packaging and integrated validation remain separately reviewed nodes.
