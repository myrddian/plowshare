# Removing the null representation of global

Status: implementation plan, 23 October 2026. Baseline `main` at `0785a3f0`,
measured against the deployed server and database of the same revision.

**The goal is the representation, not the concept.** Global is kept and is
already enumerated — `BuiltInIdentities.GLOBAL` (…004) and `GLOBAL_KNOWLEDGE`
(…005). What is being removed is `null` as the way global is spelled. This is
`global-scope-migration-map.md:64` verbatim — "null scope representation
replaced by explicit scope UUIDs" — with `:65` adding "omission never selects
server scope".

So the work is not to constrain global or to delete it. It is to make a scope
always *stated*, so that `project IS NULL` stops meaning anything.

## Why the conflation exists

From the private `plowshare` history (the public repository is squashed):

| date | commit | event |
|---|---|---|
| 2026-08-26 | `d691463f` | `Home` introduced by `feat(protocol): the memory record and its four states`, modelling **memories only**. Its Javadoc chose `null` so the global tier would be *unspellable*. |
| 2026-09-01 | — | accounts and authentication arrive |
| 2026-10-03 | `91ab6fea` | "Add account Personal spaces **and make global an abstract resource scope**" |

`91ab6fea` gave Personal a real `projects` row — which is why
`AuthorityScope.Personal` carries a `projectId` and why Personal was repairable
— and made global an abstract resource scope with no project row, so it
inherited `Home`'s `null`. From then on `null` meant both the global memory
retrieval tier and an unstated scope.

## What the code actually does — 19 sites, two kinds

`grep "== null ? Home.global()"` finds 19 sites in `plowshare-server`. They are
not one problem:

**Kind A — read-back (10 sites).** Row mappers reconstructing a nullable column:
`MemoryStore:813`, `JdbcPassageRepository:102,172`, `ProposalStore:680`,
`DigestStore:205,346`, `JdbcJobLog:241`, `ConversationStore:1512`,
`JdbcCodeWorkspaceStore:119`. These do not *choose* global; they faithfully
return a null that is already stored. **Fixing the mapper fixes nothing** — the
stored null is the defect, so these follow from the storage change and should
not be edited first.

**Kind B — decision (9 sites).** Code deciding that an absent project means
global: `InformationLifecycle:577,615,680`, `BoundPreparationEmbeddings:74`,
`InformationStageHooks:81,182`, `BoundEmbeddingRepairs:44`, `ImageSidecars:76`,
`SkillFileHooks:120`, `Orchestrations:1238`.

Every Kind B site shares one shape: it reads a **persisted, nullable project**
and cannot tell "this account's own space" from "no project at all". For
example all three `InformationLifecycle` sites are
`repository.projectName(lease.project())` followed by the null test. That is the
same information loss that closed personal admission, so each one is downstream
of storage rather than independently wrong.

**Consequence: this is one change at the root, not nineteen scattered fixes.**

## What production actually holds

Of 21 nullable `project_id`/`project` columns, only 7 contain any nulls:

| table | nulls | total | verdict |
|---|---|---|---|
| `inference_calls` | 3524 | 182262 | accounting rows; needs a decision |
| `conversations` | 503 | 11917 | **the live defect** |
| `conversation_allowance_state` | 503 | 11917 | mirrors `conversations` |
| `information_resources` | 50 | 2620 | **suspect** — see below; populated is not correct |
| `information_document_policies` | 48 | 2617 | follows `information_resources` |
| `authority_scopes` | 9 | 23 | **correct by design**; knowledge and administration scopes have no project |
| `relay_topics` | 2 | 47 | needs a decision |

There are **zero memories, globally or at all**, so the global memory tier owes
no migration. The fourteen remaining nullable columns hold no nulls.

**Treat every count here as a measurement of a repeatedly-migrated database,
not as a statement of intent.** Rows can be internally consistent and still
record the wrong thing, which is exactly what the `information_resources`
section below documents. Verify what a path *writes today* before concluding
anything from what is stored.

### `information_resources` is populated, and that is not the same as correct

**Do not read this table as finished.** The database has been migrated
repeatedly and things have broken in the process, so a populated column is
evidence of a backfill having run, not of it having been right.

Every one of its 2620 rows does have a `home_scope_id`, and the 50 rows with a
null `project_id` are all `ACCOUNT_KNOWLEDGE`. An earlier draft of this plan
concluded from that pair of facts that the table was done and the nulls
correct. **That was wrong**, and the reason is visible one query deeper.

Two live paths write what a person would call a personal document, to two
different scopes:

| spelling | namespace | `home_scope_id` kind | `project_id` |
|---|---|---|---|
| `{kind:"personal"}` | `account:<handle>` | `ACCOUNT_KNOWLEDGE` | **null** |
| `{kind:"project", project:"personal:<hex>"}` | `project:personal:<hex>` | `PERSONAL` | the personal project |

Verified by upload: ten corpus documents sent as `{kind:"personal"}` are all
`ACCOUNT_KNOWLEDGE` with a null project, and one sent as a project naming the
same home is `PERSONAL` with a real project. The split is exact across the
table: **all 50 `ACCOUNT_KNOWLEDGE` rows have a null project, all 28 `PERSONAL`
rows have one.** Their date ranges overlap (10-05→10-23 and 10-12→10-22), so
this is two concurrent paths, not a clean before-and-after.

The root is one line, `InformationContext.namespace()`:

```java
case ACCOUNT  -> "account:" + selection.account();
case PERSONAL -> "account:" + account;        // same family as ACCOUNT
case PROJECT  -> "project:" + selection.project();
```

`PERSONAL` and `ACCOUNT` share the `account:` family, and V157's resolver keys
the scope off the namespace (`'account:'||a.legacy_handle` →
`ACCOUNT_KNOWLEDGE`). So a typed personal selection is filed under the account's
*knowledge* scope. This is the storage-level cause of the two mutually invisible
partitions reported separately; `projectSelection` not normalizing is a symptom
above it, not the root.

**One consequence to weigh rather than assume.** `requireAccountAction`
consults `AccountKnowledgePermissions` — which demands ADMIN with SELF coverage
— only when `selection().scope() == ACCOUNT`. A PERSONAL selection therefore
writes a row into that account's `ACCOUNT_KNOWLEDGE` scope without that gate
being consulted. It is the caller's **own** scope, so this is not cross-account
escalation, and the upload is legitimately authorized as a personal write. But
two gates now give different answers about the same scope, and the gate pinned
in `LiveAccountKnowledgePermissionsPinTest` is not the only way into it.

### The decision, given 23 October 2026

**A personal space is a project.** There is nothing special about it in the
core; the specialness is workflows and tooling around it, mostly outside
Plowshare itself — the same relationship Applications have to Projects.

So **there is no personal tier.** `kind:personal` is *shorthand* for "the
personal project this principal has access to", and it resolves to that
project's address. For a service account running a project, that principal's
"personal" is that Project or Application. A "personal memory" is therefore
just a memory in your personal project, and memories keep two real tiers.

That settles which of the two observed shapes is right:

| spelling | scope | project | verdict |
|---|---|---|---|
| `{kind:"project", project:"personal:<hex>"}` | `PERSONAL` | real | **correct** — project-shaped |
| `{kind:"personal"}` | `ACCOUNT_KNOWLEDGE` | null | **the break** |

The 28 `PERSONAL` rows are right. The 50 `ACCOUNT_KNOWLEDGE` rows are what
moves. And the two things `ACCOUNT` conflates are now named: the shared global
knowledge destination, and an account's own space — where the second should not
be an account-knowledge scope at all, because it is a project.

**Consequence: the seven non-global `ACCOUNT_KNOWLEDGE` scopes become
vestigial.** One exists per account (admin, enzo, client-evaluation,
furrow-web, and three workers). Under this model they hold documents belonging
in those accounts' personal projects. Only GLOBAL's `…005` is a real
destination.

## The sweep: make everything resolve a PERSONAL selection the way #104 does

PR #104 established the pattern at two sites, and the work is applying it
consistently:

```diff
- Home home = project == null ? Home.global() : Home.of(project);
+ Home home = InformationAccess.executionHome(context);
```

`executionHome` resolves PERSONAL to `Home.of(PersonalSpaces.name(account))` —
the personal *project address* — which is exactly the shorthand-resolves-to-a-
project model above. Six decision sites in `information/` were left unconverted,
and they are not equally convertible:

| site | sees an `InformationContext`? | consequence |
|---|---|---|
| `InformationStageHooks:81,182` | yes | likely mechanical, as #104 |
| `InformationLifecycle:577,615,680` | **no** | blocked; works from `repository.projectName(lease.project())` |
| `BoundPreparationEmbeddings:74` | **no** | blocked; same shape |

A site with no context cannot resolve the shorthand, because a null column
cannot distinguish a personal space from no project. **So the ordering is
forced**, and the storage change must come first.

Outside `information/`, four sites need judging individually rather than
converting — each may legitimately mean the global memory tier:
`Orchestrations.homeOf` (a child inherits its parent's tier; correct if parents
carry a resolved project), `ImageSidecars:76`, `SkillFileHooks:120`,
`BoundEmbeddingRepairs:44`.

### `namespace()` is the root, and is not a free edit

```java
case PERSONAL -> "account:" + account;          // sends PERSONAL into the ACCOUNT family
case PROJECT  -> "project:" + selection.project();
```

Under the decision above this should resolve to the personal project's address,
`"project:" + PersonalSpaces.name(account)`. Two cautions:

1. `namespace()` is documented as the "stable destination locator for
   receipts", so changing it re-homes existing receipts.
2. `information_resources_home_name` is `UNIQUE (home_scope_id, source_name)`.
   A document may already exist under **both** spellings, so the migration has
   a collision case to decide, not merely a re-point.

### V157 remains the template for adding a scope column

Whatever the resolution, the mechanics of giving a table an explicit scope are
established by `V157__knowledge_homes_and_write_authority.sql`:

`V157__knowledge_homes_and_write_authority.sql` establishes the whole pattern:

```sql
ALTER TABLE information_resources ADD COLUMN home_scope_id UUID REFERENCES authority_scopes(id);
-- a resolver deriving the scope from the legacy columns
UPDATE information_resources SET home_scope_id=information_home(project_id,namespace,owner_handle);
CREATE UNIQUE INDEX information_resources_home_name ON information_resources(home_scope_id,source_name)
    WHERE home_scope_id IS NOT NULL;
CREATE FUNCTION information_knowledge_identity() RETURNS trigger ...  -- keeps new rows consistent
```

Add the scope column, resolve it from what is there, backfill, index, and add a
trigger so new rows cannot drift. **Follow this, do not invent a second shape.**

### `conversations` has no scope column at all

It depends entirely on nullable `project_id`, and its 503 null rows are
**still growing**: 415 `delegation` and 88 `submission`, spanning 2026-10-05 to
2026-10-23. A `document_pipeline` SUBMISSION log opened at a null home at
06:16:32 on 23 October — after that morning's deploy, and seventeen seconds
after the same upload correctly opened its log at
`personal:636c69656e742d6576616c756174696f6e`. One upload, two logs, one null.

The leaking site is `InformationStageHooks:81`/`:182`. It escapes the global
refusal because `ConversationStore` admits only when
`system == null && operations != null && (origin == SUBMISSION || origin == EVENT)`,
and that path runs as SYSTEM.

## Tasks

**1. Give `conversations` an explicit home scope.** A new Flyway migration
following V157: add `home_scope_id` referencing `authority_scopes(id)`, a
resolver deriving it from `project_id` and `owner_handle`, a backfill, and a
trigger keeping new rows consistent. `conversation_allowance_state` follows.

The 503 existing rows need an explicit decision, stated in the migration: what
each `delegation` and `submission` row resolves to, or that they are
deliberately retained as history. The migration map preserves "ownerless global
logs … as history", so retention is defensible — but it must be chosen, not
defaulted.

**2. Write the resolved home at the source.** `InformationStageHooks` and the
`InformationLifecycle` sites should read the stored scope rather than recompute
from a project column. Do **not** resolve a null column to
`PersonalSpaces.name(owner)`: that implements request omission and would
silently redirect a genuine global selection. A previous implementer refused
exactly that instruction and was right to.

**3. Close the three bypass doors**, which is tasks 6–7 of the admission plan:
SYSTEM work with no `selected` scope falling back to `GLOBAL_KNOWLEDGE`; four of
five `ConversationStore` constructors leaving `operations` null and skipping
admission silently; and origins other than `SUBMISSION`/`EVENT` never being
admitted at all, which is where the 415 delegations arrive.

**4. Decide `inference_calls` and `relay_topics`.** 3524 and 2 null rows. Both
may be legitimately project-less; establish which before migrating either.

**5. Only then retire the legacy columns.** Once every row carries a scope and
every writer sets one, make the nullable columns NOT NULL or drop them, and let
the Kind A read-back sites collapse. Deprecating `Home.global()` at that point
makes the compiler enumerate anything left, against `-Xlint:all -Werror` and a
server that currently carries zero `@Deprecated`.

## Constraints

1. **Pin before changing.** Each task starts by executing current behaviour and
   asserting what it does. Inverting a defect pin is the acceptance criterion,
   not "the feature works".
2. Never edit a shipped migration. Schema changes are new Flyway files.
3. `authority_scopes`'s 9 null `project_id` rows are **correct**. Do not
   "fix" them.
4. The global memory tier is `Home`'s original and designed meaning. Judge each
   `Home.global()` by whether it names that tier or stands in for an unstated
   scope — never by its shape.
5. No protocol or SDK change without a separate decision: `Home` is a wire type
   on `Memory`, referenced across the server.
6. Mock repositories by default; `-PfullDb` only where the assertion needs real
   PostgreSQL, with the reason stated. A backfill over 11917 rows is such a case.
7. Every change leaves the system consistent. The Global closure landing without
   its replacement is what produced this whole thread.
