# Slice 13: Existing API, scope-selection and client contract cutover

Status: implemented for the verified existing consumers in this branch,
18 October 2026, under explicit authorization. No new API or deployment.

Part of the [14-slice roadmap](../authority-substrate-implementation-plan.md).
Policy comes from [ADR 0030](../decisions/0030-authority-substrate-and-explicit-scopes.md);
the [impact report](../authority-substrate-impact-report.md) separates verified
source evidence from target behavior. The shared roadmap records source baseline,
test policy and cross-slice activation rules.

## Outcome and dependencies

Expose the migrated semantics only through verified existing supported operations, coordinating affected contracts without requiring SDK/client parity or creating new APIs.

**Depends on:** [05 — Current root-document policy and GLOBAL_READ audience](05-root-policy-and-global-read.md), [08 — Sandboxed SYSTEM services and independent processing](08-sandboxed-system-services.md), [09 — Scheduled and cron operation authority](09-scheduled-operations.md), [10 — Relay and message-triggered operation authority](10-relay-and-message-operations.md), [11 — Orchestration and agent-child authority lineage](11-orchestration-and-agent-lineage.md), [12 — Account acting delegation and action ceilings](12-account-acting-delegation.md).

Dependency completion means its relevant interfaces, migrations and verification
are available; it does not grant authority to a caller or enable an unmigrated path.

## Changes

- Inventory current HTTP/WebSocket/tool selectors and update affected existing typed scope/role representations and error/refusal semantics. An account UUID cannot be smuggled into an old project-name field.
- Resolve omission from trusted context before authorization: direct humans can use Personal, an unambiguous admitted Application/project selects itself, invalid/ambiguous targets refuse. Generic multi-project convenience stays deferred.
- Coordinate supported information/manual/general-read consumers, SQL-selected audiences, retained read-source admission and existing definition resolvers. Ordinary deployment/bundle bots remain user-executed; invalid authoritative context cannot fall back to root/Global.
- Regenerate only affected existing contracts/catalogues through their owning scripts. Human Global ADMIN uses account/role authority regardless of client; record missing SDK methods/interfaces rather than implement parity.

## Reuse and likely source owners

These files identify current owning contracts, not a claim that they already
implement the proposed behavior. Keep services on typed interfaces and persistence
inside specialist repositories.

| Existing source | Reuse and boundary |
| --- | --- |
| [InformationFrames.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/InformationFrames.java) | Existing information operation boundary and typed command surface. |
| [DocumentFrames.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/DocumentFrames.java) | Existing retrieval operations and server-side policy composition. |
| [DocumentController.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/api/DocumentController.java) | Existing upload/HTTP boundary; authorization is not a client feature flag. |
| [DefinitionResolver.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/DefinitionResolver.java) | Existing definition tiers/default lookup require explicit trusted context. |
| [information.ts](../../sdk/typescript/src/operations/information.ts) | Existing TypeScript operation support is an inventory, not every SDK's coverage. |
| [generate-sdk-contracts.mjs](../../scripts/generate-sdk-contracts.mjs) | Regenerate applicable outputs rather than hand-edit derived contracts. |

## Exclusions and capability boundaries

No new API verbs/SDK methods, first-party client allowlist, general project acting expansion, Global schema registry, new catalogue UI or full search aggregation redesign.

Standard scoped RBAC helpers remain the normal management path. Finer ACL needs
must use the same substrate; this slice does not silently add an ACL editor,
deny engine, SDK feature parity or an unsupported operation. Record a missing
capability as a gap before expanding implementation scope.

## Verification and completion

- Existing supported route tests prove human ADMIN management, Application/service general reads and unchanged refusal of ordinary Global execution.
- Cross-version/default tests cover null/omitted versus invalid explicit targets, ordinary projects named global, and no first-token-project or root fallback.
- Run affected existing SDK/contract/client tests and generated-output checks only; record concrete unsupported surfaces without asserting parity.
- End-to-end request tests use public boundaries; private logs/inputs remain hidden through errors, counts, events and exports.

Existing regression owners to inspect and extend, selecting methods/tags that
match the eventual change:

- [AuthoredDocumentUploadTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/api/AuthoredDocumentUploadTest.java)
- [InformationFeatureConfigTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/information/InformationFeatureConfigTest.java)
- [ProjectMembershipTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/ProjectMembershipTest.java)

**PostgreSQL justification:** Normally mocked transport/contract tests suffice. Select PostgreSQL only if this slice itself changes SQL, persisted selectors or transactional authorization; prior persistence proof is not rerun by habit.

The implementation evidence below distinguishes completed checks from the original
planned matrix. Existing fixture links do not imply database execution. Unsupported
paths remain explicit and uncertain mutations are not replayed.

## Specification required before implementation

The [operation and compatibility inventory](13-contract-coverage-specification.md)
was recorded before enforcement. It identifies the supported selectors, current
client/SDK representations, explicit refusal behavior and exact missing targets.
An operation unable to express its destination remains refused.

## Rollout and rollback

Define versioned refusal for clients/servers unable to represent the new scope. Do not silently downgrade a mutation, retry uncertain delivery, remap Global to null or impersonate its account.


## Implementation and activation limits

`RequestProjects` is an internal trusted-default capability. Its production
implementation reads existing identity, credential and Application repositories;
it checks the exact active issued Application binding, sole project scope,
expiry/revocation, active owner and current project access. It never chooses the
first of multiple projects or grants a role. Generic service tokens and service
accounts need explicit targets. The existing Personal home resolver and information
admission reuse it; supported front-door execution checks resolve that same live
Application context before authorization. Owning services recheck the actual home
before effects. Human omission retains Personal. SYSTEM/built-in identities do not
receive an interactive default.

Explicit information selectors now validate original project names, scope fields
and selector conflicts before typed DTO normalization. HTTP multipart uses the
same selection validation; invalid selectors return the existing caller refusal
instead of a server error. Omission remains distinct from malformed explicit
context. Supporting-view commands retain the admitted default rather than resetting
to Personal. A retained run's null Home cannot be reinterpreted as Personal.

Existing `document.*` read handlers now admit authenticated service readers through
their scoped document services, including list counts. They use the same current
root/input SQL policy already proven in slice 05. The existing information
share/unshare boundary uses audience authority rather than a generic content-WRITE
selection check. Its owning repository still checks Global human ADMIN or resource
home MANAGER, mandatory dependencies and the original command receipt. Client labels
never supply authority. Shared creation, processing and generic content WRITE
remain refused.

Explicit definition project lookup is now strict across run, listing and authoring:
missing names, malformed values and outages cannot select another tier. Agent and
procedure resolvers refuse deleted projects and broken authoritative merged tiers;
operator diagnostics and source-change recovery remain. Valid projects still layer
deployment/bundle ordinary definitions under the requesting account. Declared SYSTEM
services retain their separate deployment-only resolver and operation sandbox.

No protocol field, role value, scope kind, operation, SDK method, SQL or schema
changed. Existing wire project roles remain MANAGER/CONTRIBUTOR/VIEWER compatibility
helpers. Standard RBAC remains the normal management surface; this slice adds no
ACL editor or client allowlist. The read-only contract generator checks unchanged
outputs rather than generating new capabilities.

Missing interfaces stay explicit: no ordinary Global conversation/run target, no
Global destination selector for uploads/new documents or Global type registry, no
separate public Global read-source plus Personal execution selector, and no assumed
coverage in scope-unaware legacy document SDK methods. Legacy null memory/storage
homes, historical classification and integrated migration/restart proof remain
slice 14. Existing embedding/search and nested-orchestration allowance gaps remain
unchanged. This is not a complete deployment cutover.

## Verification evidence

Verification uses mocked owning repositories, real admission/catalogue services,
HTTP multipart and public frame boundaries. It covers trusted Application defaults,
invalid/ambiguous context, exact project named `global`, account UUID text in project
fields, no membership mutation, live role loss, service general reads and scoped
counts, Global audience management independent of client label, private/unsupported
work refusal, and deleted/broken definition tiers without fallback.

No PostgreSQL tests are required or run: this slice changes no SQL, persisted
selectors, schema, transactions or receipt storage. Existing policy persistence
proof is reused without claiming a new live integration.

- `./gradlew format formatCheck check sdkContractCheck` passed: 5,836 default
  server tests, zero failures and zero skips, plus the client/integration,
  formatting, Javadoc and contract checks in those tasks.
- The owning read-only generator verified 276 operations and 17 outcome codes,
  unchanged. No shared contract graph or generated outputs needed regeneration.
- A final selector-comment correction was followed by
  `./gradlew format formatCheck :plowshare-server:javadoc`, which passed.
- `git diff --check` and the slice documents' local-link checks passed.
- No PostgreSQL suite, native SDK parity acceptance, live deployment, push or
  migration rehearsal was run. Slice 14 remains unimplemented.
