# ADR 0025: Service-account attribution and spending pools

Status: proposed decision for review. Preserve the implemented identity rules below;
adopt a general spending-pool capability, with implementation deferred to separately
scoped platform work. This document does not enable or enforce a spending limit.

## Need and scope

A service Application should be able to have a separate spending allowance without
sharing a human's execution identity. An organization MCP connection can also have
several users' credentials, or an explicitly selected service credential. These are
different decisions: selecting an upstream credential determines the upstream
identity, not the owner of Plowshare inference or its spending allowance.

The platform need is broader than MCP: service Applications, agents, schedules,
embeddings and background processing all consume model resources. Existing model
capacity pools, per-execution model-call budgets and durable usage reports do not
provide an account-wide monetary reservation boundary. An adapter cannot implement
one safely by checking a report before each call: reports are projections, multiple
clients can race, and uncertainty can outlive a process. This is the general need
and SDK-only alternative required by [ADR 0001](0001-integration-boundary.md).

This decision completes the accounting boundary left by
[ADR 0019](0019-mcp-provider-hosting.md). It retains the existing provider mechanism,
server-held per-subject credentials, invocation receipts and instance-local native
hosts. It neither introduces an MCP billing ledger nor implements the separate
[service execution proposal](0024-service-execution-on-behalf-of-an-account.md).

## Current contracts verified in source

| Concern | Implemented behavior and source |
| --- | --- |
| Named service owner versus token principal | [`ServiceAccounts.issue/rotate`](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/ServiceAccounts.java) creates `@service/<token UUID>`. Another token gets another UUID; rotation changes the secret and retires sessions while preserving the UUID. The named owner handle is administrative grouping, not the execution principal. |
| Application identity | [Owned identity lifecycle](0017-application-owned-service-provisioning.md) retains one token UUID. Generic service-token rotation refuses Application-owned tokens; `application.token.issue` uses the original identity and request-receipt lifecycle. [Execution-account inheritance](0016-application-execution-account-inheritance.md) resolves omitted declarations, without creating authority. |
| Selecting a credential | [`CurrentProviderCredentialAccess.subject`](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/providers/CurrentProviderCredentialAccess.java) derives an omitted subject from authentication. Selecting another service principal requires server-administrator authority, an active subject and its Contributor access in the credential project. An arbitrary human or service-owner handle cannot be substituted. |
| Releasing a credential to a worker | [`OwnedHostedToolScopes`](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/tools/OwnedHostedToolScopes.java) retains caller/owner, executor, credential subject, connection/generations and agent with the original invocation. Execution release checks the exact live registration/socket, current grants, contract, deadline and unfinished invocation. Worker authentication alone is insufficient. |
| Owning model usage | [`UsageAttribution`](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/UsageAttribution.java) and [`UsageExecutionStore`](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/UsageExecutionStore.java) preserve admitted account and ancestry. Delegation cannot change account. SYSTEM attribution is separate from the admitted source owner and grants no authority. |
| Capturing cost | [`DurableInferenceAccounting`](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/DurableInferenceAccounting.java) freezes owner, actual capacity pool, billing route, model and optional price. [`AccountingRecorder`](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/AccountingRecorder.java) records intent before attempts and recovers metadata without repeating inference. Missing price/usage remains unknown. |
| Existing controls | [`Budget`](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Budget.java) grants model-call counts to an execution tree. [`LlmPool`](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/LlmPool.java) supplies capacity/queueing for an inference resource. Neither is an account spending pool. |

These links describe current implementation. The reservation lifecycle below is a
new platform contract to implement and verify, not a claim about those classes.

## Identity policy

Keep the following coordinates distinct:

- **Initiator:** the authenticated person or existing trigger responsible for an
  admission/configuration action. A background trigger need not have a human
  initiator. Do not invent an initiating human when the retained record has none.
- **Execution principal:** the server-admitted account under which the job runs,
  including an exact service-token principal. Neither a model argument nor a
  credential selector can change it.
- **Provider executor:** the configured worker serving the external tool. Releasing
  one invocation's secret does not let it adopt that caller's other work.
- **Upstream credential subject:** the owner of the selected MCP authorization.
  This is an exact credential reference, not a billing-owner selector.
- **Accounting owner:** the admitted owner retained in model usage, or the explicit
  internal SYSTEM attribution. Keep historical attribution immutable.
- **Spending pool:** a future server-selected allowance/reservation policy with its
  own stable ID. Pool membership is neither authentication nor project/tool access.

Illustrative identities below are roles, not configuration defaults. `H` is a
human, `S` an exact service-token principal and `W` the configured provider worker.

| Admission | Caller/execution owner | MCP credential subject | Provider worker | Plowshare model accounting |
| --- | --- | --- | --- | --- |
| Human uses their organization authorization | H | H | W | H |
| Authorized administrator H explicitly binds a service authorization to H's tools | H | S | W | H; never S merely because its secret was selected |
| Service token S or an existing S-owned schedule admits Application work | S | S | W | S; configuring the Application as a human does not make that human the run owner |
| Another token is issued under the same named service owner | New principal S2 | A separately authorized subject; no automatic adoption of S's grant | Configured W | S2; history remains with S |
| Same token or owned Application credential is rotated | Same principal S | Same subject identity, with live generation/authorization checks | Current authorized W | Same owner; old sessions are retired, not transferred |
| Native MCP is called directly in a client | Local host/profile identity | Local environment reference | Owning native process | No server inference is invented for a local MCP call |

The remote MCP provider may bill the upstream account selected by its credential.
That does not transfer Plowshare model usage. Tool-call receipts are execution
provenance; MCP success, `isError`, descriptions and annotations are not invoices,
reported token usage or authority to debit a different account. Explicit server
sharing of a native host retains the existing scope and admitted job ownership;
it does not make all clients or service principals eligible to use that host.

A human wishing to spend under S must use an already authorized admission that
actually runs as S. Editing a credential subject, agent name, provider name or
Application manifest does not convert an existing H-owned job into S-owned work.
Applications and service principals retain their current Personal restrictions,
project/token ceilings, tool grants and approvals.

### Credential and token changes

Store and authorize bindings against the exact `@service/<token UUID>` principal.
Rotation preserves that identity, but does not promise that an old session, worker
registration or cached upstream credential remains valid. Reacquire only current
authority; never repeat an uncertain external effect during reacquisition.

A newly issued token does not inherit another token's upstream grant, Application
ownership, usage ancestry or future spending-pool membership by matching its owner
handle. An operator may explicitly assign the new principal to an existing pool;
that does not move historical charges or reservations off the original principal.
Disabling/revoking a token or owner stops new authorized work. It does not erase
paid usage, settle an uncertain effect as zero, or invalidate retained receipt reads
that the owning lifecycle still permits.

### Relationship to acting grants and SYSTEM

The service-execution-on-behalf-of-an-account ADR remains **proposed**. Its actor
and effective subject would require separate retained authorization/audit fields.
This policy neither authorizes that implementation nor silently substitutes a
provider-worker token for an acting grant. If the proposal is accepted, its effective
subject remains the work/accounting owner and the actor remains provenance; any
separate pool sponsorship must be explicitly admitted by server policy.

Existing internal document processing remains SYSTEM-attributed. The admission
owner still controls access, processing allowance, hooks and cancellation. Future
pool enforcement must carry a separate trusted charge-policy reference from the
admitted processing root where one exists; this is not a change to SYSTEM usage
history. Work without a sponsoring admission needs an explicit operator SYSTEM
policy. SYSTEM is never an unbounded fallback for a human or service principal,
and uncertainty about a strict pool assignment must refuse new paid admission.

## Decision: adopt general spending pools; defer implementation

Adopt the capability as general accounting/platform work. Do not simulate it with
MCP credential storage, a capacity-pool name, a process-local counter, a report read
or a relabeling of `account_handle`. Implementation requires separate scoped work
and review before any enforcement is enabled. Existing model-call budgets and usage
capture keep their current semantics until then.

A pool has a stable server-owned ID, explicit authorized principal membership,
policy revision, configured accounting period and explicit limits. Monetary amounts
use exact decimal quantities in an identified currency; currencies are never silently
converted or added together. Quotas for calls/tokens are distinct units. A disabled
pool, a zero allowance and absence of enforcement are distinct states.

Use exact execution-principal bindings initially. Several principals, including
several tokens of one named service owner, may share a pool only through explicit
operator membership. A newly issued token cannot escape enforcement: when enforcement
is enabled for an admission class, an unmapped principal is refused rather than
given an unlimited/default human pool. Shared-pool configuration does not grant
access to other members' data, credentials or detailed reports.

### Admission and reservation lifecycle for the future platform task

1. **Resolve authority.** Before queueing a paid attempt, validate the existing
   account/project/tool/grant and execution lineage. Resolve the eligible pool from
   trusted server policy and freeze its ID with the admitted execution. Children
   inherit that policy; neither models, adapters nor client arguments select it.
   Independently admitted scheduled work resolves current authority/policy afresh.
2. **Quote the attempt.** Retain actual route/model, price revision, cost coverage,
   currency/unit, policy revision and the server-selected period. Establish a
   conservative finite bound for the attempt, including configured request fees,
   relevant price tiers and output/reasoning coverage. A reference-price UI estimate
   or an unsupported/unknown tokenizer result is not a reservation proof.
3. **Reserve atomically.** A specialist repository must check booked and held
   amounts and reserve against the applicable limits in one short transaction.
   Concurrent clients/workers cannot each spend the last available balance. Keep
   stable admission/reservation IDs and exact original receipts. Define the atomic
   relationship between the existing accounting admission/journal and reservation
   persistence before dispatch; a partial dual write never authorizes a send.
4. **Dispatch outside transactions.** Acquire bounded model capacity and record
   durable attempt intent before the upstream request. Recheck current authority
   before dispatch; a fallback route/model or changed cost bound requires admitted
   reservation coverage before sending. Cancellation/queue expiry before any send
   can release the hold once that non-dispatch is known. No JDBC transaction stays
   open over a queue wait, HTTP call or STDIO effect.
5. **Settle once.** Use the original attempt/reservation coordinate. Retries are
   separate potentially billable attempts and need separate admitted coverage.
   Settle known observations with the retained price; release only proven excess.
   Keep unknown usage/cost or uncertain dispatch visibly held/unresolved, rather
   than refunding it because a timeout, error or cancellation was observed.
6. **Recover without effects.** Restart reconciles reservation and existing
   accounting/receipt state read-only. Replaying metadata must be idempotent and
   cannot issue another model/MCP call. An administrative release/correction needs
   explicit authority, reason and retained audit; it is not proof the effect did
   not occur. Late observations remain attachable to the original attempt.

Each actual attempt is charged once, even if inclusive parent/child reports show
its ancestry several times. Delegated agents, orchestration children and paid
internal stages cannot obtain a fresh allowance merely by changing their actor,
route, model or process. Existing execution-tree call budgets apply in addition to
the pool limit; acquiring capacity is not spending permission.

Policy changes and period rollover never rewrite old reservation coordinates or
booked price/history. A retry is a new attempt, not a replay of an uncertain effect.
A stricter limit or disabled pool blocks new admissions while in-flight liabilities
remain observable. Record actual overrun/late cost truthfully rather than discarding
an observation to preserve an artificial balance invariant; block further admissions
when the configured contract/balance is no longer safe.

Strict monetary limits require finite, trustworthy cost coverage and healthy durable
accounting/reservation state. Unknown/missing pricing, unsupported bounds or disabled
capture cannot be treated as free usage. Refuse an attempt subject to such a cap
before sending it, or require a separately configured non-monetary policy whose
coverage is explicit. Existing capture-only behavior with unknown prices does not
magically become strict monetary enforcement.

### MCP costs, reports and access

The first model spending-pool implementation covers supported Plowshare inference
attempts, including their defined internal/embedding paths. MCP tools have no
standard trustworthy charge settlement in the current integration. An external
vendor's costs stay unknown/outside that model cap unless a separately reviewed,
trusted tariff/meter can bound and settle them. Never claim a model allowance caps
an organization's total upstream MCP invoice. A policy requiring bounded external
tool charges must refuse unsupported billable tools; untrusted server annotations
or a result field cannot satisfy that requirement.

Pool policy/membership changes require explicit administrative authority. Ordinary
users retain existing authorized usage/project views; selecting a service credential
does not grant its usage history. Pool reports must distinguish booked, reserved,
unknown, released and overdrawn state, with immutable attribution and coverage.
Project/report permissions remain in force even when several projects share a pool.
Administrative aggregate reporting must not become a service-token route to another
member's call history. The existing `usage.pools` capacity/fleet view is not a spend
balance or a membership-management API.

## Alternatives, rollout and compatibility

- Keeping only usage reports is compatible and useful, but does not enforce an
  allowance. It is the truthful current fallback while implementation is deferred.
- Making each MCP credential a budget would charge the wrong execution, invite
  cross-user access and require a second accounting system. Reject this option.
- Treating each LLM capacity pool as an account budget conflates scheduling with
  liability, and duplicates model endpoints merely to separate service accounts.
- A sidecar checking `usage.*` before dispatch cannot serialize spend, cover every
  paid path or reconcile cross-process uncertainty. Keep adapters SDK-only.

Enforcement must be opt-in through explicit validated deployment policy, with no
hard-coded account/pool names or silent unlimited fallback in an enabled class.
No historical backfill or reassignment is part of enabling it. Disablement stops new
admissions, not accounting settlement or historical reads. Downgrades must preserve
original accounting, journals and unresolved reservations; an old server must not
silently dispatch work whose enabled enforcement contract it cannot honor.

## Implementation and verification gates

Follow-up work belongs to the general platform, not an MCP adapter:

1. **Durable policy/admission:** typed interfaces, specialist reservation storage,
   new Flyway migrations, atomic admission/receipt design, policy/membership,
   bounded costs and all paid dispatch/recovery paths. No JDBC in tools/adapters.
2. **Public administration/reporting:** shared typed WebSocket contracts and all
   affected SDKs/clients for authorized policy/status/receipt reporting. Keep model
   pool names, upstream subjects, execution accounts and spending-pool IDs distinct.
3. **Acceptance:** multi-client concurrency, rotation/new-token boundaries,
   delegation/SYSTEM ancestry, unknown prices/usage, paid failures, queued versus
   submitted cancellation, process loss, late settlement, disablement and rollover.
   Use mocks by default; PostgreSQL tests are required only for actual reservation
   transactions/constraints/locking and must be narrowly selected with `-PfullDb`.

Current identity/ownership fixtures exercise real service issue/rotate behavior
with mocked specialist repositories, actual accounting-admission metadata on a
shared capacity pool, exact hosted invocation subject/worker/caller separation,
and administrator/current-membership credential-selection checks. Existing journal
and call-budget fixtures cover metadata recovery, unknown outcomes and shared call
limits. These tests do **not** prove future monetary reservation enforcement or
live MCP billing interoperability. See the [test/source map](../mcp-accounting-policy.md).
