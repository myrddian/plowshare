# Slice 13: Existing API, scope-selection and client contract cutover

Status: proposed scope, 17 October 2026. Planning only; no implementation,
new public capability or deployment is authorized by this document.

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

These are future checks, not tests run for this planning document. Add mocked
boundary/authorization tests for new behavior; existing suite links do not imply
every test is non-database. Follow the shared roadmap's focused-test, formatting
and check requirements. Completion requires the outcome and negative/recovery
cases above, with unsupported paths still explicit and no uncertain-effect replay.

## Specification required before implementation

Make an operation-by-operation coverage and compatibility inventory. If an existing operation cannot express the required target safely, record the exact gap and stop that route's exposure instead of adding an inferred API.

## Rollout and rollback

Define versioned refusal for clients/servers unable to represent the new scope. Do not silently downgrade a mutation, retry uncertain delivery, remap Global to null or impersonate its account.
