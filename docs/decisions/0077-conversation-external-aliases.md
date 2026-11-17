# ADR 0077: Core conversation aliases and Ex facades

Status: proposed implementation contract for CORE-01; pending independent review.
No alias table or Ex operation exists at this revision. CORE-02–CORE-04 own the
implementation and SDK compatibility evidence.

## General platform need and authorization

External clients have their own durable conversation identifiers. Making every
client retain an authoritative external-to-Plowshare map duplicates collision,
ownership, restart and retention policy. A separate resolve RPC also adds a
network request before each ordinary operation. Core should own this relationship
and resolve it within the operation that needs it, while the existing conversation
and run owners retain their lifecycle.

The user explicitly proposed a generic `CONVERSATION_ID_MAP` relation and
`ConversationOpenRequestEx` / `AgentRunRequestEx` facades, then authorized execution
of the independently reviewed delivery graph with “Start it”. This is the narrow
platform slice required by [ADR 0001](0001-integration-boundary.md), independently
useful to all external SDK consumers. It does not authorize an arbitrary
per-request system/message-role API or adapter access to core SQL. The Home
Assistant package remains an SDK consumer.

## Identity and ownership

The unique logical key is:

```text
(resolved project ID, authenticated authority-account UUID, source, externalConversation)
    -> Plowshare conversation ID
```

`project` is a required explicit project name, resolved by the existing project/
authority owners. Global, omitted/null projects and contained project destinations
are refused. Personal is supported only through the caller's explicit named
Personal project and its normal admission. Project names are not durable keys:
renaming preserves the project ID; deleting and recreating a name yields another
namespace.

The server derives the owner UUID from the live authenticated account. The wire
body cannot supply an owner, account UUID, acting subject or internal conversation
ID. Requests without an authenticated account are refused. Token rotation and
new socket sessions for the same account retain aliases; another account, even a
project manager, cannot select that account's aliases through these facades.
Alias ownership is a lookup partition, not permission to access its target.
Existing project/conversation authority and service-token ceilings still apply.

`source` is an account-local namespace, not a globally registered or attested
external identity. An authenticated caller can select labels only beneath its
own UUID. The full account-qualified namespace is therefore authenticated; a
supplied string cannot impersonate another account's source or grant authority.
No separate source registry, source-admin role or caller-selected owner is added.
Separate installations and profiles sharing one service account must configure
distinct stable source labels. Credential rotation must preserve both account and
configured source to continue the same mapping. This limitation is explicit:
external user/device identity supplied by a service is not independently attested
by Plowshare. The adapter must partition its opaque IDs by authenticated external
caller/profile context before submitting them; an HA conversation ID alone is
insufficient when different HA callers can present the same string.

`source` is 1–128 ASCII characters matching
`^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$`. `externalConversation` is 1–256 visible
ASCII characters (`^[\x21-\x7e]{1,256}$`). It is opaque and case-sensitive; never
trim, case-fold, Unicode-normalize, URL-decode or reinterpret it as an internal
ID. Whitespace, control characters, non-ASCII identifiers, empty values and
oversize values are refused before lookup. Clients needing other identifier
alphabets must use a stable reversible encoding. Both fields are validated in
canonical SDK schemas, server boundary DTOs and repository value contracts;
unknown body fields are refused. Do not log these identifiers or include them in
metrics labels or refusal diagnostics.

## Exact public requests

Add two WebSocket operations; no HTTP alias endpoint or silent fallback:

| Operation | Public SDK DTO | Required fields | Optional fields |
| --- | --- | --- | --- |
| `conversation.open.ex` | `ConversationOpenRequestEx` | `project`, `source`, `externalConversation` | `maxModelCalls`, `maxTurns`, `noTurnCap`, `noBudget` |
| `agent.run.ex` | `AgentRunRequestEx` | `project`, `source`, `externalConversation`, `agent`, `task` | `session`, `maxTurns`, `noTurnCap`, `maxModelCalls`, `images` |

Omitted optional values retain the ordinary operation's meaning. `session` alone
may also be explicit null. Optional limits/booleans/images must have their declared
number/boolean/array type when present; explicit null is refused. Positive
integral limit bounds, task/agent/session validation and unknown-field rules match
their ordinary owners and canonical shared schemas. The three alias
locator fields are required non-null strings. `images` may be omitted or empty;
nonempty images are refused by the existing conversational run rule. The Ex
facades do not add image support. `conversation`, `newConversation`, `owner` and
other routing selectors are not Ex fields and are refused, including a false
`newConversation`. Run-Ex never means standalone work or open-on-first-run.

Open-Ex returns ordinary `OK` with the unchanged `ConversationView`: internal
conversation ID, project, allowance total/spending, turn cap, no-cap/no-budget
flags and title. Lifecycle is retained and validated internally; it is not a field
in that public view. Run-Ex returns ordinary `ACCEPTED` with `StartedJob`, including
its actual internal conversation/job IDs.
There is no public `created` flag, alias enumeration/lookup endpoint, metadata
wrapper or alternative result type. Existing status, inspection, cancellation and
resume APIs consume these internal IDs as before. HA still returns its own session
ID to Assist. All Java, TypeScript/Node, Python, Go and .NET operation surfaces and
derived catalogues must be updated together through their owning generators.

## Open-Ex: atomic get-or-open

1. Strictly decode the request and validate all creation options even when an
   alias might already exist. Authenticate the account/session and admit the
   explicit requested project before reading or disclosing an alias.
2. A typed alias repository coordinates lookup/create with the ordinary
   conversation-creation owner under one Spring-managed database transaction.
   Its narrow contract accepts a validated account/project-qualified key and
   typed creation options, and returns a typed conversation snapshot plus an
   internal `created` disposition. It owns SQL and transaction coordination;
   domain services/handlers receive neither JDBC nor arbitrary SQL/callback
   facilities. Do not implement a second conversation/allowance INSERT recipe.
3. A database unique constraint over the complete key serializes concurrent first
   opens. Alias, new ordinary TURN root and its canonical allowance binding commit
   together; a losing race or failure leaves no orphan conversation/allowance or
   visible partial alias. The ordinary creation owner must participate in the
   enclosing transaction rather than commit independently. No model, provider,
   external callback or lifecycle hook runs while the transaction/alias lock is
   held. The repository reuses its resolved target snapshot and does not
   fetch-discard-refetch it just to reconstruct the result.
4. On an existing mapping, verify the actual target project/ordinary TURN-root
   shape and current conversation access before returning its current view.
   Creation options are ignored after validation: they never alter existing caps,
   ceiling, spent usage, history, title, agent selection or lifecycle. If an open
   races spending, the view is a status snapshot, not a reservation.
5. Commit before the ordinary owner sends `log.open`, and send it only when this
   request created the row. Existing aliases never fire it again. Preserve the
   current post-commit/fail-open hook contract, including its existing crash
   window; this adds no transactional outbox or exactly-once delivery claim.

For an archived, marked-for-ejection or ejected mapped root, Open-Ex returns the
ordinary view of the same root and leaves its lifecycle unchanged internally; it
never revives or replaces it. The Ex reply does not expose lifecycle. A caller
that needs to distinguish lifecycle can inspect membership in the existing
`conversation.list` result with explicit project and lifecycle filters; that is a
point-in-time observation, not permission to run. `conversation.lifecycle` changes
state and must never be called merely to read it. A missing or invalid mapped
target is a refusal, not a fresh open. This distinction makes a
repeat Open-Ex safe for resolving an uncertain creation without creating a second
conversation, provided the caller uses the identical complete key. It does not
make subsequent Run-Ex requests idempotent.

## Run-Ex: resolve existing, then ordinary admission

Authenticate/validate and authorize the explicit project and owner-local source
before lookup. An absent alias returns `NOT_FOUND` with no conversation, allowance,
job, hook, inference or other effect. Never fall back to treating the external ID
as an internal ID or mint an alias implicitly.

Resolve a typed alias/target snapshot once and validate its actual home and TURN
root. Translate to the ordinary run request with that internal conversation and
**without** the external `project` selector. Keep the external home as validated
admission evidence; do not pass both project and internal conversation into
`Runs.start`, which refuses that combination. The ordinary run owner still checks
the current session, actual target scope, selected agent/tool authority, active
lifecycle, conversation exclusion, workspace fences, approvals and budget before
work. Reuse resolved typed metadata through a narrow owning seam; any additional
read must have a documented current-authority, lifecycle or concurrency reason.
A snapshot is not a retained allow.

`agent` remains explicit on every run. Ordinary TURN conversations may contain
turns by different agents, so the alias does not silently bind or choose an agent
and does not widen agent visibility. Intentionally selecting another authorized
agent has ordinary conversational semantics. Profiles requiring separate histories
must use distinct configured source labels/qualified external IDs; the adapter
must refuse an incompatible local profile binding rather than redirect it.

Omitted `maxModelCalls` leaves the existing allowance unchanged. An explicit
`maxModelCalls` retains [ADR 0069](0069-raise-allowance-with-the-next-utterance.md):
it is the caller's absolute new total, checked/applied by the ordinary turn owner
only after claiming/admitting the existing conversation. It never resets spent
usage or becomes an increment. This is an explicit ordinary allowance decision,
not a side effect of alias resolution. Open-Ex creation options cannot raise an
existing total. Run-level turn caps likewise retain their existing meaning.

Run-Ex returns after async admission, not model completion. Normal jobs,
accounting, cancellation, durable outcomes, retained work and restart limitations
remain authoritative. SDK cancellation/disconnect is not remote abort. The alias
chooses a conversation; it is **not** an utterance/request-id deduplication key.
After possible transmission without a reply, retain accepted-job evidence and an
uncertain-send fence. Never automatically resend Run-Ex, actions or model work.

## Retention, deletion and migration

Aliases have no independent idle expiry and no public reset, rebind, import,
transfer or delete operation in this slice. Retain them across HA session cleanup,
archival and ejection; never recycle a reused key onto another conversation.
Clients start a genuinely new conversation with a fresh external identifier.
A target FK restricts hard deletion while an alias references it; the current
lifecycle can eject payloads without deleting the conversation row. A later hard
purge/reset feature must define tombstones and uncertain-work handling before
weakening that constraint. Missing/corrupt targets fail closed; Run-Ex also
refuses non-active lifecycle through the ordinary owner. Explicit ordinary
unarchive may permit future runs in the same retained conversation.

At the persistence boundary, project/account hard deletion removes its alias
namespace through FKs; this does not add a supported hard-delete operation.
Recreated names receive new immutable IDs and cannot reconnect to the deleted namespace. Account
disablement, role/token revocation and project access withdrawal retain aliases
but prevent access under current ordinary authority. The current
[retention owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/Retention.java)
ejects conversation payloads and prunes job rows; it explicitly retains
conversation rows. The existing
[contained-Application destroy owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/JdbcContainedApplicationRepository.java)
only deletes contained projects, which Ex admission refuses. Thus the target FK
does not interfere with those supported cleanup paths; selected database tests
must verify that compatibility. Ordinary account administration disables accounts
and revokes sessions rather than exposing a general hard-delete API. No
creator-based access shortcut or automatic transfer to another principal is introduced.

Add a new Flyway migration; never rewrite shipped migrations or existing
conversations. Old clients/ordinary operations keep their selectors/results.
Rolling the server back makes Ex unsupported but must retain the table and
receipts; clients must not silently fall back and open/run duplicate work.

The prototype's local alias map cannot be authoritatively imported by either Ex
request: neither accepts an internal target chosen by a client. Use a documented
phased transition: retain original pending/uncertain correlation and legacy route
for existing keys, drain/expire their HA session normally, and use a versioned
core-owned route for fresh sessions only. A key has one recorded map owner/route,
never concurrent local/core mappings. Preserve old inspection IDs and history;
do not replay a task to manufacture a new binding. Seamless adoption of existing
internal histories would require a separately designed authorized bind/import
capability, which is outside this slice.

## Budgets, persistence and evidence

Alias work uses one configurable completion budget, default two seconds, covering
admission, connection acquisition, lock waits, all statements/materialization and
transaction completion. Nested ordinary creation/authority work shares the
remaining budget. Each query normally completes within 500 ms and has a ceiling
of the earlier of one second and remaining operation time. On expiry cancel SQL,
roll back active work, reject late results and retain uncertainty if commit status
cannot be established; never retry a possibly committed mutation. Track bounded
latency distributions, 500 ms/one-second breaches and cancellation in Prometheus.
Do not hold a connection while performing hooks, network or inference work.

This is the required new-path contract, not proof of existing universal SQL
cancellation: `ReadDeadline` currently owns cache/read waits, and existing SDK
reply timers exclude some queueing. CORE-02 must inspect actual owner enforcement,
implement the affected bounded alias path and report any wider remaining gap.
A new cache is unnecessary for this indexed point lookup; current authority is
checked every operation and aliases do not become permission leases.

Acceptance evidence before code-node closure:

- Mock/fake boundary tests: malformed/unknown/conflicting selectors; unauthenticated
  callers; cross-account/source/project isolation; revoked session/token/grant;
  wrong target home/origin; selected agent refusal; explicit profile partitioning;
  archived/ejected/missing targets; unchanged existing limits; ordinary raise/cap
  rules; run-on-miss; lifecycle callback disposition and old-API compatibility.
- Only selected `@Tag("full-db")` / `-PfullDb` tests for actual PostgreSQL migration,
  key uniqueness and FK behavior, concurrent first-open races, transaction rollback
  of alias/conversation/allowance, deadline/lock cancellation and no orphan rows.
  State that database-specific reason before running the smallest relevant set.
- One actual client request reaches normal admission through each facade; no
  client resolve RPC, server self-RPC, automatic mutation replay or unused
  projections. Ordinary run exclusion/cancellation and uncertain replies remain
  tested. A lost open reply resolves the same key; a lost run reply stays fenced.
- Canonical TypeScript contracts and generated Java/Python/Go/.NET catalogues
  remain synchronized; shared malformed/refusal/unknown-delivery fixtures and
  installed-consumer checks cover the new operations. Run required formatting,
  `check`, `sdkCheck` and affected `sdkPackageCheck`; missing toolchains remain
  unresolved evidence rather than a pass.

The implementation must distinguish proposed contracts, implemented behavior,
independent approval, merge, deployment and live validation. This ADR is the
contract for those later nodes, not their completion receipt.

Current behavior was checked against the owning
[ordinary open handler](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ConversationOpenHandler.java),
[run admission](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Runs.java),
[conversation repository](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ConversationStore.java),
[lifecycle](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ConversationLifecycle.java),
[account administration contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/AccountAdministrationRepository.java)
and [canonical SDK payloads](../../sdk/typescript/src/operations/catalog.ts).
These owners are implementation references; the Ex additions above are proposed.
