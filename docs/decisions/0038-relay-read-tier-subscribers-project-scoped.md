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
- **Delivering a publication to a subscriber still requires that the subscriber may read the
  topic**, evaluated at delivery, not only at subscription. A subscription is not a lease on
  authority; access revoked between subscribing and delivering must stop delivery. This mirrors the
  rule the information read path already follows — "check every operation again; an earlier context
  is not a membership lease" in `InformationAccess.requireSelection`.
- **Work and manage tiers are unchanged.** Publishing, ports, receiver dispatch, topic
  registration and configuration keep their current checks. Nothing in this record widens who may
  publish or administer a topic.

### Consequences accepted

- **More accounts can hold subscriptions.** A VIEWER may subscribe and consume where previously
  only contributors could. This is the intent: it is the audience that may already read the
  content and already inspect the log.
- **Lease contention is unchanged.** Leases remain exclusive per `(scope, topic, subscriber)`;
  admitting read-tier subscribers adds subscribers, not contention within one subscription.
- **Delivery-time authority checks cost a read.** Re-checking at delivery rather than trusting the
  subscription is a per-delivery authorization resolution, which this codebase has repeatedly found
  to be the expensive pattern. It must resolve the topic's audience once per delivery batch, not
  once per publication per account — the mistake that made the information-event fan-out cost
  3,996 ms for a single event. The implementation is expected to hoist it the way
  `knowledge_readable_scopes` (V184) hoisted the document read boundary.

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

## Open question for the authorizing decision

Whether the delivery-time authority re-check is required, or whether subscription-time authority is
sufficient with revocation handled by removing subscriptions. The former is safer and matches the
information read path's existing rule; the latter is cheaper and matches how Relay treats
subscriptions today. This record proposes the former and flags that it is the costlier choice.
