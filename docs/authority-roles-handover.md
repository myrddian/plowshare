# Authority and roles: what is done, what is not

Status snapshot, 22 October 2026, re-verified 23 October against merged `main`
at `0785a3f0`. Every claim below was checked against the code, not recalled.
Verify again before acting — this file ages.

The 32 commits between `8467c821` and `0785a3f0` are request-dispatch capacity,
connection pooling, backpressure and two new build guards. They touch neither
`server/information/` nor `server/access/`, so nothing here moved: the
seven-condition SYSTEM fence still refuses a tool, `promotion_judge.md:15` still
declares `tools: [memory_read]`, both `Home`↔`AuthorityScope` bridges are still
in place, and the server still carries zero `@Deprecated`.

**Three of eight tasks are done.** The two user-visible defects that were
fixed are fixed and deployed. The remaining five tasks are all one theme:
the SYSTEM ceiling, and making "no scope" unsayable.

The full sequence and its reasoning live in
[the admission plan](authority-scope-admission-plan.md). This file is the
status layer on top of it, plus the things that plan does not cover.

## The invariants being conformed to

None of this is new design. Every rule below was already written down, and
the implementation could bypass each one with nothing failing.

| invariant | where |
|---|---|
| "Omission never selects server scope" | `global-scope-migration-map.md:65` |
| Null scope representation replaced by explicit scope UUIDs | `global-scope-migration-map.md:64` |
| A SYSTEM service is admitted with the caller's trigger, SYSTEM's authority **and the worker's operation sandbox** | ADR 0030 |
| Global knowledge: everyone reads, only ADMIN promotes, ADMIN is delegated from SYSTEM | ADR 0030; `global-knowledge-read-contract.md` |
| `scopes` cannot be widened at run time | `AgentDefinition` Javadoc :24, :55 |
| One resolution and one input for Personal | user ruling, 22 Oct 2026 |

`BuiltInIdentities` holds the registry: `SYSTEM` …001, `ADMINISTRATION` …003,
`GLOBAL` …004, `GLOBAL_KNOWLEDGE` …005 (a ScopeId), `GLOBAL_READ` …006 (a
RoleId). The two routes to "everyone can read this" are separate identities,
by design.

## Task status

| | task | state |
|---|---|---|
| 1 | Admission takes `AuthorityScope`, not `Home` | **done** — `bf538538`, deployed |
| 2 | Pin the scope-less callers | **done** — 6 pins, 36 tests |
| 3 | Resolve Personal in the information path | **done** — PR #104, deployed, live-verified |
| 4 | Split the SYSTEM ceiling | **not started** |
| 5 | Decide the Curator | **not started** |
| 6 | Pass the sandbox at every SYSTEM admission site | **not started** |
| 7 | Deprecate then delete the scope-less overloads | **not started** |
| 8 | A wrapper naming the kind of SYSTEM work | **not started** |

Plus one task opened by the live testing of task 3, not in the original plan:
**resolve `kind:personal` to its one address** (see "The newest item" below).

## What tasks 1–3 actually changed

`admit` and `requireOwn` take `AuthorityScope` rather than `Home`. `Home`
reverts to the memory **retrieval partition** it was designed as and is
otherwise untouched — it remains a wire type on `Memory`, referenced by
~200 server files, so it was deliberately not given a Personal tier.

`InformationAccess.executionHome(context)` resolves PROJECT to
`Home.of(project)`, PERSONAL to `Home.of(PersonalSpaces.name(account))`, and
ACCOUNT/GLOBAL_READ to `Home.global()` unchanged. Applied at the two sites
that admit as the account: `InformationWriteGates` and
`InformationAcquisitions`.

**Verified live** on the deployed server, 22 Oct: a personal upload is
accepted on a first attempt and the row lands in the account's own home; a
`global_read` write is refused ("Global READ is a read selection; select a
writable home"); a project write without membership is refused ("This
account does not have the required project access"). Ten real documents
then loaded successfully at personal scope.

## Task 4 — the SYSTEM ceiling is one check too strict

`SystemModelTasks.requireModelOnly` refuses on seven conditions. They are
not the same kind of rule:

| condition | role | keep for SYSTEM? |
|---|---|---|
| `calls`, `orchestrations`, `reviewWith` | delegation | **yes** |
| `scopes`, `skills` | run-time capability acquisition, including provider and hosted tools | **yes** |
| `board` | outward side effects | **yes** |
| `tools` | the static declaration all 35 shipped agents already carry | **no** |

Six say *you may not grow*. The seventh says *you may not have* — a
different and stricter policy, right for the model-only workers the class is
named for and wrong for the judge.

Dropping the seventh does **not** open the dynamic-tool escape: provider
tools arrive through `scopes`, which stays refused, and hosted scopes
additionally need a live registration, a fresh catalogue and a current
credential grant (`hosted-tool-scopes.md`).

**This loosens an authorization fence, so pin all six remaining checks
refusing individually before touching it.** The argument "six of seven are
the real ones" currently rests on reading, not on tests.

## Task 5 — the Curator has never run

`Curator:468` hands work to `JobRuntime.forSystemWork`, but
`BoundSystemOperations.admit` first calls `SystemModelTasks.requireModelOnly`,
which refuses any definition carrying a tool — and `promotion_judge.md:15`
declares `tools: [memory_read]` deliberately ("the tool is the whole reason a
ruling is a job rather than a second scribe").

A production-wired pass throws `IllegalStateException` on its **first
candidate** and `Curator.pass` does not catch it: no ruling, no proposal, no
envelope.

**Why no test caught it:** every existing `CuratorTest` uses the
five-argument constructor, which leaves `system` null and skips admission.
The suite has never exercised the production path.

Either the judge loses its tool, or model-only is the wrong ceiling for
curation. Task 4 makes the second option available. Which is right is a
design decision, not an implementation one. Its scope is already correct
(the curated project's own; `Home.of` refuses null/blank).

## Task 6 — severity per SYSTEM caller

"Contract-bounded" means `BoundSystemOperations.claimed(...)` refuses unless
`tools()` is empty and the model matches, with the prompt pinned by hash.

| caller | scope on global home | bounded? | severity |
|---|---|---|---|
| `Compaction:4466` | `GLOBAL_KNOWLEDGE` | yes | wrong ceiling, **unusable** |
| `Scribe:198` | `GLOBAL_KNOWLEDGE` | yes | wrong ceiling, unusable — but **a global home is directly reachable from a person's request** (`MemoryController.write` → `RequestedHome.in(null)`) |
| `Learner:224` | `GLOBAL_KNOWLEDGE` | yes | wrong ceiling, unusable; reachable only via `Origin.MEMORY` global-home rows |
| `DigestModel:194` | `GLOBAL_KNOWLEDGE` | yes, but **prompt not pinned** — the binding is `String.valueOf(trace.conversationId())`, so one admission authorizes many differently-worded calls | wrong ceiling, unusable, weaker fence |
| `Deliberation:1002` | `GLOBAL_KNOWLEDGE` | — | confirmed misroute |
| `Curator:468` | n/a, never admitted | n/a | **broken**; needs latitude the ceiling forbids |

A wrong ceiling that is unusable only because a second, unrelated mechanism
holds is still worth fixing. **It is not an incident.** Nobody has
established that these stages actually *write* through the wrong ceiling;
wrong-ceiling and wrong-data are different claims.

Supply `selected` at each site, per ADR 0030. Then the global fallback in
`BoundSystemOperations` is unreachable from these callers. This becomes
load-bearing once task 4 lands, because a tool-bearing worker's scope
decides what its tool can reach.

## Task 7 — the compiler as the to-do list

The build sets `-Xlint:all -Werror` ("a warning nobody fails on is a warning
nobody reads", `build.gradle.kts:137`) and the server has **zero**
`@Deprecated` and **zero** deprecation suppressions. So a deprecation here is
a build break, not a warning: the compiler produces the to-do list and
refuses to proceed until it is cleared.

Deprecate only once tasks 3–6 leave no callers, then delete, and omission
becomes unsayable.

Two bridges were added in task 1 and **must not become permanent**:
`IdentityRepository.home(AuthorityScope)` and `OperationAuthority.scope(Home)`.
They exist because `Admission` retains a legacy project-name column. Both go
when `Home` leaves `Admission`.

Consider a policy script asserting zero deprecation suppressions in
`access/`, following `check-native-sdk-policy.py`.

## Task 8 — name the kind of SYSTEM work

The real distinction is not "SYSTEM or not" but **model-only** versus
**tool-bearing-but-declared**, with different requirements on each. Shape
depends on what tasks 4–6 settle. Design it against the pins, not against
recollection.

## The newest item — one resolution for Personal

Found by live-testing task 3. A personal home resolves to **two mutually
invisible partitions** depending on how a client spells it. Both of these
were accepted; neither list saw the other's row:

- `{"kind":"personal"}` → account namespace
- `{"kind":"project","project":"personal:<hex-of-handle>"}` → project namespace

Not a regression from task 3 — the project path always did this — but it was
unobservable while personal writes were refused, because only one partition
was reachable.

**Mechanism.** `InformationAccess.forRun` normalizes the address, folding a
personal-home project name back into `Scope.PERSONAL`. The ordinary request
path does not: `InformationAccess.projectSelection(account, project)` ignores
its `account` argument and returns `Selection.project(project)` verbatim.

**Direction, ruled by the user:** the typed selection resolves to the
address, not the reverse — "`kind:personal` allows the resolution enough",
whereas the reverse "has to check if a personal prefix is attached and do
word matching". So `kind:personal` is sugar that resolves forward, once, at
the boundary, and nothing inspects a project string's content.

Supporting evidence for that direction:

- `JdbcIdentityRepository.project(String name)` looks up `projects.name` and
  builds `AuthorityScope.Project` or `.Personal` from the row's `kind`
  column. Its comment states the principle: a personal space's name is an
  ordinary `projects.name`, and nothing reconstructs it. **Personal-ness is a
  stored property resolved by lookup.**
- `forRun`'s `PersonalSpaces.name(account).equals(project)` derives a name
  and compares — exactly what that comment warns against. If a personal
  space's `projects.name` ever diverges from the derived value, the equality
  silently fails and the home is treated as an ordinary project.
- `PersonalSpaces.requireOwn` checks only the lowercase `personal:` prefix,
  so `requireOwn("Personal:someone-else", me)` does not throw on its own —
  its safety depends on `resolveAddress` having run first. Two static helpers
  with an undocumented ordering contract.

There are **three** spellings: `name(handle)` → `personal:<hex>` (storage),
`address(handle)` → `Personal:<handle>` (public routing identity), and the
typed selection.

`ProjectStore.ordinary` already refuses both prefixes, so a real project
cannot be minted as `personal:<hex>`. Under this direction the address space
is shared by design, so **that guard becomes load-bearing** — confirm every
creation *and rename* path routes through it.

Admission is correct in both spellings today: `JdbcProjectMembers.role`
answers MANAGER for `PersonalSpaces.name(handle)` to its owner. This is a
**placement** fix, not an authorization change.

## Carried gaps, outside the eight tasks

- **`ConversationStore`'s legacy `(JdbcTemplate, …)` constructors leave
  `operations` null and skip admission silently.** Five constructors exist;
  presumably unreachable in production wiring, but worth making
  unconstructible rather than trusted.
- **`Compaction.logFor` swallows `RuntimeException`** from
  `conversations.log` in two places and degrades to `Transcript.NONE`.
- **A reserved-but-never-initialized personal space** has a scope but no
  grant, so uploads to it are still refused.
- **A personal upload whose receipt already carries a log** skips the log
  opening and so skips the refusal. Validate any fix against a *first*
  attempt, not a retry.
- `LiveAccountKnowledgePermissions` gates Global documents and duplicates
  SQL. Separate task, deliberately untouched.

## Constraints that held through tasks 1–3 and should keep holding

1. No protocol, SDK or contract change. `Home` stays as it is.
2. No behaviour change beyond the named defects. Global admission stays
   closed; project writes, service-account refusal and audience changes keep
   current behaviour **and error text**.
3. Mock repositories by default per the database test policy. `-PfullDb`
   only where the assertion needs real PostgreSQL, with the reason stated.
4. **Every change leaves the system consistent.** The Global closure landing
   without its replacement is what produced this whole thread; do not repeat
   the shape. Piecemeal migration across a server with this many migrations
   is how inconsistent states arise.

## Method note

Four confidently-argued diagnoses were wrong during this work, each costing
time. The common thread: reasoning from source without checking which layer
actually emits the observed error. The message in the original defect
appeared **once** in the codebase, and that fact was available from the first
grep.

Pin before fixing. A pin executes the behaviour and asserts what it actually
does, so a wrong theory dies before it becomes a change. Inverting a defect
pin is the acceptance criterion — not "the feature works". A pin that
outlives its fix is an accepted risk and should become a disabled expectation
of correct behaviour.

## Stale references to correct

`authority-scope-admission-plan.md` still says production runs `b6ffc2bb` and
names the deploy as the remaining gate. Both are now stale: production ran
`5ef22ff0` before the 22 Oct release, now runs `8467c821`, and the live gate
has passed.
