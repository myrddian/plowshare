# Completing the explicit-scope cutover in admission

Status: implementation plan, 22 October 2026. Baseline `c9a2cf67`; work in
progress on `claude/personal-knowledge-write`. Server-side only.

**This is conformance work, not a new design.** Every invariant below is already
written down. The implementation can bypass each of them and nothing fails when
it does. The visible symptom — Personal document uploads refused — is one
consequence of a cutover that was specified, started, and left partly done.

## The written invariants

| invariant | where |
|---|---|
| "Omission never selects server scope" | `global-scope-migration-map.md:65` |
| Null scope representation replaced by explicit scope UUIDs | `global-scope-migration-map.md:64` |
| A SYSTEM service is admitted with the caller's trigger, SYSTEM's authority **and the worker's operation sandbox** | ADR 0030 |
| Platform services have **explicit** purpose, input/output and spending contracts | ADR 0030 |
| Global knowledge: everyone reads; only ADMIN promotes/writes; ADMIN is delegated from SYSTEM | ADR 0030; `global-knowledge-read-contract.md` |
| `tools`, `calls`, `scopes` declare what an agent may do; **`scopes` cannot be widened at run time** | `AgentDefinition` Javadoc :24, :55 |

`BuiltInIdentities` already holds the registry: `SYSTEM` …001, `ADMINISTRATION`
…003, `GLOBAL` …004, `GLOBAL_KNOWLEDGE` …005 (a ScopeId), `GLOBAL_READ` …006 (a
RoleId). The two paths to "everyone can read this" are separate identities, as
designed.

## Established by test — six pins, 36 tests

| pin | commit | asserts | invert on fix? |
|---|---|---|---|
| `PinnedOperationAuthorityTest` | `bf538538` | admission takes `AuthorityScope`; Global admission closed twice | no |
| `InformationWriteGatesHomeTest` | `4ea69c14` | PERSONAL selection opens its pipeline log against `Home.global()` | **yes** |
| `ConversationStoreAdmissionHomeTest` | `336cfe3a` | a global home is refused at admission, in `scope(Home)`, before the account is examined | **yes** |
| `DeliberationSystemScopeTest` | `bf87d480` | a personal document's SYSTEM stage admitted against `GLOBAL_KNOWLEDGE` | **yes** |
| `SystemCompactionTest` | `ab20aa5e` | compaction: global home → `GLOBAL_KNOWLEDGE`, project home → that project | **yes** |
| Scribe/Learner/DigestModel/Curator pins | `93a01619` | per-caller scope and latitude | **partly** |

**Inverting the defect pins is the acceptance criterion** — not "the upload
works". A pin that outlives its fix is an accepted risk and must be converted to
a disabled expectation of correct behaviour.

## Two broken features, both invisible to the existing suite

**1. Personal document upload is refused.** `Selection` forbids a project on any
non-PROJECT scope → `project == null` → eight sites in `server/information/` map
that to `Home.global()` → `OperationAuthority.scope(Home)` refuses a global home
outright. Nothing is written; a refused upload leaves no row.

**2. The Curator never runs.** `Curator:468` hands work to
`JobRuntime.forSystemWork`, but `BoundSystemOperations.admit` first calls
`SystemModelTasks.requireModelOnly`, which refuses any definition carrying a
tool — and `promotion_judge.md:15` declares `tools: [memory_read]` deliberately
("the tool is the whole reason a ruling is a job rather than a second scribe").
A production-wired pass throws `IllegalStateException` on its **first candidate**
and `Curator.pass` does not catch it: no ruling, no proposal, no envelope. Every
existing `CuratorTest` uses the five-argument constructor, which leaves `system`
null and skips admission — so the suite has never exercised the production path.

## The SYSTEM ceiling is one check too strict

`SystemModelTasks.requireModelOnly` refuses on seven conditions. They are not the
same kind of thing:

| condition | role | keep for SYSTEM? |
|---|---|---|
| `calls`, `orchestrations`, `reviewWith` | delegation | **yes** |
| `scopes`, `skills` | runtime capability acquisition, **including provider/hosted tools** | **yes** |
| `board` | outward side effects | **yes** |
| `tools` | the static declaration every one of the 35 shipped agents already carries | **no** |

Six say *you may not grow*. The seventh says *you may not have* — a stricter,
different policy, correct for the model-only workers the class was named for and
wrong for the judge.

Dropping the seventh does **not** open the dynamic-tool escape: provider tools
arrive through `scopes`, which the Javadoc calls "the one an agent cannot widen
at run time" and which remains refused. Hosted scopes additionally require a live
registration, a fresh catalogue and a current credential grant
(`hosted-tool-scopes.md`).

## Severity, per SYSTEM caller

Contract-bounded means `BoundSystemOperations.claimed(...)` refuses unless
`tools()` is empty and the model matches, with the prompt pinned by hash.

| caller | scope on global home | bounded? | severity |
|---|---|---|---|
| `Compaction:4466` | `GLOBAL_KNOWLEDGE` | yes | wrong ceiling, **unusable** |
| `Scribe:198` | `GLOBAL_KNOWLEDGE` | yes | wrong ceiling, unusable — but **global home is directly reachable from a person's request** (`MemoryController.write` → `RequestedHome.in(null)`) |
| `Learner:224` | `GLOBAL_KNOWLEDGE` | yes | wrong ceiling, unusable; reachable only via `Origin.MEMORY` global-home rows |
| `DigestModel:194` | `GLOBAL_KNOWLEDGE` | yes, but **prompt not pinned** (binding is `String.valueOf(trace.conversationId())`, so one admission authorizes many differently-worded calls) | wrong ceiling, unusable, weaker fence |
| `Deliberation:1002` | `GLOBAL_KNOWLEDGE` | — | confirmed misroute |
| `Curator:468` | n/a — never admitted | n/a | **broken**; needs latitude the ceiling forbids |

A wrong ceiling that is unusable only because a second, unrelated mechanism holds
is still worth fixing. It is not an incident.

## The holdover hypothesis is disproved

Global *conversations* are removed (`personal-space.md:446`), but these branches
are live, not dead:

- `DigestModel`/`Scribe` — the home is a memory **tier**, first class.
- `Learner` — `logInternal` resolves a scope (and so refuses global) **only** for
  `Origin.SUBMISSION`/`EVENT`, so `Origin.MEMORY` global logs are written today;
  the migration note preserves "ownerless global logs … as history", and
  `EntryStore.CONVERSATIONS_AWAITING_THE_LEARNER` has no origin or home predicate.
- `Curator` — no global branch exists.

**Deleting them as dead would be wrong**, because the memory tier is live. But the
claim previously made here — that "git history is squashed to one snapshot
(`cd7f68ab`), so no archaeology is available either way" — **was wrong**, and it
was load-bearing. The public repository is squashed; the full history is in the
private `plowshare` repository (2242 commits, tip 2026-10-06). See
[the provenance below](#where-global-as-a-scope-came-from).

## Where global-as-a-scope came from

Read from the private history, which settles what the two meanings of "no
project" are and which of them is original.

| date | commit | event |
|---|---|---|
| 2026-08-26 | `d691463f` | `Home` introduced, in `feat(protocol): the memory record and its four states` |
| 2026-09-01 | — | accounts and authentication arrive |
| 2026-10-03 | `91ab6fea` | "Add account Personal spaces **and make global an abstract resource scope**" |

`Home`'s original Javadoc is explicit that it models **memories only**, and that
`null` was chosen deliberately to make the global tier unspellable:

> Which of the two tiers a memory lives in: a named project, or global.
>
> **Global is the absence of a project, not a project named "global".** If the
> global tier were spelled as the project string `global`, then a real project
> that happened to be called that — or any code that defaulted a missing project
> to a placeholder — would silently write into the tier every agent everywhere
> reads. Modelling it as `null` makes that unspellable.

Five weeks later, `91ab6fea` did two things at once. Personal spaces got a real
`projects` row (`projects.personal_owner`, internal name `personal:` plus the
hex-encoded handle, per `docs/personal-space.md` as added by that commit) — which
is **why Personal was repairable**: `AuthorityScope.Personal` carries a
`projectId`. In the same change, global was promoted from a memory tier to "the
shared server setup and resource scope": abstract, with no project row.

`Home`'s `null` was the only "no project" representation available, so the new
abstract global *resource scope* inherited it. From that commit onward, `null`
meant both "the global memory retrieval tier" and "the global resource scope".

**That conflation is the whole defect family** — refused Personal uploads, the
uninstallable public manual, and the SYSTEM ceiling admitting by omission — and
`Home`'s own Javadoc had warned against precisely it.

Two consequences for this plan:

1. **Global-as-a-scope is a late addition, not a foundational concept.** It
   postdates `Home` by five weeks and arrived with multi-user support. "Removed"
   is therefore a more plausible target than "constrained", and the memory tier
   is a separate thing that stays.
2. **`AuthorityScope.AccountKnowledge` is most likely that abstract scope**, typed
   in terms of "an account's knowledge" when the only reachable such account is
   `BuiltInIdentities.GLOBAL` (`InformationContext.Selection.account` throws for
   any other id, and only two call sites resolve a knowledge scope at all). The
   authority substrate postdates the private repository's tip, so this is a
   well-supported reading rather than a proven one. If only Global's knowledge
   scope will ever exist, a `GlobalKnowledge(ScopeId)` variant carrying no
   `AccountId` would make the sealed interface honest.

## Tasks

**1. Admission takes `AuthorityScope`** — done, `bf538538`. Four runtime call
sites. Two bridges added (`IdentityRepository.home(AuthorityScope)`,
`OperationAuthority.scope(Home)`) because `Admission` retains a legacy
project-name column; both go when `Home` leaves `Admission`, and must not become
permanent.

**2. Pin the scope-less callers** — done, `bf87d480`, `ab20aa5e`, `93a01619`.

**3. Resolve Personal explicitly in the information path.** Use
`identities.personal(account)` instead of producing `Home.global()`. The
authority layer is ready: `admit`/`requireOwn` work end to end for a Personal
scope, and `JdbcProjectMembers.role` answers MANAGER for
`PersonalSpaces.name(handle)` when the caller owns it. Invert the write-gate and
admission pins. **Next.**

**4. Split the SYSTEM ceiling.** Pin that each of the six escalation checks still
refuses *individually*, then drop `!tools().isEmpty()`. This loosens an
authorization fence, so the pins come first — the argument "six of seven are the
real ones" currently rests on reading.

**5. Decide the Curator.** Either the judge loses its tool, or model-only is the
wrong ceiling for curation. Task 4 makes the second option available; which is
right is a design decision, not an implementation one. Its scope is already
correct (the curated project's own; `Home.of` refuses null/blank).

**6. Pass the sandbox at every SYSTEM admission site.** Supply `selected`, per
ADR 0030. Then the global fallback in `BoundSystemOperations` is unreachable from
these callers. Load-bearing once task 4 lands, because a tool-bearing worker's
scope decides what the tool can reach.

**7. Deprecate, then delete, the scope-less overloads.** The build sets
`-Xlint:all -Werror` ("a warning nobody fails on is a warning nobody reads",
`build.gradle.kts:137`) and the server has **zero** `@Deprecated` and **zero**
deprecation suppressions, so a deprecation here is a build break, not a warning:
the compiler produces the to-do list and refuses to proceed until it is cleared.
Deprecate only once tasks 3–6 leave no callers; then delete, and omission becomes
unsayable. Consider a policy script asserting zero deprecation suppressions in
`access/`, following `check-native-sdk-policy.py`.

**8. A wrapper that names the kind of SYSTEM work.** The distinction is not
"SYSTEM or not" but *model-only* versus *tool-bearing-but-declared*, with
different requirements on each. Shape depends on what tasks 4–6 settle; design it
against the pins, not against recollection.

## Constraints

1. No protocol, SDK or contract change. `Home` stays — it is a wire type on
   `Memory` and a memory retrieval partition, correct for that job.
2. No behaviour change beyond the named defects. Global admission stays closed;
   project writes, service-account refusal and audience changes keep current
   behaviour and error text.
3. `LiveAccountKnowledgePermissions` is out of scope — it gates Global documents
   and duplicates SQL; separate task.
4. Mock repositories per the database test policy. No `-PfullDb`, no
   `dockerSmoke`, no PostgreSQL.
5. **Every change leaves the system consistent.** The Global closure landing
   without its replacement is what produced this; do not repeat the shape.

## Verification

Targeted tests plus `format`/`formatCheck`, and `./gradlew check` before handoff.

**The real gate is live.** `information.upload` with `scope:{kind:"personal"}`
must succeed against the server, which needs a build and deploy past the current
`b6ffc2bb`. The pins establish the mechanism; only the deploy establishes the fix.

## Known gaps, carried deliberately

- `ConversationStore`'s legacy `(JdbcTemplate, …)` constructors leave
  `operations` null and **skip admission silently**. Presumably unreachable in
  production wiring; worth making unconstructible rather than trusting.
- A personal upload whose receipt already carries a log skips the log opening and
  so skips the refusal. Validate against a first attempt, not a retry.
- Whether these stages actually *write* through the wrong ceiling is
  unestablished. Wrong-ceiling and wrong-data are different claims.
