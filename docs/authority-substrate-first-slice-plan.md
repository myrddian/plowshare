# Authority substrate: first implementation slice

Status: proposed implementation scope, 17 October 2026. Planning only; this
document does not authorize implementation, new APIs or new user capabilities.

This is **slice 01 of the [14-slice implementation roadmap](authority-substrate-implementation-plan.md)**.
Its existing scope remains unchanged; each follow-on slice has its own document
and dependency/verification boundary.

Read with [ADR 0030](decisions/0030-authority-substrate-and-explicit-scopes.md),
the [impact report](authority-substrate-impact-report.md) and
[role matrices](role-scope-permissions-proposal.md). Source was checked in the
proposal checkout after documentation commit `63c57b6`; the runtime baseline
remains main `269854a7c5bcc5f7080bc04de01ea8ccce46eaa7`.

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
| [InformationAccess](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationAccess.java) | Project selection rechecks mayUse; project work additionally checks mayWork. Authentication, Personal mapping and service exclusion from Personal/Shared remain owned here. |
| [ProjectMembers](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ProjectMembers.java) and [ProjectRole](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ProjectRole.java) | Live effective roles and existing VIEWER/CONTRIBUTOR/MANAGER vocabulary. No wire-role rename, membership migration or enum insertion. |
| [ApplicationProjectMembers](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ApplicationProjectMembers.java) | The primary injected role source intersects stored roles with current Application-manifest limits and service-token ownership/ceilings. This wrapper must not be bypassed. |
| [JdbcProjectMembers](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcProjectMembers.java) | Existing persistence, account/token role resolution and current Personal/legacy server-admin compatibility. No new SQL or duplicate role lookup. |
| [DocumentsConfig](../plowshare-server/src/main/java/io/aeyer/plowshare/server/documents/DocumentsConfig.java) | Owns the InformationAccess bean today; composition can supply the new contract while services depend on its interface. |
| [InformationCatalogue](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationCatalogue.java), [InformationSql](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationSql.java) and [InformationWriteGates](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationWriteGates.java) | Existing call sites, per-resource readability/owner/input constraints, parameterized filtering and durable transition gates remain in place. Passing scoped WRITE is not a bypass of these checks. |

In production, do not substitute raw JdbcProjectMembers, isMember, a role supplied
in a request, or an AdminStore shortcut for the effective injected ProjectMembers source.
Application manifests may narrow existing authority; they do not create membership.

## Changes proposed for this slice

### 1. Define the narrow contract

Introduce a small constructor-injected permission interface in the server access
package, with validated authenticated-principal/project-target values and explicit
READ/WRITE requests. Candidate names such as `ScopedPermissions`, `ProjectTarget`
and `PermissionAction` are implementation names to finalize, not new public DTOs.
Keep the contract no wider than the first consumer requires.

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

Back the contract with the effective ProjectMembers interface and its live role
resolution. Evaluate each request afresh; do not cache an allow decision in an
InformationContext or invent another permission store.

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

Wire the effective ProjectMembers adapter in composition and inject the narrow
interface into InformationAccess. Update its constructor fixtures accordingly;
do not hide composition inside a service's compatibility constructor or let
production choose a raw repository to make tests easier.

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

## Verification for a future implementation

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

Add a focused mocked test for the new adapter (for example,
`io.aeyer.plowshare.server.access.ScopedPermissionsTest`; proposed, not present
today) and extend the existing InformationAccess tests. Include a mock-backed
composition check using the existing application-context test facilities: a raw
stored MANAGER capped to VIEWER by the primary ApplicationProjectMembers bean
must remain read-only through the injected permission adapter. This needs no
database or new test infrastructure. Reuse focused regressions:

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

These commands are a future verification plan, not checks run by this planning
task. Finalize the new test name with the implementation. No PostgreSQL/full-db
run is required for this slice: it changes no SQL, row mapping, migrations or
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
