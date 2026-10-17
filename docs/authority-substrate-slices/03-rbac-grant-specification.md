# Slice 03 grant compatibility and transaction specification

Implementation specification, 17 October 2026. Internal contracts only; no new
transport operation, role editor or client capability. Read with [slice 03](03-rbac-grants-and-role-helpers.md).

## Compatibility and activation

| Source | Scoped helper | Data and control boundary |
| --- | --- | --- |
| Project VIEWER | READER | READ only; wire name stays VIEWER. |
| Project CONTRIBUTOR | CONTRIBUTOR | READ/WRITE and the declared conversation/model action vocabulary; execution consumers migrate in 06–07. |
| Project MANAGER | MANAGER | Contributor actions plus local management and audience choice; audience consumers migrate in 05. |
| Human server administrator | ADMIN in administration and Global knowledge scopes | Administration covers ordinary project control explicitly. No Personal/client coverage. Global helper has READ/WRITE/control, no execution. Existing APIs retain their independent domain gates. |
| Previously implicit administrator project MANAGER | Separate compatibility MANAGER grant per existing ordinary project | Preserve existing effective data access, with recorded migration provenance; active only while the source administrator flag remains valid. New projects/administrators do not receive this data grant. Application/token limits still narrow it. |
| SYSTEM | Intrinsic root-policy binding | Persisted root policy, never an assignable role or identifier shortcut. No new execution consumer is enabled. |
| GLOBAL_READ | Existing role identity only | Audience activation remains 05. Merely storing a helper never publishes a resource. |

Reserved standard role UUIDs extend the registry: ADMIN suffix 7, MANAGER 8,
CONTRIBUTOR 9, READER 10 (hexadecimal UUID suffix `000a` for READER), under the
existing `00000000-0000-0000-0000-` prefix. Conflicts refuse migration.

Only the existing membership management and access-metadata surfaces consume
administrative project control in this slice. Other legacy `mayManage` and
`requireRole(MANAGER)` consumers still require a data MANAGER binding. Mapping
those operation families is their documented later consumer work; a control
binding cannot become a broad legacy MANAGER bypass.

Grants target an exact persisted scope. Explicit coverage values are SELF,
ORDINARY_PROJECT_CONTROL (administration ADMIN over its own scope and ordinary
projects, control actions only), and
ALL_SCOPES (intrinsic SYSTEM root policy only). There is no inferred project
ancestry or resource hierarchy. Restricted/client scopes cannot be granted via
this contract; resource-level policies and Global audience await their slices.

## Issuance and agent exception

Authenticated server callers supply the issuer and a live issuing grant reference;
payload role names or account UUIDs alone prove nothing. The issuing grant must
belong to that issuer, cover the destination and permit delegation of that helper.
MANAGER can issue READER/CONTRIBUTOR/MANAGER locally; ADMIN can issue scoped roles
within its control coverage. These versioned helper delegation ceilings are
separate from the issuer's own exercise actions: administrative permission
management can issue a deliberate data grant without supplying implicit data
access to the administrator. A forced MANAGER exception requires an enabled human
USER issuer with live access-management authority. Service accounts are not
automatically agents. Agent recipients are explicitly named under their execution
account, scoped independently of its direct account grants. Agent READER and
CONTRIBUTOR remain unchanged; MANAGER defaults to CONTRIBUTOR. ADMIN, SYSTEM and
GLOBAL_READ agent assignments refuse. SYSTEM and GLOBAL_READ are not issued by
this ordinary role-management contract. An agent cannot issue ADMIN, authorize a
forced exception, or lend its own exception to a child. No arbitrary action-set
issuance exists that could reconstruct ADMIN. The result returns requested and
effective roles before callers may start dependent work.

Existing membership APIs continue to assign only account roles. There is no
agent-force field in those operations. Agent runtime retention and child wiring
remain 06/11; this slice does not claim their activation.

## Transactions, narrowing and recovery

For internal issuance the trusted caller allocates and retains the grant UUID
before mutation. Reconcile that identity after uncertain completion; duplicate
issuance refuses instead of replaying or issuing another grant. Existing public
membership commands retain their current contracts; no new request/receipt API
is added. Persist immutable grant UUID, recipient, issuer, issuing grant, scope, coverage,
requested/effective role, role version (1), force decision, provenance, timestamps
and optional expiry. Revocation increments a mutation version and retains both
grant and audit. A replacement is a new grant; it never rewrites old lineage.
A child has no later expiry or wider coverage than its issuer. Chains are acyclic
and limited to 32 edges. Every protected check rereads live lineage, recipient and
issuer activity; expired/revoked/disabled ancestors invalidate descendants.
No decision cache or widening union with a retained operation is introduced.

Use the project-then-scope row lock to serialize issuance, membership mutation
and revocation. Share-lock the immutable ancestor chain before issuing a child,
so cross-scope grant revocation cannot commit between validation and issuance.
Record grant/audit and compatibility membership changes atomically. Existing
project membership source inserts used by project/Personal provisioning receive
explicit root-policy provenance, distinct from human issuance. Existing membership
updates/removals revoke their prior bindings; unrelated direct grants remain
separate. Last-manager protection evaluates live resulting membership managers
under the project lock; an explicit administrative control grant retains the
existing administrative recovery exception. Failed checks roll back all rows.
An existing manager cannot replace the very membership used as its issuing
authority: another manager or an independent administrative control grant must
authorize that replacement. Deleting a project retains the grant's scope UUID
and audit; its absent scope mapping makes the grant ineffective. A recreated
project name gets a new scope and cannot recover that authority. Project-membership
credential issuers retain their exact token reference/expiry and live MANAGER
ceiling; revocation or narrowing invalidates their issued bindings. Manifest
checks cover every MANAGER issuer in the chain, as well as the recipient.
Version mismatches refuse; do not retry unknown mutations automatically. Restart
reads persisted UUIDs/audit, never issues replacements. Rollback must not restore
legacy administrator or membership checks that ignore grant revocation.
