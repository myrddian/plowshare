# Discord integration web management and account linking plan

Status: proposed implementation plan, 21 October 2026. Source baseline:
`2cf4db4c2d72e9f99e43e343a38ff603278e8329`.

C1 and the service-chat portion of C2 are now implemented in the external adapter:
revisioned, stopped-only headless configuration edits and opt-in one-to-one DMs.
See the [operator workflow](../integrations/discord/README.md#configuration-management).
Legacy setup hashes and guild conversation keys are preserved on upgrade. This
slice adds no web listener, account linking, consent contract or delegated runtime
change. Onboarding commands remain dependent on track B; C3/C4 remain planned.

Provide an optional internal web app for managing the Discord integration, link
individual Discord users to their own Plowshare accounts, and support direct
messages to authorised agents. Preserve the existing Discord-only service mode.
Keep delegated runtime repairs, generic platform consent contracts, and external
SDK integration work in separate changes and reviews.

This plan authorises no implementation or deployment by itself. Core work follows
[ADR 0001](decisions/0001-integration-boundary.md) as separately scoped platform
tasks. The integration must report a missing public capability rather than patch
core or access its internal services.

## Deployment and user experience

Plowshare and the integration web app are reachable locally, on the internal
network, or through VPN. Neither requires a public internet listener. Bind and
advertised origins are explicit configuration. Discord traffic arrives through
the integration's outbound Gateway connection; SDK connections reach the private
Plowshare origin. No public callback or reverse connection from Discord is required.

The web app is available internally to managers and users. It is optional: the
same management capabilities remain usable from a headless CLI. A local install
and a shared internal install use the same authority model.

After linking, ordinary Discord messages do not require the user's device to be
on VPN. The running bridge reaches the private server. LAN/VPN access is needed
when opening the internal management or Plowshare consent pages.

| Person | Intended workflow | Authority boundary |
| --- | --- | --- |
| Application manager | Configure Discord, routes, allowed callers, health and recovery | Only the managed Application and authorised local configuration; installation and owned-token issuance/rotation retain current server-administrator requirements |
| Plowshare user | Connect their Discord identity, approve bounded Personal access, select an agent, inspect expiry and revoke their link | Their authenticated account and its own approved delegation; no other person's links or credentials |
| Discord-only user | Use explicitly permitted service chat without a Plowshare account | Application service authority and adapter routing restrictions |

Internal reachability does not identify a caller. The web backend must enforce
current manager/user authority for each action and response. Running a process
with a saved administrator login must not make every browser its administrator.
Managers see operational metadata, not users' acting secrets or Personal history.

## Current contracts and gaps

The baseline source provides these starting points:

| Capability | Current owner and status |
| --- | --- |
| Application install, service identity, private credential output, recover and rotate | [Setup engine](../integrations/discord/src/setup-engine.ts) and [repository](../integrations/discord/src/setup-store.ts); already implemented through public SDK contracts |
| Guild channels and threads, persistent contexts, status/cancel and Relay notifications | [Discord adapter](../integrations/discord/src/adapter.ts); already implemented in service mode |
| Discord DMs at baseline | Required DM events, separate destination DTOs and journal migration; the C2 service-chat slice now implements these in the [Discord boundary](../integrations/discord/src/discord.ts) |
| Public delegated execution | [SDK requests](../sdk/typescript/src/operations/administration.ts) and [server dispatch](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ActingRequests.java) support context open/close, Personal turn start/receipt, history and correlated job status/cancel |
| Token issue, revocation, metadata, receipts and eligibility | [ActingTokens](../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/ActingTokens.java) exists internally; public lifecycle operations and CLI commands are missing |
| Browser linking and web management authentication | No completed public contract establishes this workflow; platform contract work must supply it before authenticated shared web management ships |
| Agent capabilities under delegation | [RetainedAgent](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/RetainedAgent.java) still refuses skills, orchestrations and other unsupported capabilities; use the existing [runtime handoff](delegated-runtime-authority-todo.md) |
| Setup updates | C1 now separates operational revisions from the frozen setup hash. The basic manifest decoder still rejects `acting`; source updates and browser authority remain separate work |

The current delegated turn target is Personal. Existing project history reads do
not provide project acting execution. Do not attach `actingFor` to `incoming.*`,
Relay or other unsupported operations. A dynamic picker for the subject's agents
also needs an authorised public catalogue; service-mode `incoming.catalog` cannot
stand in for Personal discovery.

## Work tracks and review boundaries

| Track | Deliverable | Allowed scope | Completion gate |
| --- | --- | --- | --- |
| A Core delegated runtime | Carry admitted authority through supported agent capabilities | Server runtime, existing permission/lifecycle owners and their tests | Ordinary granted skills work under the original actor, subject, scope and revocation checks |
| B Platform consent and public contracts | Human consent lifecycle, linking and authenticated management authorisation | General server APIs, protocol, SDKs, CLI and Plowshare consent UI, with an ADR 0001 design decision | A public SDK consumer can complete approval, credential handoff, revocation and recovery without internal access |
| C SDK integration | Internal web app, adapter configuration, link routing, DM input/output and delivery recovery | `integrations/discord`, packaging and integration documentation; public SDK dependencies only | Local and VPN users can manage their own links and DM permitted agents; current service behavior remains valid |

Track A is a repair to delegated execution, not a Discord feature flag. Track B
is a generic platform capability usable by other integrations. Track C never
adds a server endpoint, migration, permission bypass or shared SDK contract to
make its own acceptance tests pass. Contracts from B must land before C consumes
them; test fakes do not establish that a missing contract exists.

## Track A Core delegated runtime

Use the [existing handoff](delegated-runtime-authority-todo.md), [acting action
specification](authority-substrate-slices/12-acting-authority-specification.md)
and [ADR 0030](decisions/0030-authority-substrate-and-explicit-scopes.md).

1. Trace authority through definition resolution, invocation, tool/skill reads,
   child creation, execution, approvals and output disclosure. Prefer the ordinary
   runtime with explicit per-run authority over maintaining another configured
   runtime copy. Preserve existing tool grants, workspace fences and budgets.
2. Make DIRECT skills and worker skills retain the original actor, subject,
   consent proof, scope, admitted ceiling and ancestor restrictions. Private
   SYSTEM skills remain protected. Remove each blanket capability refusal only
   when its actual lifecycle carries these checks.
3. Publish a capability matrix for orchestration, reviewer/fallback, board,
   native/script, dynamic providers, approvals and background execution. Assign
   each remaining gap to a separate runtime slice; a refusal remains unfinished
   functionality, not evidence of full delegated-agent support.

Acceptance requires permitted DIRECT and worker skills to work, including child
work, while cross-user access, expiry, revocation and disabled identities refuse
protected effects and disclosure. Reconnect/cancellation retain original receipts;
neither Application authority nor SYSTEM is a fallback for refused delegation.
The basic linked-chat release requires the skill repair, and may only advertise
the remaining capabilities that their individual slices actually support.

This track does not add Discord routing, consent screens or a token-creation API.

## Track B Platform consent and public contracts

### Authorisation policy

Application authentication and human consent remain separate. The Application
may request a link within its administrator-authorised manifest grant. The
signed-in human supplies consent for their own account. Eligibility remains an
administrator policy; approval cannot silently add an ineligible account.

Record an action/target matrix before implementation. `DELEGATION_REQUEST` is a
candidate description, not an existing permission or a settled enum addition.
Reuse existing scoped actions where their semantics fit. The actor must not gain
general authority to create human credentials, manage eligibility, or issue for
a caller-supplied account name. Effective delegated authority only narrows.

### Public contract slices

**B1 Consent lifecycle.** Expose typed human-owned issuance, metadata, revocation
and original-request receipt recovery, plus administrator-only eligibility
management. Reuse `ActingTokens` and owning repositories. Add coordinated protocol,
SDK and CLI support. A one-time acting secret is returned only by successful new
issuance; listings and receipts never reveal it again. Define the exact supported
targets, actions and refusal/recovery semantics in the platform design decision.

**B2 Link workflow and management identity.** Supply an expiring link transaction,
direct-human consent, actor-bound credential redemption, status/recovery and
cancellation. Supply a server-verifiable way for the integration web backend to
identify the human manager/user and authorise local management actions. Prove an
existing public session contract sufficient or add the smallest generic bounded
authorisation exchange in this platform slice. Do not trust browser-submitted
handles, external IDs, a network address or the service principal as human proof.
Do not send a user's password, refresh token or unrestricted login session to the
integration as the solution to linking.

Redemption consumes a durable consent record created under the authenticated
human's authority. The Application cannot call the current direct-human issuer
by manufacturing a `RequestAuthority.Direct` from a stored account name. Specify
the approved-consent redemption capability and dual actor/subject provenance in
the platform contract before implementing it.

Before C web mutations ship, settle identity expiry, revocation, Application and
UI-origin binding, proof replay limits and live manager/user permission checks.
An acting proof must not bootstrap credential-management or administrator rights.
Keep human consent on Plowshare's authenticated origin, reachable over LAN/VPN;
the integration UI directs the browser there and shows the resulting status.
Only explicitly configured internal origins/return destinations are permitted.

The user revokes consent through an authenticated own-account operation; existing
server-administrator revocation remains distinct. A manager can disable local
routing, but that action must not be presented as proof that the server credential
has been revoked. Define any Application-owned link-withdrawal operation narrowly
in B2 rather than giving the actor general credential-management authority.

**B3 Discovery and continuity decisions.** First release may use an explicitly
named exported Personal agent, validated by the server at admission, without
submitting a test turn. A picker requires authorised delegated discovery through
a separately specified public capability. It must not list definitions using an
operator's authority or invent names from a service catalogue.

Current [Personal acting work](../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/PersonalActingWork.java)
binds continuation, receipts and jobs to the same actor, subject, Application,
grant and proof token. Reopening a socket context with the same proof preserves
that binding. Replacing an acting token changes its proof identity. First release
starts a new binding/conversation after replacement and explains this to the user;
it preserves old evidence without claiming that old jobs can be recovered using
the new proof. Seamless proof replacement is a separate platform lifecycle
decision, not adapter-side conversation adoption. Application service-token
rotation retains its principal and is a different operation.

### Proposed linking sequence

1. An authenticated Discord DM sender requests linking. The Application creates a
   bounded pending transaction tied to its retained identity, grant, integration
   instance and intended external binding. No model work starts.
2. The bot supplies an internal URL or approval code. The browser must be able to
   reach Plowshare and the integration through LAN/VPN; a public callback is not
   required. Links/codes contain no acting credential.
3. The human authenticates directly with Plowshare. Derive the subject from that
   session and check current eligibility. Possessing a forwarded URL, knowing a
   Discord ID, or typing a Plowshare handle cannot establish the complete link.
4. Display Application identity, external account, Personal access, actions,
   selected agent, expiry and reply surface. The human approves within their
   own authority and the live grant/eligibility limits. Consent is specific to
   this binding; managers cannot approve it as the user. For the first release,
   show a fresh confirmation code only in this authenticated Plowshare flow after
   approval. Require that code from the original authenticated Discord DM sender
   before redemption; do not send it to Discord automatically. The code is
   short-lived, single-use and is not an acting credential.
5. Only the authenticated Application redeems the approved transaction. Mint the
   credential at redemption, commit its hash and metadata receipt atomically,
   and return the secret once over the private SDK connection. Persist it in the
   integration's private credential store before marking the local link ready.
6. The bot confirms success without printing credentials. Pending, denied,
   cancelled, expired, delivery-uncertain and revoked links remain distinguishable
   from active links. Closing a browser or restarting the adapter does not approve
   a request or authorise another issuance.

Approvals and redemptions need stable request identities, expected transaction
versions, bounded lifetimes, rate limits and exact actor/subject/external-binding
checks. First-time linking includes the identity confirmation step; a returning
user with a still-verified binding may have a shorter consent flow. Receipt
reconciliation never replays an uncertain issuance. If redemption committed but
its one-time secret was lost, expose the metadata and require
explicit revoke/replacement; do not make old secrets retrievable to hide the loss.

## Track C External SDK integration

### C1 Shared management capabilities and durable configuration

Put typed management operations behind narrow interfaces consumed by CLI and web
handlers. Reuse the existing setup/gateway/store responsibilities; do not make web
routes invoke CLI processes or create another durable agent runtime.

Separate installation identity and immutable admission coordinates from editable
operational configuration. Add explicit configuration revisions and expected-version
writes, plus a validated upgrade/import path for existing private journals. The
current frozen setup hash must not be bypassed or reset when a route is edited.
Preserve original intents, uncertain deliveries, subscription identities and
conversation bindings. Bot/server/actor replacement requires a deliberate new
installation or verified migration; it cannot adopt another identity's state.

CLI and web mutations share one process/store owner. Define stopped versus live
configuration changes and an explicit drain/reload point. The existing
`setup.lock` held for the child's lifetime needs deliberate coordination before
concurrent management is supported. Never steal locks, run two owners or delete
private receipts to make an edit work.

### C2 DM transport and service chat

Add Discord DM events and the library's required DM channel handling. Decode
guild/thread and direct-message destinations as distinct typed variants. Validate
the direct channel and recipient on delivery; do not fabricate a guild ID or
reuse a guild route for a DM. Update persisted-state decoding and migration along
with input, routing, output, status/cancel and tests. First release supports
one-to-one DMs; group DMs do not acquire Personal routing implicitly.

An explicit DM policy selects onboarding-only or configured service chat for
unlinked users. Service chat still requires an allowed caller and an explicit
Application project/exported bot mapping. This can ship independently of A/B.
The initial controls may be text commands; registering a slash-command catalogue,
voice and attachments are separate features. Existing guild routes and Relay
notifications continue to use service authority.

### C3 Optional internal web app

Serve a small bundled web frontend with the adapter's management backend. Provide
explicit bind/advertised origins and private deployment guidance; no public tunnel
or internet callback is a requirement. Serve static assets locally. Do not add a
second account database or infer authentication from LAN/VPN access.

Manager pages cover connection status, routes and allowed callers, supported
Application source updates, credential metadata/rotation, and recovery holds.
User pages cover their own pending/active links, selected agent, granted scope,
expiry and revocation. Browser-visible metadata excludes secrets and unrelated
Personal content. Every request requires the B2 identity/authorisation contract,
and server-administrator actions retain that requirement even for an Application
manager. Form visibility is not authorisation.

Use explicit trusted origins, authenticated session expiry, CSRF protection for
cookie-backed mutations, and versioned writes. Client errors/logs must not contain
credentials, proof material or complete private payloads. The browser may present
an approval handle; the acting secret is redeemed only by the Application backend.
Headless operators use the same capabilities through CLI/configuration.

The basic setup manifest needs an explicit, typed update to support approved
acting declarations. Activation must preserve the owned service identity, human
grants and current live membership. Manager permission in the UI does not make
source deployment possible under a service token: use the public human/admin
workflow where deployment requires it.

### C4 Linked Personal DM conversations

Look up links only by verified Discord sender and integration identity. Use the
stored server-confirmed subject/proof binding; never derive a subject from a
Discord display name, message text, channel name or browser-supplied handle.
Linked requests use `acting.turn.start` and `acting.turn.receipt`; history and
status/cancel use the explicitly selected context and the public supported
operations. Service requests keep `incoming.*` and their existing task/context IDs.

Retain the request UUID before each submission. Bind each record to its mode,
Discord bot/sender/direct channel, Application actor, server-confirmed link
generation, subject, proof and concrete agent. Persist conversation IDs and job
receipts; keep secrets in separate private credential artifacts. Acting context
handles are ephemeral connection state: reopen with the same proof after expiry
or reconnect, confirm the returned subject, then reconcile retained requests.
Agent or proof changes select a new binding rather than silently retargeting work.
Bound context caches by connection and proof, close unused contexts, and select
authority on each SDK request; never keep a mutable connection-wide current user.

Link withdrawal, invalid proof, insufficient permission, disabled user or an
unsupported agent stops delegated admission/disclosure. It never retries as the
Application, another user or SYSTEM. Unlinking/credential loss does not cancel
server work implicitly or erase its evidence. Cancellation is explicit and uses
the original live authority where still permitted. Selecting service mode later
is an explicit new user action and starts a separate service conversation.

Personal output stays in the bound DM. It never enters existing guild replies,
shared notification routes or the manager dashboard. A DM is a Discord delivery
surface, not a transfer of message confidentiality to Plowshare: Discord carries
its messages even though the server and web UI remain private.

Preserve the adapter's output intent/receipt journal and no-replay behavior.
Revoked access holds unsent Personal output; a Discord send already in flight
cannot be undone by a later revocation. A missing send receipt remains uncertain,
and later recovery must not post the same answer again automatically.

## Delivery sequence and independent changes

| Change | Dependencies | Reviewable result |
| --- | --- | --- |
| A1 Runtime authority and skills repair | Existing runtime handoff | DIRECT/worker skills work under retained authority; matrix identifies remaining runtime slices |
| B1 Public consent lifecycle | Platform design decision and action/target specification | SDK/CLI can issue, inspect, revoke and reconcile acting credentials under direct-human authority |
| C1 Management and configuration lifecycle | Existing setup contracts | Typed CLI capabilities, revision checks, state migration and one store owner; no dependency on internal APIs |
| C2 Service DM support | C1 persistence/configuration changes | Allowlisted service DMs and onboarding policy work while guild behavior is preserved |
| B2 Generic linking and web management identity | B1 and reviewed workflow/authentication contracts | Two identities are verified; consent and one-time private credential handoff can complete through the SDK |
| C3 Internal web management | C1 and B2 | Managers and users see only their authorised controls and metadata; CLI remains available |
| C4 Linked Personal DMs | A1, B2, C1/C2 and supported agent capabilities | Link, select an exported Personal agent, DM, recover, cancel and revoke through public contracts |
| A2 and later runtime consumers | Individual lifecycle specifications | Each formerly blocked capability is delivered and verified separately; no blanket parity claim |

Tracks A and B can progress independently of C1/C2. UI work can use explicit
contract fixtures while B2 is in development, but it is not an authenticated
working release until the real public contracts pass consumer tests. Keep core,
shared SDK/CLI contract publication and integration adoption in distinct PRs.
Slack can consume the generic B contracts later; no Slack adapter is included here.

## Verification and release acceptance

Use mocks and in-memory repositories by default. Real PostgreSQL is justified only
for changed persistence constraints, atomic consent/redemption/receipt behavior,
locking, rollback or row mapping. State that reason and run the smallest relevant
`-PfullDb --tests` selection; do not enable it for UI, adapter or permission-policy
checks. After implementation, run the required format/default checks and packed
public SDK consumer tests. Planning alone does not need a runtime test run.

Acceptance must cover these independent failure boundaries:

- Two Plowshare users and an unlinked Discord caller cannot share contexts,
  credentials, Personal output, management authority or another user's job control.
  Forwarded pairing links and simultaneous confirmations cannot swap subjects.
- A manager configures only the authorised Application; user consent cannot
  enlarge eligibility. Ordinary users cannot install/rotate Application tokens,
  read other links or invoke configuration endpoints directly.
- Browser refresh, duplicate approval/redemption, restart, version conflicts and
  failed private writes preserve the original request. Lost one-time credentials
  require explicit repair, without making metadata endpoints secret retrieval APIs.
- Socket expiry/reconnect renews only ephemeral contexts, then reads original
  receipts; unknown turns/cancels/sends are never resubmitted. Application token
  rotation, acting-token replacement and relinking have distinct tested outcomes.
- Role/ACL/grant changes and revocation while queued, in a tool/skill/child or
  during output disclosure stop protected actions without acquiring wider authority.
- Existing private setup artifacts, guild/thread conversations, service DMs,
  notifications, mention suppression, rate-limit handling and exclusive ownership
  survive configuration/state upgrades and headless operation.

Finish with live acceptance against an explicitly configured private server and
Discord bot: browser access over LAN/VPN, linking by two real test users, a service
user, DM conversation reuse, restart/receipt recovery, cancellation and revocation.
Do not open a public listener for the test. Use an explicit access topology and
record actual SDK/server compatibility; mocked tests do not prove live delivery.

Each release reports the exact commits/contracts adopted, passed checks, supported
agent capabilities and unresolved gaps. A working service DM is not a linked-user
release; a working link is not proof that every delegated capability now works.
