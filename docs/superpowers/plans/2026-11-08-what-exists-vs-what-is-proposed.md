# What exists vs what is proposed

Read this before the work graph. The graph and its findings register interleave
shipped facts with proposals, which makes the boundary hard to see. This document
is only the boundary.

**One sentence.** Containment is built and tested **up to and including the
repository and store layer**; **nothing above that layer admits a contained
principal**; and **every proposal under discussion is about that layer and above**.

**Four categories, and the third is where confusion comes from:**

| | What it is | Status |
| --- | --- | --- |
| **A** | Shipped on `main` | Live. Predates this work entirely. |
| **B** | This branch, PR #174 ("slice four") | Code complete, tested, **unmerged**. |
| **C** | Proposals | **Zero code. Nothing written. Nothing decided.** |
| **D** | Findings `F1`–`F17` | **Not proposals.** Verified statements about A and B, plus what they imply for C. |

---

## A — Shipped on `main`

All of this is live and none of it is mine. Verified present on the merge base
`8d5c11fd`.

| Migration / construct | What it gives you |
| --- | --- |
| V37 `admins`, V111 `account_kind` | The identity registry — `USER`, `SERVICE`, `SERVICE_TOKEN`, later `BUILTIN`. Only `USER` may be `server_admin`. |
| V155 `authority_accounts`, `authority_roles`, `authority_scopes` | The authority substrate. Every authority account is anchored to an `admins` row by `legacy_handle`. |
| V156 `authority_grants`, `authority_project_role`, `authority_live_grants` | RBAC proper: grants, the role-resolution predicate, the live-grant recursion. |
| V111 / V138 `account_active`, V158 `information_global_read_member` | Liveness and global-read membership. |
| `ScopedPermissions` → `EffectiveProjectPermissions` | The service-layer permission gate. One method: `allows(Principal, ProjectTarget, Action)`. |
| **V216** | `CONTAINED` as a **fifth scope kind**, with a `container` column and an ancestry edge. Deliberately excludes contained scopes from grant issuance and from live-grant coverage. |
| **V218** `contained_principals` | The child's identity: a bare UUID, **no credential material** — no row in `admins`, `authority_accounts` or `service_tokens`. |
| **V223** | Contained knowledge reads. `contained_scope_ancestors`, `contained_scope_readable` (a self arm and an ancestor arm). |
| **V229** | Contained actor reads and the two write gates. `contained_knowledge_actor`, `contained_own_home`. |
| ADR 0042 | The containment decision record and its surface inventories. |

**So on `main` today:** a child has an identity, a scope, an ancestry, and
purpose-built SQL predicates that admit it to its own knowledge. It has no
credential, no handle, no role, and no grant — by design.

## B — This branch (PR #174, "slice four")

18 commits, `check` green at the tip, **unmerged**. 37 files. The substantive
additions:

| Added / changed | What it does |
| --- | --- |
| `InformationActor` (new) | The two-axis actor: `acting()` is the child principal every gate is asked about; `attribution()` is the container the provenance columns record. |
| `ContainedActorGuard` (new) | The admission guard for a contained actor. |
| **V231** (new migration) | Navigation reference visibility for a contained principal — added as a disjunct. |
| **V232** (new migration) | Contained memory reads. |
| `MemoryStore`, `MemoryQueries`, `MemoryWrites`, `JobRuntime` | Widened to carry the contained actor; three memory-path sites corrected to pass the acting axis rather than the container's handle. |
| 9 `Jdbc*Repository` classes, `InformationAccess` | Carry the actor through the repository layer. |
| 8 new test classes | Characterization and reach tests, mostly `@Tag("full-db")`. |

**What slice four did *not* do, and this is the crux:** it opened the repository
and store layer. It did not open `ScopedPermissions`, and it did not open
`DocumentNavigation`. **No production caller reaches the code it added** — that
limitation is stated in ADR 0042 as the "no-caller limitation" and it is not a
defect in the slice, it is the slice's documented boundary.

## C — Proposals. None of this exists.

Nothing below has been written, authorized or decided. All of it lives in
[the work graph](2026-11-08-contained-proxy-principal-work-graph.md) as items.

| Item | Proposal |
| --- | --- |
| A2 | A regression test pinning the existing sign-in allowlist. *Safe to write now; asserts current behaviour only.* |
| A5 | A decision record. **Unwritten, and it gates all of Phase B.** |
| B1 | A `CONTAINED` account kind in `admins` — an identity that cannot authenticate. |
| B2 | Grants for that identity. Requires relaxing **four conjuncts** across `authority_grant_insert` and `authority_live_grants`. **Highest risk in the whole proposal.** |
| B3 | A `CONTAINED` branch in `authority_project_role`. |
| B4 | A `scoped_accounts` table (linkage) plus an accountable-ancestor resolution. *The resolution half needs no new table and no proxy — see below.* |
| C1–C4 | Lifecycle wiring, collapsing the two-axis actor, removing the 24 embedded predicates, opening the ceiling. |
| D1–D3 | Reach tests, an inventory re-audit, a repository-wide check. |
| Variants | A handle-less proxy (F15); and the **fallback** — an ancestry-walking branch in `authority_project_role`, keeping the bare principal. |

**The one proposal that needs nothing else first:** the accountable-ancestor
resolution. `contained_principals(id, scope_id)` and
`contained_scope_ancestors(descendant)` both already exist in category A, so
"given a contained principal, who is accountable for its rows?" is answerable
today with no schema change and no proxy. Useful under every option, including
abandoning all of them.

## D — The findings are not proposals

`F1`–`F17` in the graph's register are **verified statements about categories A
and B**, each citing a construct in shipped code, plus the consequence for C. They
are the reason the proposals' costs changed; they are not themselves work.

Three of them matter most for orientation:

- **F16** — the ceiling is **deliberate**, not an oversight. V216 made `CONTAINED`
  an authority-free scope kind and updated five functions to enforce that. So the
  proposals are not a bug fix; they would replace authority-free containment with
  authority-bearing containment.
- **F14** — `admins` is **not an administrators table**. It is the universal
  identity registry; V111 structurally bars every non-`USER` kind from
  `server_admin`. A proxy there gains no administrative privilege.
- **F9/F11** — 60 foreign keys reference `admins(handle)` and 58 block deletion, so
  any identity row created there is permanent. This is a fact about A, and it is
  what makes the proposals' lifecycle expensive.

## The decision that is actually open

Not "should we fix the ceiling." It is:

**Should a contained scope carry RBAC authority at all?** V216 decided no. The
proposals ask to reverse that so the layer slice four built becomes reachable. The
alternative is the fallback, which opens only the container-facing half and leaves
the child's own run unable to pass `ScopedPermissions`.

That decision has not been made, and nothing in categories A or B depends on it.
