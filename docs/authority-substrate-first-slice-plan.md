# Authority substrate: first implementation slice

Status: slice 01 implemented in this branch, 17 October 2026, following explicit
authorization to implement this slice only. No new APIs or other slices are included.

This is **slice 01 of the [14-slice implementation roadmap](authority-substrate-implementation-plan.md)**.
Its existing scope remains unchanged; each follow-on slice has its own document
and dependency/verification boundary.

Read with [ADR 0030](decisions/0030-authority-substrate-and-explicit-scopes.md),
the [impact report](authority-substrate-impact-report.md) and
[role matrices](role-scope-permissions-proposal.md). The original source assessment
used main `269854a7c5bcc5f7080bc04de01ea8ccce46eaa7`. This branch now adds the narrow
implementation described below; the remaining authority model is still proposed.

## Recommended boundary and outcome

Start with a shared, typed project READ/WRITE permission contract, backed by
existing effective RBAC and used by the existing project-information gate.
The production consumer is `InformationAccess`; this is not an unused generic
permission engine or a parallel shadow-authority service.

The observable result is compatibility: existing project-information access
continues to allow/refuse the same callers at the same boundaries, now through
explicit action requests and one reusable permission contract. This establishes
a small foundation for later scope/action migrations. It does not complete the
Global split or change the current owner-only document-write behavior.

The later Global ADMIN upload, SYSTEM preparation and reader path described in
the impact report is an end-to-end cutover milestone. Combining its identity,
root-policy, audience, processing and accounting migrations would exceed this
first slice. Those changes remain explicit follow-on work.

## Current implementation and reusable seams

| Existing implementation | What we reuse and preserve |
| --- | --- |
| [InformationAccess](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationAccess.java) | Project selection now requests READ; project work additionally requests WRITE. Authentication, Personal mapping and service exclusion from Personal/Shared remain owned here. |
| [ProjectMembers](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ProjectMembers.java) and [ProjectRole](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ProjectRole.java) | Live effective roles and existing VIEWER/CONTRIBUTOR/MANAGER vocabulary. No wire-role rename, membership migration or enum insertion. |
| [ApplicationProjectMembers](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ApplicationProjectMembers.java) | The primary injected role source intersects stored roles with current Application-manifest limits and service-token ownership/ceilings. This wrapper must not be bypassed. |
| [JdbcProjectMembers](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcProjectMembers.java) | Existing persistence, account/token role resolution and current Personal/legacy server-admin compatibility. No new SQL or duplicate role lookup. |
| [DocumentsConfig](../plowshare-server/src/main/java/io/aeyer/plowshare/server/documents/DocumentsConfig.java) | Owns the InformationAccess bean today; composition can supply the new contract while services depend on its interface. |
| [InformationCatalogue](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationCatalogue.java), [InformationSql](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationSql.java) and [InformationWriteGates](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationWriteGates.java) | Existing call sites, per-resource readability/owner/input constraints, parameterized filtering and durable transition gates remain in place. Passing scoped WRITE is not a bypass of these checks. |

In production, do not substitute raw JdbcProjectMembers, isMember, a role supplied
in a request, or an AdminStore shortcut for the effective injected ProjectMembers source.
Application manifests may narrow existing authority; they do not create membership.

## Implemented scope

### 1. Define the narrow contract

[ScopedPermissions](../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/ScopedPermissions.java)
is the constructor-injected interface in the server access package. Its internal
`Principal` value requires a nonblank identity from authenticated server context;
the value itself does not authenticate. `ProjectTarget` uses the existing
`Home.of` locator validation and preserves the exact project name. `Action`
contains only READ and WRITE. These are internal contracts, not new public DTOs.

- An explicit project target is required. Null/blank/invalid targets cannot mean
  Global, Personal, unrestricted work or a fallback project.
- Use the existing project locator/role lookup as a compatibility reference.
  Do not synthesize scope UUIDs or present names as the final durable UUID model.
- Only implemented actions are admitted. There is no ordinal permission ladder,
  wildcard allow, reserved-identifier bypass or default authorization of a future
  unclassified action.
- No raw JSON, transport payload map, JDBC infrastructure, client label or
  caller-selected role enters this internal contract.

### 2. Implement the existing RBAC mapping

[EffectiveProjectPermissions](../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/EffectiveProjectPermissions.java)
uses the effective ProjectMembers interface and its live role resolution. Each
request resolves the role afresh with explicit action/role switches. No allow
decision is cached in InformationContext and no permission store is added.

| Current effective project role | READ | WRITE |
| --- | --- | --- |
| No role | Refused | Refused |
| VIEWER, the current read-role vocabulary | Allowed | Refused |
| CONTRIBUTOR | Allowed | Allowed |
| MANAGER | Allowed | Allowed |

This is the first implemented portion of the standard role helpers. It does not
add MANAGE_ACCESS, CONVERSATION_CREATE, RUN_MODEL, ADMIN, SYSTEM or GLOBAL_READ to
the project-role enum or claim those target policies have been implemented.
Current server-admin/Personal behavior continues only as supplied by the existing
effective role source; this adapter must not invent new administrative authority.

RBAC remains the normal setup path. No custom ACL configuration, new role-assignment
screen, automatic membership or per-resource grant backfill is needed for this
slice. Complex ACL evaluation remains outside it.

### 3. Wire the existing information gate

InformationAccess project branches request READ for requireSelection and WRITE
for requireWork, preserving the existing READ prerequisite and recheck points.
Leave Personal/Shared routing, includeShared selection, service-token restrictions
and SQL/resource policy composition intact. The existing permission-error contract
should remain stable where possible; unavailable authority must fail closed.

DocumentsConfig wires the adapter to the primary effective ProjectMembers bean
and injects ScopedPermissions into InformationAccess. Direct-construction test
fixtures compose the adapter explicitly; InformationAccess has no compatibility
constructor that selects an implementation or raw repository.

The central ProjectAuthorization operation classification remains unchanged.
In particular, do not reinterpret every CONTRIBUTOR operation as WRITE or every
MANAGER operation as MANAGE_ACCESS: model runs, configuration and access changes
need their own later semantic mapping. This slice is not complete authorization
for every operation that happens to call InformationAccess.

## Expected change surface

| Area | Planned change |
| --- | --- |
| access package | New narrow interface/value/action contract and an effective-project-role adapter; no repository or schema additions |
| InformationAccess | Replace direct project role checks with action requests; retain the other admission rules |
| DocumentsConfig/composition | Wire the adapter to the primary effective ProjectMembers bean |
| Constructor/test fixtures | Update direct InformationAccess construction in existing mocks and persistence fixtures; compiling a full-db fixture does not require running PostgreSQL |
| Documentation | Record the supported READ/WRITE seam and remaining migration gaps |

No changes are planned to protocol/SDK contracts, operation catalogues, transport
handlers, ProjectRole wire names, ProjectMembers SQL, Flyway, membership data,
SYSTEM worker definitions or model dispatch. If the implementation needs those
changes, report the concrete dependency and revise this scope before expanding it.

## Verification

Use mocked ProjectMembers and mock-backed ApplicationProjectMembers, plus existing
information repository interfaces. These are authorization/compatibility tests,
not proof of live PostgreSQL or a complete Global migration.

| Case | Required evidence |
| --- | --- |
| Role and resource isolation | VIEWER reads but cannot write; CONTRIBUTOR/MANAGER can pass the project gate; no membership or a role on another project grants neither action. |
| Effective Application/token ceilings | A stored MANAGER narrowed to VIEWER by the manifest can only read. Revoked/removed manifest authority and token role ceilings cannot be bypassed by using raw membership or server-admin status in the adapter. |
| Live narrowing | A previously admitted context fails its next READ after access removal, and its next WRITE after downgrade to VIEWER. No cached grant survives those checks. |
| Boundary validation/failure | Missing/blank principal or target refuses; role-source failure never allows access or routes to Global. A registered ordinary project named global is treated like any other project. |
| Existing private/service selection | Own-Personal mapping still works; other users' Personal and service Personal/Shared/includeShared selections still refuse. Existing human Personal/Shared defaults do not change. |
| Resource policy remains authoritative | Passing project READ/WRITE does not bypass current owner checks, mandatory/reference/input constraints or lifecycle gates. Denied work does not call the protected mutation. |
| Composition and public compatibility | The InformationAccess bean uses the effective Application-aware role adapter. Existing feature-config, navigation and upload tests still exercise the existing contracts, without new endpoints or SDK methods. |

The new `ScopedPermissionsTest` and extended information tests cover this matrix.
A mock-backed ApplicationContextRunner test loads production DocumentsConfig and
the primary ApplicationProjectMembers bean: a raw stored MANAGER capped to VIEWER
remains read-only through the injected adapter, and manifest removal refuses its
retained context. This uses no database or new test infrastructure. Focused checks:

```sh
./gradlew :plowshare-server:test \
  --tests 'io.aeyer.plowshare.server.access.ScopedPermissionsTest' \
  --tests 'io.aeyer.plowshare.server.information.InformationAccessTest' \
  --tests 'io.aeyer.plowshare.server.archive.ApplicationProjectMembersTest' \
  --tests 'io.aeyer.plowshare.server.information.InformationContentAdmissionTest' \
  --tests 'io.aeyer.plowshare.server.information.DocumentNavigationTest' \
  --tests 'io.aeyer.plowshare.server.information.InformationFeatureConfigTest' \
  --tests 'io.aeyer.plowshare.server.api.AuthoredDocumentUploadTest'
./gradlew format
./gradlew formatCheck
./gradlew check
```

The focused command passed **47 tests**, with no failures or skips. `format`
and `formatCheck` passed. The initial full `check` command ran 5,705 server tests:
5,704 passed and one failed in the then-unchanged
`CodeMapToolTest.live_symbols_page_and_exact_unicode_reads_are_hash_bound`
(line 158: expected three symbols, received zero). A single-test rerun also failed;
later ordinary runs passed, confirming intermittent behavior. The separately
authorized cold-start follow-up below addresses this failure; it is not slice 02.
The initial console dependency-resolution failure was
cleared by building the existing MCP integration and installing the Desktop's
locked dependencies, with no tracked client/build changes.

### Code-map cold-start follow-up

Original-history commit `e06e2a4af343aa0a088b608e9b20f7d1df5b33b0`
("Add Tree-sitter code outlines and scoped symbol lookup", 3 October 2026)
introduced the two-second deadline before native parser/grammar initialization.
`38f2df87b0ed77a2fd16fec3ebdcb6878c59c569` added the affected workspace test.
Both first entered this repository through public snapshot
`d38d55f4943f180dfbe7d4d9ff2249e2a8902874`; the parser implementation subsequently
remained unchanged apart from formatting. These original-history hashes are
provenance from the earlier repository, not commits in the public snapshot lineage.

A deterministic class-loader regression delays parser loading beyond two seconds:
the old implementation returned `limited` / `outline_limit` with no symbols for
valid Java. The deadline now starts after parser/grammar initialization, so the
same source produces all three declarations. The two-second parse/traversal
budget, interruption and existing input/node/symbol limits remain intact. No
schema, permission, parser grammar/version, retained-data rewrite or new API is
involved. The existing workspace test now includes its response in assertion
diagnostics. A separate test verifies interruption still refuses the outline.

The code-map/parser/workspace tests and slice 01's permission/admission tests
passed **31 focused cases** after the fix, with no failures or skips.
`format`, `formatCheck` and the full repository `check` then passed. The final
server test run passed **5,707 tests**, with no failures or skips, including the
original code-map case and both new regression tests. The earlier repository-check
blocker is resolved; no PostgreSQL/full-db tests were run.

No PostgreSQL/full-db run is required for this slice: it changes no SQL, row mapping, migrations or
transaction behavior. ProjectAuthorizationTest and several construction fixtures
are full-db tests; do not run them merely because their constructors compile
against the new interface. Any persistence change requires a scope revision and
a stated PostgreSQL-specific reason for the smallest selected full-db tests.

## Limits, rollback and completion gate

This slice retains live checks at existing boundaries. It does not establish an
atomic grant-revocation-versus-commit guarantee, eliminate legacy owner equality,
or retrofit retained authority into every job. Existing transaction/lifecycle
fences remain; their later migration needs consumer-specific verification.

Rollback is a code/configuration rollback with no persisted authority format to
reverse. It must not require token reissuance, data cleanup, model re-execution
or replay of uncertain effects.

The slice is complete only when the contract is used by the production information
gate, effective role ceilings and negative/revocation cases pass, existing
supported behavior remains compatible, required formatting/checks pass, and the
remaining Global/owner/SYSTEM gaps are reported honestly. Passing mocks does not
prove a Global account, SYSTEM execution sandbox or monetary budget ledger exists.

The implementation and focused authorization checks are delivered. The cold-start
follow-up is separately scoped to the code-map regression above. No live deployment,
SQL policy proof or atomic revocation-versus-commit proof is claimed.

## Deferred from this slice

- Fixed SYSTEM/Global account/role/scope records, UUID/storage mappings and bootstrap.
- Global/manual/legacy Shared migrations, root-document policy changes, created_by
  backfill, ownerOnly conversion and manager audience-management implementation.
- Removing implicit server-admin access or changing human/Application role grants.
- Enforcing CONVERSATION_CREATE/RUN_MODEL across runtime/model/embedding consumers.
- Independent SYSTEM processing, creator-access decoupling and operation budgets.
- New omitted Application defaults, expanded acting targets, ACL/group engines,
  SDK/client parity, lifecycle controls or new APIs.

Those remain governed by the consolidated proposal. The roadmap documents the
recommended follow-on slices, beginning with explicit identities/scopes. Their
plans and dependencies do not automatically authorize implementation.
