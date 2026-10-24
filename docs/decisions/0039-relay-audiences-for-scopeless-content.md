# Relay audiences for scope-less content

Status: deferred. Separated from `0038-relay-read-tier-subscribers-project-scoped.md` so the
project-scoped half can proceed. Needs its own authorization when it becomes necessary.

## Need and boundary

Relay scopes are `Relay.ProjectScope(projectId)` or `Relay.SystemScope.SERVER`
(`RelayScopeCodec`). Content whose authority scope has no project therefore has no Relay audience:

- `ScopedRelayInspection` gates project scope on `members.mayUse(project, account)` and system scope
  on `members.isServerAdmin(account)`.
- `SystemScope.SERVER` is not a user-facing audience. Its topics are internal server wake signals —
  `message.wake.requested`, `board.wake.requested` in `RelayInternalWorkers` — consumed by native
  workers through `JdbcRelayNativeRepository`.

So an audience defined by something other than project membership cannot be expressed. The concrete
case is information content homed in GLOBAL_KNOWLEDGE, whose readers are
`information_global_read_member` accounts and who need no project role.

### Why this is deferred rather than solved

Under the V185 alias every other information home scope is project-scoped — PROJECT and PERSONAL
directly, ACCOUNT_KNOWLEDGE through the owner's personal project. On the live installation that
covered every information resource: 2,813 PROJECT, 50 ACCOUNT_KNOWLEDGE, 29 PERSONAL, and no
GLOBAL_KNOWLEDGE rows at all (measured 24 October 2026, before a host reset, so indicative rather
than a guarantee).

The case is therefore reachable but currently empty, while `0038` unblocks all content that exists.
Introducing a new Relay scope kind is a change to the broker's core identity model — scope keys are
persisted (`RelayScopeCodec.read` rejects anything it does not recognise), so a new kind touches
stored data, every authority check and the SDK surface. That is disproportionate to an empty case,
and doing it under time pressure alongside `0037` is how a scope model acquires a shape nobody
wanted.

## What a solution must satisfy

Recorded now so the constraints are not rediscovered later:

- **A new scope kind, not a reused one.** `SystemScope.SERVER` must stay the internal worker scope.
  Admitting accounts to it would expose server wake topics to them.
- **The audience is a predicate, not a membership table.** Global-read readership is
  `information_global_read_member`, which is derived from authority grants rather than from a
  members row. Relay's authority checks are currently membership lookups, so this is the part that
  does not fit today.
- **Persisted scope keys must round-trip.** `RelayScopeCodec.write`/`read` is the stored encoding
  and rejects unknown keys; a new kind needs a migration-safe encoding and must not make existing
  `project:` and `system` keys ambiguous.
- **It must not become an information-specific hook.** If the only way to express the audience is to
  let the information subsystem supply a recipient predicate, that re-creates the special-case
  delivery path `0037` exists to delete. A general scope whose audience is an authority predicate is
  the shape to aim for; an `information`-shaped callback is not.
- **Delivery-time authority must stay hoistable.** Whatever evaluates the audience must resolve once
  per delivery batch, not once per publication per account. The information-event fan-out cost
  3,996 ms for a single event precisely because it asked a permission predicate per candidate
  account.

## Until then

Information events homed in GLOBAL_KNOWLEDGE have no Relay audience. The implementation of `0037`
must therefore **fail loudly** on that home scope rather than silently dropping the event or
falling back to a topic with the wrong audience. A refused publication is a visible defect; a
publication delivered to the wrong audience, or to none, is not.
