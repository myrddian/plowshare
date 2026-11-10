# Self-similar nesting: what it would take

**Out of scope.** Nothing here is authorized or planned. This is the note the
containment work owes its successor, recorded while the evidence was in hand.

**The restriction, as ADR 0042 states it.** `application.child.run` exists only as
a frame behind `Asking.requireHandle`, and a contained principal is a bare UUID
with no credential material (V218), so "a child cannot start a grandchild on its
own initiative… transitive containment needs such a driver per hop." The ADR
records this as **both a confinement property and a capability limitation** and
says the two are "the same fact".

## What is and is not limited

**Not limited: depth or breadth of the structure.** `contained_scope_ancestors`
recurses to 64 in SQL, and `ContainmentBounds(maxDepth, maxLiveDescendants)` is
mandatory — `ContainmentConfig` refuses to start without both positive, with the
words "containment is not unbounded by omission". The hierarchy can be arbitrarily
deep and wide within those bounds, and depth 2 is already exercised.

**Limited: a child's ability to extend the hierarchy.** Every hop needs an
authenticated ancestor driving it. The agent is not self-similar: a child does the
work of a parent in every respect except making another child.

**Not the blocker: authority.** This is the trap to avoid. The obstacle is the
absence of a **door**, not of a role. A frame needs a session; session creation
allowlists `account_kind='USER'` (`JdbcDurableSessions:64`). So any scheme that
gives a child *authority* — a grant, a role, a proxy identity — leaves this exactly
where it is. Do not approach nesting by way of the authority substrate.

## What would be needed

1. **Expose the hop as a retained tool, not a frame.** A contained run reaches work
   through its tool set, authorized server-side against its admission ceiling —
   `PinnedOperationAuthority`, `ApplicationGrantLineage`, the retained ceiling. That
   is how every other thing a child does already works, and it needs no credential,
   no session and no reversal of V218. The hop becomes one more tool whose arguments
   the admission confines.
2. **Decide the ceiling rule for a grandchild.** The existing rule is that a child's
   authority is a strict subset of its container's and narrows across each hop. A
   child-driven hop must compute the grandchild's ceiling from the child's own
   retained admission rather than from the container's, or the narrowing is lost at
   the second level.
3. **Re-examine ADR 0042's argument, which this note disputes in one respect.** The
   ADR justifies leaving the door closed by the spec's anti-goal — fearing "unbounded
   recursion or width … reachable from ordinary agent work" — and says "requiring an
   authenticated driver per hop is exactly that brake." But `ContainmentBounds` is
   also exactly that brake: mandatory, fail-closed, and covering recursion *and*
   width precisely, where the authenticated driver covers them only incidentally. The
   authenticated driver is the **second** brake, not the only one. That does not make
   opening the door correct — it means the decision should be re-argued against the
   bounds that exist, rather than inherited.
4. **Extend `RetainedToolAccess.resources`.** ADR 0042's own inventory gap: that
   mapping turns a tool's arguments into the `Resource` references
   `requireReadTargets` confines, and it maps **two** tools. A hop tool that named no
   resource would be confined by nothing. Adding the tool without adding its mapping
   is the specific way this goes wrong.
5. **A width budget per actor, not only per installation.**
   `maxLiveDescendants` bounds the tree. A child-driven hop also wants a bound on
   what one *run* may create, or a single child can consume the whole installation
   budget.

## What must not be used to get there

- A credential, handle or session for a contained principal. V218 forbids it, ADR
  0042 rests eleven-odd surface closures on it, and it is not what blocks nesting
  anyway.
- A grant against a `CONTAINED` scope. V216 excludes contained scopes from issuance
  and from live-grant coverage deliberately; see
  [the work graph](2026-11-08-contained-proxy-principal-work-graph.md), findings
  F1, F5 and F16.
- Widening `ScopedPermissions` or `authority_project_role` for the child. That is
  the ceiling question, which is a different axis (F20).
