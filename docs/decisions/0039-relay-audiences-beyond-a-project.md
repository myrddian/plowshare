# Relay audiences beyond a project

Status: deferred, and **reframed**. An earlier version of this record stated the problem as "Relay
needs a new scope kind". That framing was wrong and is corrected below, because acting on it would
have made the mismatch permanent.

## The problem, stated correctly

Relay's scope space is a **two-valued enum** — `Relay.ProjectScope(projectId)` or
`Relay.SystemScope.SERVER`, encoded as `project:<id>` or `system` by `RelayScopeCodec`. The
authority substrate's scope space is a **table**, `authority_scopes`, with kinds PROJECT, PERSONAL,
ACCOUNT_KNOWLEDGE, GLOBAL_KNOWLEDGE, ADMINISTRATION and RESTRICTED.

So an audience that the substrate can name, Relay cannot. GLOBAL_KNOWLEDGE is the live instance:
content homed there has no project, and `SystemScope.SERVER` is the internal worker scope —
`RelayInternalWorkers` publishes `message.wake.requested` and `board.wake.requested` on it, and
`ScopedRelayInspection` gates it on `isServerAdmin`.

The right question is therefore: **does Relay key topics on `authority_scopes.id`** rather than on
its own enum? Not: does Relay gain a sixth kind.

## Two framings this record previously got wrong

**"Relay needs a new scope kind."** Adding one would bolt a substrate concept onto Relay's enum and
make the two scope vocabularies permanently parallel — one more kind every time the substrate grows.
The mismatch is the enum, not its contents.

**"Relay is on a superseded permission model."** Also wrong, and worth recording so nobody acts on
it. Relay authorizes through `ProjectMembers` role lookups, and `JdbcProjectMembers.role()` resolves
them with `authority_project_role(?,?)` — the substrate function added by `V156`, which was applied
additively so that existing wire `ProjectRole` names kept working. Relay's answers are therefore
**substrate-backed already**; no case was found where Relay's verdict differs from the substrate's.

The gap is **vocabulary, not staleness**: Relay cannot *name* a non-project audience, even though
the authority behind its checks could evaluate one. This is a change of expression, not a latent
authorization defect.

## Why it stays deferred

Under the V185 alias every other information home scope is project-scoped — PROJECT and PERSONAL
directly, ACCOUNT_KNOWLEDGE through the owner's personal project. On the live installation that
covered every information resource: 2,813 PROJECT, 50 ACCOUNT_KNOWLEDGE, 29 PERSONAL, and no
GLOBAL_KNOWLEDGE rows at all (measured 24 October 2026, before a host reset — indicative, not a
guarantee). So decision 0037's implementation is unblocked for all content that exists, and this
case is reachable but currently empty.

Keying topics on `authority_scopes.id` is also a change to Relay's core identity model: scope keys
are persisted and `RelayScopeCodec.read` rejects anything it does not recognise, so it touches
stored data, every authority check and the SDK surface. That is disproportionate to an empty case,
and doing it under time pressure is how a scope model acquires a shape nobody wanted.

## What a solution must satisfy

- **Do not reuse `SystemScope.SERVER`.** It carries internal worker topics gated on
  `isServerAdmin`; admitting accounts would expose them. `V126` already took this shortcut for
  messaging lifecycle — publishing with a NULL project, which resolves to the system scope and so
  to server administrators only. That is the workaround this record rejects, and it is already in
  the tree.
- **Persisted scope keys must round-trip.** `RelayScopeCodec.write`/`read` is the stored encoding;
  a new keying must be migration-safe and must not make existing `project:` and `system` keys
  ambiguous.
- **The audience may be a predicate, not a membership row.** Global-read readership is
  `information_global_read_member`, derived from authority grants. Relay's checks are currently
  membership lookups, and this is the part that does not fit today.
- **It must not become a consumer-specific hook.** If the only way to express an audience is to let
  a consuming subsystem supply a predicate, that re-creates the special-case delivery path 0037
  exists to delete. A general scope whose audience is an authority predicate is the shape to aim
  for; an `information`-shaped callback is not.
- **Delivery-time authority must stay hoistable.** Whatever evaluates an audience resolves once per
  delivery batch, not once per publication per account. The information-event fan-out cost 3,996 ms
  for a single event precisely because it asked a permission predicate per candidate account.
- **Sequence it against extensibility.** Per `0040`, Relay cannot currently be extended from
  outside — every family pays a core change. Keying topics on substrate scopes and making Relay
  extensible are related problems, and solving the audience alone leaves the larger one standing.

## Until then

Information events homed in GLOBAL_KNOWLEDGE have no Relay audience, and 0037's implementation
**fails loudly** on that home scope rather than guessing a topic or silently dropping the event. A
refused publication is a visible defect; one delivered to the wrong audience, or to none, is not.
