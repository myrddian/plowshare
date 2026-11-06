# Parent-owned namespaces for contained Applications

Design, 7 November 2026. Authorized by the user in conversation on the same
date, as the requirement that reframes the fourth slice of
[nested Applications](2026-10-25-nested-applications-design.md). That design
remains the binding authority for containment itself; this document adds the
facility a parent uses to control what its children can read and write, and
records the decisions the user made about it.

Containment as built is recorded in
[ADR 0042](../../decisions/0042-nested-application-containment.md). Three slices
have shipped behind it: the containment primitive, the knowledge read fences,
and execution. This design is what the fourth slice is for, and it supersedes
the fourth slice's previously named shape — "wire the contained principal as the
information actor" — by supplying the reason that wiring exists. The authority
decision is unchanged and still required; what changes is that it is now in
service of a facility rather than an end in itself.

## Intent

**A parent must be able to alter the memory and knowledge a child has access
to.** That is the requirement in one sentence, and everything below follows from
it.

The motivating use is a parent Application that manages nested Applications as a
workflow: it creates children, gives each a different view of the world, lets
them run, reads what they produced, keeps what worked and discards what did not.
The platform supplies the facility. It does not run the experiment.

### Why now

Slice three made a contained child execute. A running child today reads and
writes no knowledge or memory at all — not because a fence refuses it, but
because nothing makes the child the information actor. The gates are open and
unreachable. Wiring them without deciding what a child's world *is* would
produce a child that reads whatever its container reads, which is the opposite
of the requirement: the parent could not narrow it, and could not steer it.

So the namespace is not an addition on top of the information actor. It is the
thing that makes wiring the actor meaningful.

## Prior art: Harness², and where this departs

Google Research's Harness² (arXiv 2610.14224, `google-research/harness2`,
Apache 2.0, October 2026) is the system the user is drawing the analogy from. It
improves an agent's harness — system prompt, guardrails, memory, skills, tool
guidance, scripts, subagents, middleware — by proposing candidate edits, probing
them on the same task, and composing the result. Read directly from the paper
and the released code, the mechanics are:

- **Everything is text files on disk.** "Each component is mounted as a file in
  the runtime." Evidence reaches a session as files in a working directory
  rather than inlined in a prompt.
- **Isolation is by full copy.** `Proposer.call` does
  `shutil.copytree(context_dir, tmp / "context")` into a fresh
  `tempfile.mkdtemp`, runs the agent with `cwd=tmp`, and each candidate is
  materialized separately by `write_tree(..., work_dir / "harnesses" / label)`.
  Candidates probe concurrently in a `ThreadPoolExecutor` under a
  `BoundedSemaphore`.
- **The change report is a tree comparison.** `tree.py`'s
  `diff_summary(surface, before, after)` returns `added`, `removed`, `modified`
  and a character delta, computed in memory from two whole-tree dicts. A
  `harness.diff` file is written for pool evidence. No version control is used.
- **Collection precedes destruction.** `_harvest` copies the edited harness out
  of the temp copy, and only then does `finally: shutil.rmtree(tmp)` run.
- **Nothing is pruned.** Only the per-attempt proposer temp copies are deleted.
  Candidate and probe directories accumulate under `work_dir` indefinitely.
- **Confinement is convention, not enforcement.** `normalize_rel` raises on
  absolute paths and `..`; `is_allowed` bounds the editable surface. That is a
  path whitelist, not a sandbox. `docs/EXPERIMENTS.md` states that the release
  does not restrict network access and that Codex runs with its sandbox
  disabled.

Three things carry over unchanged, and they are the user's own instincts
confirmed by the code: **a modified/added/removed change report**, **isolation
per experiment so several can run at once**, and **the parent collecting what it
wants before the space is destroyed**.

Three things deliberately depart:

1. **Copy-on-write instead of full copy.** Harness² can copy a whole working
   directory because the editable surface is eight small text files. Plowshare's
   equivalent spans the source area, memory, the information system and logs,
   including documents with revision histories and binary blobs. Copying that
   per child is not affordable, and the cost would scale with what a child *can
   see* rather than what it changes.
2. **Materialization as the change record, instead of a tree comparison.** Once
   reads resolve through references and writes materialize copies, the set of
   materialized entries *is* the change set. The report becomes a read of the
   space's own structure rather than a content comparison — which is both
   cheaper and the only approach that works for a binary blob or a document
   whose content the platform cannot interpret.
3. **Deliberate destruction instead of accumulation.** Harness² never prunes.
   Here the parent destroys a space explicitly, which is the same decision made
   on purpose rather than by omission.

And one thing is different in kind rather than in degree. Harness² is a harness
over a CLI coding agent — OpenCode and Codex are its executors — so its
confinement can be a path whitelist, because the thing being confined is a
process with a working directory. Plowshare is a persistent, server-first agent
platform: a child is an Application with a project, a principal, authority
records, an allowance, conversations, memory, an information corpus and Relay
endpoints. Confinement here is server-enforced authority, which is what makes a
namespace that spans the information system possible at all — and what makes it
a platform decision rather than a convention.

## Goals

1. A parent can give a child a namespace, and can read and write into that
   namespace from outside while the child runs.
2. The namespace bounds everything the child reaches: its source files, its
   memory, the information system and its logs. One assignment covers all four
   doors.
3. A contained child remains indistinguishable from an ordinary Application
   from its own point of view.
4. Several children can run concurrently against separate namespaces, with no
   interference and no visibility into one another.
5. The platform tells the parent what changed in a namespace, distinguishing
   modified, created and removed.
6. The parent can promote a child's work into its own space, or discard it, and
   both are ordinary operations under the parent's own authority.
7. The parent controls when a child accepts work and when a namespace is
   destroyed, as separate decisions.
8. The facility is uniform at depth: a child uses it on its own children exactly
   as its parent used it.

## Non-goals

- **No evaluation, scoring, ranking, comparison or selection.** The platform
  reports what changed; the parent decides what it means. This is the same line
  ADR 0042 already draws, and it keeps that record's prohibition intact: no
  contract may name harnesses, probes, candidates or improvement.
- **No experiment orchestration.** Running eleven children and picking a winner
  is the parent's program.
- **No reconciliation or merge.** When a parent promotes a child's modified
  document, the platform does not merge it with the parent's original. The
  parent decides what to write and writes it.
- **No new child-facing API.** Nothing is added for the child to call, because
  the child is not aware it is contained.
- **No shared allowance counter.** Unchanged from ADR 0042; a child allocates
  its own allowance under a parent-set ceiling.
- **Not a filesystem.** The namespace is shaped like a directory from the
  child's point of view because that is what the child already expects, but it
  is a bounded view over Plowshare's existing stores, not a new storage layer.

## Anti-goals

These would make the work a failure even if it functioned.

- **A child that must cooperate.** If the child has to ask for its namespace,
  announce itself, or avoid reaching outside, confinement is a convention and
  the facility is worthless for running untrusted generated configuration. The
  child discovers its limits the way any Application does: things resolve or
  they do not.
- **A namespace enforced by a default rather than a structure.** ADR 0042 names
  this explicitly, and slice two's outward fence exists because a `CONTAINED`
  home's publication was being refused only by the value a policy column
  happened to default to. A namespace that holds because nobody writes the row
  that would break it is not a namespace.
- **Confinement as a check at each call site.** If every read has to remember to
  ask, one forgotten site is a hole. Confinement is a property of the principal
  and the resolution path.
- **A parent write that the child's own writes can corrupt, or vice versa.** The
  parent's originals must be untouchable by the child by construction, not by
  the child behaving.
- **Silent loss of a child's output.** A parent that destroys a space loses its
  evidence, and that is the parent's bug; a platform that discards a child's
  output on its own is a defect.

## Constraints

From the repository's standing rules and from what is already built:

- Repositories own persistence. Nothing outside a repository or store touches
  `JdbcTemplate`. Namespace resolution lives behind a typed interface.
- Typed DTOs at boundaries. No raw JSON or `Map<String, Object>` crossing into
  internal contracts.
- Schema changes go in new Flyway migrations; shipped migrations are never
  edited. Three renumberings have already happened on this branch, and the
  record asks that a long-lived branch's migration numbering be treated as
  colliding by default.
- Database tests sit behind `@Tag("full-db")` and `-PfullDb`. The gates this
  work touches cannot be proved any other way, so the database-specific reason
  is explicit and the test selection stays narrow.
- No hard-coded deployment values anywhere in the facility.
- Containment resolves by walking `contained_scope_ancestors` **upward from the
  descendant**. Confinement is the direction of the walk, not a filter. A
  namespace reference chain resolves the same direction, through the same
  ancestry.
- A contained principal holds no credential material — no row in `admins`,
  `authority_accounts` or `service_tokens`. Eleven surfaces in ADR 0042's
  inventory are closed by that fact alone. Nothing here may issue a contained
  principal a credential or a handle.
- **Containment depth is already lifted.** An earlier draft of this document said
  the effective depth limit is 1; that was true before slice three and is false
  now. `IdentityRepository.contained(ScopeId)` answers "contained, or nothing",
  the depth walk follows that read, and depth 2 is exercised by a hop that
  succeeds. The walks are bounded by the configured `maxDepth` and refuse beyond
  it, which is the intended fence rather than a limitation. So uniform recursion
  does not need the depth limit lifted; what it needs is a driver at each hop,
  which is a separate matter recorded below.

## The design

### One space, symmetric, shaped like a directory

A parent assigns a child exactly one namespace. The child reads from it and
writes to it. There is no separate read-set and write-destination: one place,
the way an ordinary Application reads and writes what is on disk.

This keeps the space as the unit of everything. Discarding an experiment drops
one space; promoting takes from one space; the change report reads one space.
There is no second structure to keep in step and no question of a read-set and a
write-space disagreeing.

It also lines up with what exists. A contained child already has exactly one
home — its own contained project and scope — and slice three's run resolves
definitions, tools and resource references through that one home. The namespace
is that home given a parent-side write door and copy-on-write reads, not a new
addressing concept beside it.

*Decided by the user: one space, read and write, matching what is on disk.*

### The child's side is unchanged

A contained child boots as an ordinary Application: it looks at its file
structure, reads what its permissions allow, and asks memory and the information
system the questions any Application asks. There is no containment-aware API, no
handshake, no namespace to fetch first.

Consequences the builder must preserve:

- The child cannot be *told* its limits, so nothing may depend on it having been
  told. It discovers them by what resolves.
- A child that cached its world at boot would be wrong, because the parent may
  change the space underneath it.
- Every bound is enforced server-side on the resolution path, never by the
  child's cooperation.

*Decided by the user, as a hard requirement.*

### Live alteration, bounded by tools

The parent may alter the child's namespace while the child is running. Reads
resolve live rather than against a snapshot taken at run start. Whether to
exploit that is the application designer's business; the platform takes no
position.

This is **parity with ordinary Applications, not a new divergence.** An
Application running in Plowshare today can have its information space altered
entirely at runtime, and is still bounded by its tools. A contained child
getting the same treatment is the requirement that the child is just an
Application, applied here.

What it rules out: copying the namespace into the run's memory at start, and any
cache keyed only on the run. What it does not rule out is coherence within a
single operation.

*Author's call, open to correction: the guarantee is per-operation consistency,
not per-run. A child must not see half of a parent's multi-document write;
nothing promises that two separate reads agree.*

### Copy-on-write with lazy references

A child's namespace starts as references into its parent's material. A child
read resolves through the reference to the parent's original. A child **write**
materializes a copy in the child's space and replaces the reference. The
parent's original is untouched and no sibling sees the change.

So the cost of a child is proportional to what it changes, not to what it can
see — which is what makes many concurrent children affordable, and is the
departure from Harness²' full copy.

**Why copy rather than revision history.** Plowshare has revisions, so "just
revert" looks available. It is not sufficient:

- The revision chain is per-document and ordered. Two experiments touching one
  document would serialize into a single history, so running several at once is
  not a convenience being traded away — it is impossible.
- Revert undoes the latest entry. A discarded experiment that touched eleven
  documents needs eleven reverts in the right order, and any interleaving with a
  live parent write corrupts both.
- Discarding a space is dropping a space, not unwinding a history.

*Decided by the user: copy, with lazy references as the optimization, so a child
write produces a new copy rather than touching the parent's original.*

### The change report

The platform tells the parent what changed in a namespace, in three states:

- **`M` modified** — an entry that was a reference to the parent's material and
  has been materialized and changed.
- **`+` created** — an entry that exists only in the child's space and
  corresponds to nothing of the parent's.
- **`-` removed** — an entry the child has removed from its own view, where the
  parent's original still exists.

The three are separate because each is a different promotion decision: a
modified entry means reconciling against an original the parent still holds, a
created one has nothing to reconcile, and a removed one is the child asserting
that the parent's material should not be there. A flat listing would push the
parent into re-deriving all of that.

Copy-on-write supplies this without a diff: the space knows which entries are
still references, which are materialized, which exist only here, and which are
tombstoned. The state lives on the entry rather than in the content, so the same
report covers files, documents, memories and binaries alike — including bytes
the platform cannot interpret.

*Decided by the user: git-like `M`, `+`, `-`. The user also asked that Harness²
be consulted; it computes the same three categories via `diff_summary` over
whole in-memory trees, which Plowshare cannot do, hence the structural
derivation above.*

### Promotion and discard

**Promotion is the parent's own write.** When a parent takes a child's version,
it writes into parent-owned knowledge under the parent's own authority, through
the ordinary write path it already has. It is not a privileged containment
operation, which is what keeps it from becoming a hole.

**Discard is dropping the space.** The parent's originals are untouched by
construction.

The platform offers no merge, no reconciliation and no assistance in choosing.

### Two lifecycles

**The child's liveness and the namespace's existence are separate, and both are
the parent's to decide.**

The child is live while the parent lets it live. This matters because Plowshare
has several doors in: a prompt saying "do task X" that completes, Relay messages
arriving unprompted, delegation, schedules. A run-shaped child only covers the
first; a child sitting on a Relay topic has no natural end. So the boundary
cannot be "the work finished" — it is "the parent said stop."

The sequence:

1. **Create.** The parent creates the child and its namespace.
2. **Ready.** The parent tells Plowshare the child is ready. Ingress opens.
3. **Stop.** The parent stops the child. It is *disabled*: no new work can be
   queued. The namespace stays readable.
4. **Kill**, optionally, and only after a stop. See below.
5. **Destroy.** Separately and later, the parent destroys the namespace.

The namespace outlives the child. A space reclaimed when the run ended would
destroy the evidence before the parent read it. The child finishing does not
stop the parent from reading and analysing the space until it is done.

**This is a shift from what slice three built.** Slice three's execution is
run-shaped: `application.child.run` starts a run, it finishes, and three things
can stop it — `application.child.cancel`, a destruction that stops the subtree,
and a mid-run revocation of the container's grant. All three are run-stopping
rather than Application-disabling, and all three must be re-read against this
lifecycle.

*Decided by the user, including that liveness and namespace destruction are
separate things.*

**Stop cannot be built the way destroy is, and that is the sharpest consequence
of separating the two lifecycles.** ADR 0042 has exactly one `destroy`, and
*every* refusal for a destroyed child works by the project-row cascade:
`projects` → `authority_scopes` → `contained_applications`. `run`, `trajectory`,
`resources` and `cancel` all refuse only because `records.find` comes back
empty. `contained_applications.live` is vestigial and is never written `false`.

If the namespace outlives the child, the child's project row and scope must
survive stop and kill. So stop cannot use the cascade, and `live` — or whatever
replaces it — has to become real written state, with each of those four
operations re-decided against it. Building stop on the cascade would produce a
stop indistinguishable from today's destroy, which is the specific failure to
avoid.

What step 5 destroys is close to today's `destroy`: the cascade is what
reclaims the space. Whether it is that operation renamed, or a new one with the
collection record and sweep behind it, is the builder's to decide.

### Stop semantics: a clean halt

**STOP closes the doors; it does not drain the queue.** Work already queued
stays and is worked through. What stops is *new* work arriving:

- Relay stops delivering to the child.
- Messaging and delegation reject incoming.
- Schedules are disabled.
- Sending a prompt is disabled.

Every door that could add to the queue closes; the queue itself is honoured.

**The rule is clean, not dirty.** A child midway through an orchestration gets to
complete that orchestration — not merely the current model call. So the unit
allowed to finish is the unit of work the child committed to, which may span
several steps and several turns. Stopping therefore cannot be a flag the turn
loop checks.

**KILL is an escalation available only after a stop**, so there is no way to
hard-stop a child whose doors are still open. Even kill respects the turn
boundary: work already past the scheduler and running on a model completes that
turn or step before anything stops.

The existing machinery already works at that grain, which is corroboration
rather than coincidence: slice three's mid-run revocation is checked at the
operation's `CallAuthorization`, asked after each completion returns and before
output is published, so a revoked container's ceiling absorbs one more model
call rather than zero. The turn boundary is where control returns, so it is
where stopping can happen honestly.

*Decided by the user: stop lets in-flight and queued work finish, closes every
ingress, and kill is a post-stop escalation that cuts at the turn or step
boundary.*

*Author's call, open to correction: kill is where the queue is dropped — that is
the useful difference between the two verbs. The user said queued work stays on
a stop and did not say what kill does to it.*

*Author's call: stop is reversible, so a parent can re-enable a stopped child
rather than building a new one. Not discussed.*

Two of those doors are worth noting against what exists. Slice three refuses a
child declaring a schedule outright, so "schedules are disabled" is a rule
waiting for a capability rather than a change to one; nobody should read that
refusal as the permanent answer. And delegation *within* the child's own subtree
already works, so stop must reject inbound delegation without breaking a
delegation the child itself started and is waiting on.

### Uniform recursion

A child creating a child of its own carves the grandchild's namespace out of the
namespace it was itself given. Same mechanism at every level — the namespace is
"a namespace like no other", meaning it is not other.

Copy-on-write references therefore chain. A grandchild read that nothing has
materialized resolves through the child's reference to the parent's original. A
write at any level materializes at that level and leaves everything above
untouched, so a grandchild's experiment is invisible to the child, which is
invisible to the parent, and discarding any one of them drops exactly one space.

This resolves in the same direction as the containment walk already built —
upward from the descendant, through `contained_scope_ancestors`.

**Depth itself is not the obstacle.** Slice three lifted the effective limit of
1, and the configured `maxDepth` bound is the intended fence. What uniform
recursion needs is a *driver* at each hop — see "The driver" below.

*Decided by the user: the grandchild's namespace comes from the namespace the
child was given, by the same mechanism.*

*A parent can **read** its grandchild's space, matching the existing read model
where an ancestor reads its descendants. It cannot write into or destroy one —
see "Write reach: container only", which the user decided after this paragraph
was first written.*

### What the parent collects

The platform cannot enumerate what a child produces. It may be a file, a report,
a binary, a directory of files, documents in the information system, memories,
or logs. So the parent must be able to walk the whole space and read everything
in it, including bytes the platform cannot interpret. Plowshare's binary blob
storage — extended on `main` by V224–V228 — is where a child's binary output
already has somewhere to live.

Collection before destruction is the parent's obligation. The platform makes it
possible; it does not enforce it. A parent that destroys a space before reading
it loses its evidence, and that is the parent's bug.

### The driver

**An Application's agent gets tools for the `application.child.*` operations.**
A parent drives its children from inside its own turns, and a child drives its
own children the same way — which is what makes the parent a workflow rather
than the subject of one, and what makes goal 8 buildable.

**This removes a brake ADR 0042 put there on purpose, and that is the point of
authorizing it separately.** Those eight operations exist today only as
WebSocket frames (`FrameTypes:82`–`124`); no agent tool names any of them, and
the only mentions inside `server/agents/` are a Javadoc line in `Runs` and a
refusal message in `Turn`. They sit behind `Asking.requireHandle`, and a
contained principal is a bare UUID with no credential material (V218), so ADR
0042 records that "a running child has no door of its own to
`application.child.run`" and that every containment hop needs an authenticated
driver. That was the brake against the recursion anti-goal.

What does **not** change: V218's no-credential property. A child must still hold
no row in `admins`, `authority_accounts` or `service_tokens`, because eleven
surfaces in ADR 0042's inventory are closed by that fact alone. The tool reaches
the operation through the run's own authority, not by issuing the child a
handle. Spelling that difference correctly is the heart of this work — a tool
that works by handing a child a credential would open all eleven.

Also unchanged: no contract may name harnesses, probes, candidates or
improvement. The tools are for managing nested Applications, and the recursive
workflow remains the user's program rather than the platform's vocabulary.

*Decided by the user: add in-model child tools.*

### Reading what a child produced

**A parent may read a child's content, under a named discipline by default, with
escalation available by separate authorization.**

Two levels:

1. **Default — named discipline.** A parent reads a child's bytes through an
   explicit operation, for a named entry, and the result never implicitly enters
   a model prompt. Asking is the parent's declared act, which preserves the
   reasoning behind ADR 0042's metadata-only decision: a child's writes are
   untrusted input and a prompt-injection surface by construction, so
   `application.child.resources` answers `(path, kind, revision, bytes)` and
   carries no file content.
2. **Escalation on request.** When the parent needs more than the discipline
   allows, it requests an escalation, and **a separate authority grants it — another
   agent or a human.** The parent cannot escalate itself.

*Decided by the user, who asked for both of the options offered rather than
either: start from the named discipline, and let the parent or operator escalate
through an authorization step.*

The builder decides what the escalation grants and how it is scoped and
recorded, but not whether it is self-service: it is not. Plowshare already has
approval machinery with its own authority and lifecycle contracts, and ADR 0042
requires that approvals stay explicit, so the escalation should reuse that
rather than inventing a second approval path.

### Logs

Logs are the fourth door, and they are **created-only**. A child's conversation
log has no original of the parent's to be modified or removed from, so `M` and
`-` cannot arise for it. The parent reads and collects logs; it does not write
into the child's log.

So "one assignment covers all four doors" means one bound, not four symmetric
behaviours: source, memory and the information system are read-write with all
three change states, and logs are append-by-the-child, read-by-the-parent.

*Author's call. The user named logs as one of the doors and did not say how they
behave.*

### What a namespace initially references

A namespace references **what the parent seeds into it**, not everything the
parent can see. The parent assigns the namespace and writes into it; a child's
read dereferences an entry the parent put there.

The alternative — a namespace that overlays everything the parent can reach —
would reverse the fences slices two and three exist to establish, where a
contained actor reaches its own subtree and nothing else. That would be a
different and much larger authority decision than anything discussed.

*Author's call, from the user's framing that the parent "assigns the child the
namespace" and "writes into the child's space". The user did not address it
directly. If the intent was an overlay, this document is wrong about the size of
the work.*

### The file door: staged source becomes mutable

**The namespace includes a genuinely writable file area.** This is the closest
analogue to Harness²' text-files model, and it is the larger of the two options
considered.

It reverses or re-opens five things, and each needs deciding as part of this
work rather than discovered during it:

1. **The `writableAreas` refusal.** The creation request refuses a non-empty
   `writableAreas` today, on the reasoning that any requested area names a store
   outside the container's fence. A child's writable area must instead be
   allocated inside its own containment.
2. **`WorkspaceApplicationPolicy:53`**, which answers `INVALID` for a contained
   project — recorded as "a child's own file configuration remains
   unexpressible".
3. **ADR 0005's staged immutable revisions**, including the per-file and total
   package budgets and the UTF-8-text-only constraint. A mutable area inside an
   immutable staging model needs the boundary between them stated explicitly.
4. **Revision identity and the source-directory caches**, which are keyed on
   source-directory identity and will not see writes that bypass staging.
5. **The refusal of a parent revision change while any child is live**, which
   exists to prevent orphans and interacts directly with a parent writing into a
   live child's area.

*Decided by the user: make staged source mutable.*

The builder should treat this as the part of the work most likely to need its
own slice, and should say so rather than folding it into the namespace
representation.

### Write reach: container only

**Only a child's immediate container writes into or destroys its space.** A
grandparent still *reads* through the chain, exactly as it does today.

This follows the shape already in the codebase for operations that carry
consequence: `trajectory` and `resources` walk the whole chain upward, while
`run` and `ceiling` are container-only for accounting reasons. Writing into a
space, and destroying one, belong with the latter — the authority to intervene
stays with the thing that created the child.

**This overrides an author's call made earlier in this document**, which
guessed that a parent could reach its grandchild's space on the strength of the
existing ancestor-read model. For reads that guess stands; for writes and
destruction it does not.

V229's two write arms (`information_lock_destination` and
`information_home_allows`) are keyed on a live `contained_principals` row and
that row's own scope, so nothing admits an ancestor's write at all today. A new
arm is needed, and its action set is a builder decision with one constraint
carried over: V229 admitted `WRITE` and deliberately not `SET_AUDIENCE`, and a
parent writing into a child's space must not become the route by which a
contained home gets published outward.

*Decided by the user: container only.*

### Two findings the builder must not inherit as solved

**Memory needs its own gate, and ADR 0042 over-claims here.** V229 replaces
`knowledge_revision_readable`, `knowledge_read_authority`,
`knowledge_readable_candidates`, `information_lock_destination` and
`information_home_allows`. It does **not** touch
`information_global_read_member`. `MemoryStore`'s own SQL requires
`authority_accounts.legacy_handle = ?` **and**
`information_global_read_member(?)` on the read path, and additionally an
`admins` row with `server_admin` on the write path. A contained principal has no
`authority_accounts` row and no `admins` row, so memory is closed for it by two
predicates V229 never reached. ADR 0042's statement that a contained principal
"reads its own documents and its own memories" is correct for documents and
over-claimed for memories; the memory door is its own V229-shaped arm with its
own authority argument, not a consequence of wiring the actor.

**The binary blob claim path and the collection sweep are unverified.** V225
keys `binary_blobs` and `binary_blob_claims` on a TEXT scope with a state and
`unclaimed_at` collection index, deliberately so that byte storage survives
project deletion. Whether a contained principal has an admitted claim path, and
whether an unclaimed child blob can be swept while the namespace is still meant
to be readable, is not established. A sweep taking a child's output before the
parent collects it would be the "silent loss" anti-goal caused by the platform
rather than the parent, so this must be proved rather than assumed.

## How this reframes the fourth slice

The previously named prerequisite — making the contained principal the
information actor, with the container as the accounting account — is unchanged
and still the blocking decision. What changes is its justification. It is no
longer "the gates are open and should be reachable"; it is "a child reads and
writes its own namespace, so the identity the information system resolves must
be the child's."

The pieces already built that this design consumes:

- `InformationContext.acting()` and `.attribution()`, the two axes.
- `InformationAccess.contained(principal, container, project)`, the only place
  the two axes are built differing, with `includeGlobal` deliberately false.
- V229's read baseline and both write arms, keyed on
  `contained_knowledge_actor`.
- V230's lineage relaxation and `Origin.CONTAINED`.
- The twelve `requireUncontainedActor` guards in
  `JdbcInformationCatalogueRepository`.

Work this design makes unavoidable rather than optional:

- **The five unguarded sibling repositories** —
  `JdbcInformationNavigationRepository`, `JdbcInformationProjectionRepository`,
  `JdbcAcquisitionRepository`, `JdbcInformationContentRepository`,
  `JdbcInformationInterchangeRepository`. Each takes an `InformationContext`,
  each writes, none carries a guard, and whether their service layer would
  refuse a contained actor is unverified. The only reason that is survivable
  today is that nothing presents a contained actor — the exact fact this work
  removes.
- **The tool-argument to `Resource`-reference mapping**, unpinned for three
  slices.
- **`information_navigation_reference_visible`**, which carries the same
  unextended `RESTRICTED` fence and is the *only* admission arm at
  `JdbcInformationNavigationRepository.referenceVisible`, so the conjunct cannot
  simply be added there without refusing an ancestor's own reference visibility
  into its descendant.
- **The `CONTAINED` retention**, which currently keeps conversation rows nothing
  can read, for ever. This design makes the question sharper, because the parent
  explicitly wants to read a finished child's material: whatever retention
  serves that should be decided here rather than inherited.

## What is left to the builder

The following are deliberately not decided, because a competent builder in this
codebase is better placed to decide them against the code than this document is
against a conversation.

- **How a namespace is represented.** Whether references live in a new table
  keyed by (space, entry), whether tombstones are rows or a column, and how an
  entry's identity is spelled across four different stores.
- **How a namespace is addressed.** ADR 0042 rejected encoding containment in a
  readable project-name path because it exhausts ADR 0021 capacity at depth; the
  same constraint applies here.
- **Where reference resolution sits** — the resolution path for each of the four
  doors, and which existing predicate or repository each one widens. V229 and
  V230 are the model for how a gate is opened and proved.
- **Whether any new migration is needed at all.** V229 and V230 already carry
  the gates the actor wiring needs. New SQL is likely for the reference and
  tombstone representation, and possible for the navigation fence. If the
  representation can be expressed without it, that is worth knowing.
- **Slicing.** This is more than one slice's work. The builder should propose a
  cut, and should assume the migration-numbering collision is the normal case.
- **The per-operation consistency guarantee's implementation**, including what
  happens to a child read that races a parent write to the same entry.
- **Whether stop is a state on the child, a withdrawal of grants, or both**, and
  how a stop that must let an orchestration finish is expressed without a flag
  the turn loop polls.

## Open decisions for the user

These change what gets built and only the user can settle them. They are not
blockers for planning the parts above.

1. **Is the information-actor divergence information-only, or a general
   facility any subsystem may use?** Information-only is narrower and
   recommended; it means the next subsystem that wants it must ask again.
2. **The `CONTAINED` retention**: drop it and give `contained` `SUBMISSION`'s
   ejection age, or make `trajectory` answer after destruction from the
   surviving collection record. The second reopens the deferred existence-oracle
   contract question.
3. **The existence oracle** in `SourceContainedApplications.trajectory` and
   `resources`. Closing it changes which fault a nonexistent child raises, so it
   is a contract change.
4. **The inherited global agent tier**: whether a global callee's `tools:` list
   must be subset-tested for a contained child. Parity rather than escalation;
   previously deferred by the user as "document, decide separately."

## Sources

- [Harness²: Recursive Agent Harnessing for an Open World (arXiv 2610.14224)](https://arxiv.org/html/2610.14224v1)
- [google-research/harness2](https://github.com/google-research/harness2)
- [harness2 docs/EXPERIMENTS.md](https://github.com/google-research/harness2/blob/main/docs/EXPERIMENTS.md)
- [Google Open-Sources Harness²  (KDnuggets)](https://www.kdnuggets.com/google-open-sources-harness-self-improving-ai-agents-without-retraining)
