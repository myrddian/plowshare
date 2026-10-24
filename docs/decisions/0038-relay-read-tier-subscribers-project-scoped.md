# Relay read-tier subscribers, project-scoped

Status: proposed. Requires explicit authorization before any code changes, because it changes
Relay's consumption authority.

Scope: project-scoped topics only. Audiences for content whose home scope has no project are
separated into `0039-relay-audiences-for-scopeless-content.md`, because that half needs a new Relay
scope and this half does not.

## Need and boundary

`0037-events-and-messaging-on-relay.md` moves information events onto Relay. That migration is
blocked: Relay cannot currently deliver to the audience information events have.

Information read authority admits more accounts than Relay consumption does.
`EffectiveProjectPermissions` grants READ to VIEWER, CONTRIBUTOR and MANAGER alike, and the
existing recipient fan-out admits any project member who passes `information_readable`, plus any
`information_global_read_member` for GLOBAL_READ-audience content — accounts that need no project
role at all. Relay gates consumption at CONTRIBUTOR.

### This is not an oversight, and the record should say so

Relay already has three authority tiers, and a read tier is one of them:

| tier | check | admits | used for |
|---|---|---|---|
| read | `members.mayUse(project, account)` — any role present | VIEWER and above | log inspection, `ScopedRelayInspection` |
| work | `members.mayWork(project, account)` — CONTRIBUTOR or above | contributor and above | leased consumption (`ProjectRelayProcessing`), ports, receiver dispatch |
| manage | `members.mayManage(project, account)` — MANAGER | manager | topic registration and configuration |

So a read-tier account can already *inspect* a topic's log. What it cannot do is *subscribe*, and
the reason is sound: consuming takes a lease, and a lease is a write —
`JdbcRelayConsumerRepository` issues `INSERT INTO relay_consumer_leases … ON CONFLICT … DO UPDATE`
and `UPDATE relay_consumer_leases SET lease_until=…`. Durable at-least-once delivery requires
per-subscriber position state, and persisting that state is by definition a mutation. Gating a
mutation on write access is the conservative, defensible choice, and whoever wrote it was not
cutting a corner.

The defect is the **coupling**, not the tier. A subscriber's lease and offset are bookkeeping about
*that subscriber*, not content in the project. They are keyed
`(scope_key, topic, subscriber)` and carry an `account`; nothing in a lease row describes project
data. Authorizing that write with "may write project content" conflates two unrelated authorities,
and the consequence is that Relay can only deliver to accounts that could modify the project —
which is the wrong audience for any topic that carries readable content, not just information
events.

### The second gap is separated, not solved here

`mayUse` is project-scoped. Content homed in GLOBAL_KNOWLEDGE has no project, and
`Relay.SystemScope.SERVER` is not a user-facing audience: its topics are internal server wake
signals (`message.wake.requested`, `board.wake.requested` in `RelayInternalWorkers`) consumed by
native workers, and `ScopedRelayInspection` gates system scope on `isServerAdmin`. So global-read
accounts have no expressible Relay audience.

That needs a new Relay scope, which is a larger change with a different risk profile, so it is
recorded separately in `0039-relay-audiences-for-scopeless-content.md`. Separating it is safe and
unblocks the work now: under the V185 alias, PROJECT, PERSONAL and ACCOUNT_KNOWLEDGE home scopes
are all project-scoped, and on the live installation those accounted for **every** information
resource — 2,813 PROJECT, 50 ACCOUNT_KNOWLEDGE, 29 PERSONAL, and no GLOBAL_KNOWLEDGE rows at all
(measured 24 October 2026, before a host reset; indicative rather than a guarantee). This record
therefore unblocks `0037` for all information content that exists, and `0039` covers a case that is
currently empty but reachable.

## Decision

Separate **topic read authority** from **consumer-state authority**.

- **Subscribing and consuming require read authority on the topic**, not write authority on the
  project: the same `mayUse`-equivalent check that already governs log inspection.
- **Writing a consumer's own lease and offset is authorized by subscriber identity**, not by
  project write access. The authority to advance a position is: the caller may read the topic, and
  the lease row being written is the caller's own. Per-subscriber bookkeeping is the subscriber's
  state.
- **Authority is established when the subscription is established, and a subscription is valid
  until invalidated.** Delivery does not re-resolve authority per publication. A subscription
  carries its validity; it ends when something invalidates it, and at the latest when a
  configurable maximum age elapses — whichever comes first. See "Subscription validity" below.
- **Work and manage tiers are unchanged.** Publishing, ports, receiver dispatch, topic
  registration and configuration keep their current checks. Nothing in this record widens who may
  publish or administer a topic.

### Consequences accepted

- **More accounts can hold subscriptions.** A VIEWER may subscribe and consume where previously
  only contributors could. This is the intent: it is the audience that may already read the
  content and already inspect the log.
- **Lease contention is unchanged.** Leases remain exclusive per `(scope, topic, subscriber)`;
  admitting read-tier subscribers adds subscribers, not contention within one subscription.
- **Revocation is bounded, not immediate.** An account whose read access is withdrawn can keep
  receiving from an established subscription until that subscription is invalidated, and in the
  worst case until the maximum age elapses. This is the deliberate trade for not resolving
  authority per publication — the pattern that made the information-event fan-out cost 3,996 ms for
  a single event. The window is configurable precisely so the exposure is a deployment choice
  rather than a property of the design.

## Alternatives rejected

- **Leave consumption at `mayWork` and accept the narrower audience.** Information events would
  reach contributors only; viewers and global-read readers would lose delivery they have today.
  That is a silent reduction in what users see, traded for avoiding a Relay change. Rejected: the
  audience is not the migration's business to change.
- **Give read-tier subscribers a client-tracked cursor with no durable position.** No lease, no
  write, no authority question. Rejected: it abandons at-least-once delivery for exactly the
  accounts that only read, so a disconnected viewer silently loses events — the same class of
  defect as the `sequence > ?` cursor that 0037 exists to remove.
- **Resolve the read audience on the information side and hand Relay a recipient list.** Preserves
  Relay's current authority model untouched. Rejected: it rebuilds the per-account fan-out that
  0037 removes, and re-creates the special-case delivery subsystem that record exists to delete.
- **Reuse `SystemScope.SERVER` for global-read content.** Rejected outright: that scope carries
  internal server wake topics gated on `isServerAdmin`, and admitting accounts to it would expose
  them. The alternative that replaces it belongs to `0039`.

## Subscription validity

A subscription is valid from the moment its authority was checked until it is invalidated.
Invalidation has two triggers, and the second is a floor under the first:

- **Explicit invalidation**, which is the normal path. Anything that changes who may read the topic
  invalidates subscriptions against it — a membership or role change, a scope's read authority
  changing, the topic being reset. Relay already has the mechanism: `relay_subscriptions` carries a
  `generation` alongside `seen_through`/`seen_at`, and a subscription whose generation no longer
  matches the topic's is not valid. Revocation should bump that generation rather than wait.
- **A configurable maximum age**, which is the fallback. However subscriptions are invalidated,
  none is trusted beyond a configured window; past it, authority is re-established or the
  subscription lapses. `relay_consumer_leases` already expresses a bounded window this way with
  `epoch` and `lease_until`.

The practical effect is bounded staleness with an explicit bound. Authority resolution happens once
per subscription rather than once per delivery, so delivery stays cheap, and the exposure from a
missed invalidation is capped by configuration instead of being unbounded.

Two things the implementation must not do:

- **Do not treat the window as the primary mechanism.** If revocation relies on expiry rather than
  invalidation, the configured window becomes the actual revocation latency for every change. The
  window exists to bound the failure of invalidation, not to replace it.
- **Do not re-resolve the audience per publication.** Whatever revalidates a subscription resolves
  the topic's audience once, for that subscription — not once per publication per account.
  `knowledge_readable_scopes` (V184) is the precedent for hoisting that resolution.
