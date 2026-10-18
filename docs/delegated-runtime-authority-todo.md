# TODO: complete delegated execution without a restricted runtime copy

Status: handoff for future implementation; no runtime change authorized by this document.
Recorded: 19 October 2026.
Source baseline: `4271689728c1d04c2efaef36373f2aa382bb0c25`.

## User problem and intended outcome

An authenticated integration acting for a user should be able to talk to that
user's agent with its ordinary granted skills. Merely declaring skills must not
make the whole agent unusable. Execution must remain within the original acting
proof, subject authority, selected scope and existing capability restrictions.

The user identified the restricted-runtime-copy approach as a maintenance and
consistency concern. Investigate replacing it with the ordinary runtime execution
path carrying an explicit per-run authority context. This is a proposed direction,
not a claim that this context already exists or a mandate for a second framework.
Accounting products are deferred. Preserve the execution identities and lifecycle
relationships needed for authorization, revocation, cancellation and safe recovery.

## Verified current behavior

- There is one `JobRuntime` implementation. `forAdmittedTurn` constructs a second
  configured instance through `retainedCopy`, sharing the dispatcher and registry
  while wrapping selected capabilities and manually copying selected dependencies.
- `retainedCopy` does not carry the skill runtime. It also deliberately omits other
  independent capabilities. Do not simply copy every field or delete the guard:
  those capabilities need the original acting authority throughout their work.
- `RetainedAgent.require` rejects any agent declaring skills, orchestrations,
  unsupported tools, board/dynamic/inbox/reviewer/fallback behavior, or native/script
  execution. This happens at admission, before any such capability is invoked.
- The guard was introduced in `06fdf45c4b0d0f558bee0709639fb72a5f532c3e`
  (17 October 2026), with the bounded Application acting implementation. It was not
  introduced by the Global refactor and remained unchanged through slice 18.
- Skills are not all independent workers. DIRECT mode returns instructions to the
  current conversation; other modes route through child-agent/context machinery.
  The current blanket guard rejects both.
- Existing synchronous tool and ordinary child-agent paths carry admitted checks.
  That supported subset does not prove skill, reviewer, orchestration, provider or
  background-work authority propagation.

## Source map and call chain

1. [PersonalActingWork](../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/PersonalActingWork.java)
   validates actor/subject proof and calls `RetainedAgent.requireRoot` before
   admission and again after commit, then passes a retained `RunAdmission` onward.
2. [Turn.speakAdmitted](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Turn.java)
   rechecks admission and root suitability before starting the turn.
3. [JobRuntime](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java)
   contains `forAdmittedTurn`, `retainedCopy`, confined execution and child checks.
4. [RetainedAgent](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/RetainedAgent.java)
   and [RetainedToolAccess](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/RetainedToolAccess.java)
   define the current blanket refusals and synchronous resource extraction.
5. [SkillRuntime](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/SkillRuntime.java)
   owns discovery, invocation, DIRECT activation, package reads and worker routing.
   Follow its `CallerAccess`, `SkillContexts`, `AgentRunTool` and receipt boundaries;
   an account name alone must never reconstruct wider ambient authority.
6. [ADR 0026](decisions/0026-service-execution-on-behalf-of-an-account.md),
   [ADR 0030](decisions/0030-authority-substrate-and-explicit-scopes.md) and
   [slice 12](authority-substrate-slices/12-account-acting-delegation.md)
   describe the independent Application authentication and bounded acting model.

## Work for the implementing agent

- Read repository instructions and coding standards; inspect the current tree and
  revalidate this source map before changing code.
- Trace authority through admission, definition/skill resolution, discovery,
  package reads, DIRECT activation, child contexts, execution, approvals and result
  disclosure. Identify where it is currently lost or reconstructed from an account.
- Prefer one execution path with a typed per-run authority context. Carry the
  original actor, subject, home/scope, retained operation and acting checks without
  mutable global "current user" state or a manually maintained capability subset.
- Preserve normal agent/tool/skill/filesystem/provider grants as additional bounds.
  A skill provides instructions and declared routing; it grants no extra authority.
  Keep private SYSTEM/harness skills protected from ordinary delegated invocation.
- Support ordinary DIRECT and worker skills without requiring users to duplicate
  or strip down their agent. Worker skills must retain ancestor restrictions and
  proof validity rather than acquiring fresh subject authority.
- Replace blanket capability refusals only as the relevant paths become correct.
  Produce a concrete support matrix for remaining reviewer/orchestration/native/
  background paths. Any remaining functional refusal is an explicit unfinished
  item; do not label it optional merely because it is safely blocked.
- Preserve durable skill invocation identities, receipts, cancellation and the
  existing unknown-outcome/no-replay rules. Reuse lifecycle owners and public
  contracts; no new public APIs, consent flows or accounting products are implied.

## Acceptance and verification

- A user's exported agent with a granted DIRECT skill starts through the existing
  acting API, discovers/reads/uses that skill, and retains its normal restrictions.
- A granted worker skill runs its child under the same bounded acting authority;
  instructions, context modes and child selection cannot widen scope or grants.
- Expiry, revocation, disabled identities and lost subject access stop subsequent
  protected actions and result disclosure, including queued and child work.
- Other users' Personal resources, unrelated projects, private dependencies, client
  sessions and files outside the workspace/provider grants remain inaccessible.
- Existing ordinary execution, supported acting tools and child-agent behavior
  continue to work. Parallel users and children cannot exchange authority state.
- Retry/reconnect/approval continuation preserves original identities and receipts;
  uncertain mutations and completed skill invocations are never silently replayed.
- Use mocked repositories by default. Run focused PostgreSQL tests only for actual
  changed persistence/transaction/locking assertions, with the explicit full-db
  opt-in. Run required formatting and the default repository checks after code work.
- Update the slice 12/18 support and completion claims to reflect actual coverage.
  Do not claim full delegated-agent support solely because an admission check passes.

## Separate consistency issue

Global knowledge reads still have a separate mapping mismatch: memory tools use a
captured Global-read ceiling, while document tools build information selections
independently and service-account project selections omit general/shared discovery.
Do not solve that by turning on all legacy Shared access or widening acting proofs.
Keep this issue visible when designing the common per-run read authority, but do
not silently absorb its implementation into this handoff's scope.
