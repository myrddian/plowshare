# Application-scoped agent delegation

Status: accepted following explicit authorization of the core delegation change.

## Need and boundary

Application agents resolve and appear in the caller's project catalogue, but
`agent_run` used only the server startup registry. Project-local specialists
therefore passed loading and declared-call validation yet were refused at execution.
This is a general platform gap for Applications, project agents and unattended work,
independent of the Network Privacy Watch integration that exposed it.

An SDK cannot choose the internal delegation registry. Starting a second external
agent job would lose the shared allowance, cancellation and delegation transcript
contract; installing every project specialist globally would discard scope isolation.
The fix belongs in the server harness. External adapters remain SDK consumers.

## Decision

Inject the narrow `AgentDelegates` contract into the runtime. Composition resolves
it lazily through the existing caller-aware definition loader, avoiding the startup
cycle between runtime tool names and agent validation. Schema descriptions use the
current readable catalogue; execution rechecks session ownership and project work
membership and resolves the target again in the inherited account/home/session.
No model argument selects that context. There is no fallback to another account,
project or SYSTEM identity when authorization or resolution fails.

The admitted caller's declared `calls:` list remains the upper bound on target names.
A fresh callee must be delegable and cannot exceed the admitted caller's workspace
grants. Exported status remains an external-submission rule, not an additional
internal-delegation requirement. Application resources, revision invalidation,
client-root eligibility and Personal isolation remain owned by the existing loader.
The child retains its own tools and grants and inherits the execution identity.

Keep the existing shared budget, cancellation, child conversations, approval
propagation, images and scripted delegation lifecycle. Convenience constructors for
isolated runtimes retain a single-registry implementation; production uses scoped
resolution. Unscoped schema inspection remains startup metadata without granting an
execution identity. Missing/disabled targets are ordinary refused tool results;
revoked work/session authority returns `E_NO_ACCESS` before opening a child.

## Compatibility and verification

The model tool schema and public operations do not change. All SDKs and clients use
the same server behavior; no migration, SQL, deployment convention or adapter change
is required. Mock-mode tests use real Application directory loading and scripted
model responses to cover project isolation, unexported delegates, live permission
revocation, removed projects and definitions, workspace escalation, foreign sessions and absent
identity. Existing delegation, approval, scripted-failure, resolver and wiring tests
preserve lifecycle and catalogue contracts. No database test is needed because the
owning membership and persistence operations are unchanged.
