# ADR 0056: The containment path stays beside the authority contract

Status: proposed, 11 November 2026.

Slice four of [nested Application containment](0042-nested-application-containment.md)
left one question open in writing — whether the Java layers above the SQL arms
should admit a contained principal, and by what means — and then answered half
of it in code, by building a second path rather than widening the first. This
record states that answer in decision form, gives it the rule that governs the
next door, and names what it deliberately does not decide. It adds nothing to
[the nested Applications design](../superpowers/specs/2026-10-25-nested-applications-design.md),
which remains the binding authority. Becoming *accepted* needs the user's
authorization, because the alternative it rejects is an authority change.

## Decision

`ScopedPermissions` is not taught to answer for a contained principal, now or
later. A door that must admit a confined child asks a separate, narrow
containment question and places it as a **disjunct beside** the question it
already asked, never in place of it, so a principal the ordinary question
already refused is the only one that can reach the new arm.

Two shapes implement that, and the difference is the question each door needs
rather than a split in the rule:

- **Home ownership, at a service gate.** `ContainedHomes.ownsContainedHome`
  asks whether this principal owns this project as its own contained home.
  Three doors take it: `InformationAccess`'s `requireSelection` and
  `requireWork` through `ownsSelectedHome` (`InformationAccess.java:471`),
  `BoundMemoryQueries.requireScope` (`BoundMemoryQueries.java:143`) and
  `BoundMemoryWrites.requireRecipient` (`BoundMemoryWrites.java:247`).
- **Liveness, in the repository.** `JdbcInformationNavigationRepository`'s
  `requireActive` asks the `admins` existence test first and unchanged, then
  `contained_knowledge_actor` — the same predicate V229's and V231's arms
  select on, so liveness cannot drift from admission. For a contained
  principal existence *is* liveness: V218 gives `contained_principals` no
  revocation column, and the row dies with its scope through `ON DELETE
  CASCADE`.

A new door picks the question its own gate turns on. It does not invent a
third source, and it does not reach for the role contract.

## Why not an arm on the authority contract

The role family returns nothing for a contained project by construction, and
in both directions. V216 made `CONTAINED` an authority-free scope kind: no
grant may target one and none is live against one, so `authority_project_role`
resolves no role on a `contained:` project for the child **and for its
container**. V218 gives a contained principal no `authority_accounts` row, so
the role family's `legacy_handle` join can never match it either. Both facts
are load-bearing elsewhere in 0042's inventories; an arm on
`ScopedPermissions` would have to contradict at least one of them to be
useful, and every fence that currently reads "the role question answers no for
a contained project" would have to be re-audited against a contract that no
longer does.

The decisive argument is not symmetry but what the container would gain.
`information_project_readable`'s ancestor walk (V223) answers TRUE for a
container over its descendant's project. That is correct for knowledge, where
an ancestor reads its subtree by design. Were the general role question opened
on a contained project, the container's own handle would select the
`authority_accounts` arm and read the **child's memories inside the child's
home** — a reach the design never granted and that no door is currently
written to refuse, because today it is unreachable one layer up. A containment
question cannot cause that: it answers only whether *this* principal owns
*this* project as its own home, so a container asking it about a descendant
gets false.

Placing the containment question as a disjunct, rather than folding it into
the role source, is what keeps every ordinary decision bit-for-bit unchanged.
A true answer short-circuits deliberately: asking the role family about a
contained project as well would leave it resolving one, which is exactly what
V216 was written to prevent.

## What a door must satisfy to take this path

- **Ask the acting axis.** `InformationActor`/`InformationContext` carry the
  child as `acting()` and the container as `attribution()`. Authority
  questions take `acting()`; `attribution()` is provenance and answering an
  authority question with it hands a confined child its container's authority.
- **Keep the question off the ordinary path.** Call `ownsContainedHome`, never
  `ownsHome`: the `ContainedProjects.name` prefix test on the contract returns
  false without a query for every project name that is not `contained:`, and
  every one of these gates is reached by every ordinary request. The first
  draft of the information gate put a round trip there for an answer it could
  not change.
- **Fail closed when unwired.** `ContainedHomes.NONE` refuses every contained
  principal, so an incomplete composition loses the capability rather than
  opening one, and an unavailable answer propagates rather than reading as
  ownership.
- **Admit a selection, not an action.** A true answer means "this project is
  this principal's own contained home" and nothing more — not an ancestor's
  home, not a sibling's, not a descendant's. The door still decides which
  action a child may take there, and the SQL arm beneath it still decides
  independently.
- **Lock what exists, ask about the child.** A contained principal has no row
  to lock. `lockContainedIdentity` therefore takes the share lock on the
  *container's* `admins` row, through `attribution()`, and asks liveness of
  the child through `acting()`. A door needing a lock follows that split
  rather than inventing a row for the child or skipping the lock.
- **Carry the characterization.** A door that takes this path pins both
  directions: the admission, and the refusal beside it that a dropped negation
  would invert.

## What stays refused

`SET_AUDIENCE` is never admitted by this path. V229 widened the knowledge
write gates for a contained actor and deliberately not the audience one, and
`InformationAccess.requireAudience` does not consult `ContainedHomes`: it
admits the selection through `requireSelection` and then asks the role
question, which answers no. A child does not change who can see a thing.

The navigation surfaces that take a bare handle — `DocumentNavigation.mutate`,
`PortableInformation.commit` — stay outside the path. Their `account` columns
on saved views, receipts and audit are keys rather than labels, so attributing
them to the container would merge a child's request identifiers into its
container's idempotency namespace. Whose namespace a contained run's
idempotency lives in is open, and this record does not settle it.

Nesting remains out of scope for the reason 0042 records.

## Consequences

The authority contract and its SQL remain untouched, so no existing fence that
reasons from "the role question answers no for a contained project" needs
re-auditing — the property it depends on is still true. The cost is that every
door wanting this capability states it again, which is why the rule above
exists and why the prefix guard lives on the contract rather than in each
caller.

One asymmetry is left standing knowingly, and it is about where a recheck
lives rather than whether one happens. Neither path locks an identity row for
a contained principal, because there is none to lock: both take the shared
`information-navigation-control` advisory lock and then
`information_lock_scope` on the scope itself. The knowledge path re-evaluates
`contained_knowledge_actor` **inside** `information_lock_destination` after
that lock, on a fresh snapshot, so a principal revoked meanwhile gets false
from the function. The memory path's equivalent recheck is in Java:
`MemoryStore.lockRead` and the write path's pre-commit `require` re-pin, which
re-evaluates `contained_memory_actor_allows`. So a revocation landing
mid-transaction is caught either way and this is not a hole — but a door
taking this path must not assume the SQL rechecks for it. It is recorded here
because it is the first thing to examine if this decision is ever reversed:
the ceiling this record preserves is the only reason a production caller does
not reach `MemoryStore` directly today.
