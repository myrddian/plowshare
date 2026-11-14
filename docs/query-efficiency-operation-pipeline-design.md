# Operation read pipeline: resolve once, pass it along

Status: proposed design, not implemented. It builds on
[item-level invalidation](query-efficiency-item-invalidation-design.md) and the single-revision
"decide, then fetch" reads in [deployment follow-ups](query-efficiency-deployment-followups.md).

## Problem

PostgreSQL checking permissions is not the problem. A set-based statement that authorizes and
fetches a page in one indexed pass, reading authority and data in one snapshot, is often the best
tool, and the design keeps using it. The problem is repetition around those statements: one user
operation resolves the same permission, or fetches the same object, several times, often discarding
what it already had.

A code audit (read-only, call chains verified) found these patterns. PROD call counts come from a
28-minute `pg_stat_statements` sample at `2c305443`:

| # | Pattern | Where | Effect |
| --- | --- | --- | --- |
| 1 | The hosted tool list is fetched once per catalogue tool inside a loop | `DefinitionResolver.forCaller` → `stamp` → `ApplicationToolRegistry.revision` → `snapshot` → `connections.connections(project, null)` per tool | `hosted_tool_scopes` by project: 41,824 calls; each list runs about ten checks per binding |
| 2 | An Application root is resolved 3–5 times per `forCaller`, even on a cache hit | `keyFor`, `agentsDirectory`, `botsDirectory` → `WorkspaceApplicationResources.root` → name, boundary, `projects.find`, manifest parse | `projects` by name: 16,874; `application_boundary`: 15,571 |
| 3 | A waiting relay tool re-checks its full permission chain every 100 ms | `ProjectRelayToolInvocations.invoke` loop → `hosting.permits` + `authorizeSame` | About 20 permission chains per second of wait |
| 4 | One tool call resolves the same tool entry at least twice and rebuilds the snapshot that listing already built | `ApplicationToolRegistry.LiveTool.run` → `assigned`, `connections`, `snapshot`, `assigned` | Patterns 1–2 again per call |
| 5 | Operation authority is re-required 3–4 times per tool call; inside it, live grants are fetched again per support item | `JobRuntime` → `PinnedOperationAuthority.require` per step, target and after the tool; `admit` → `allowsUsing` refetches `live` and `lineage` per grant | `grants.live` ≈ 47,000, `grants.lineage` ≈ 46,000 |
| 6 | Each publish re-checks access twice and re-registers its topic | `ProjectRelayPorts.publish` → `require` ×2 (+ancestry); `registerTopic` on every publish → insert-on-conflict then a locked read | `relay_topics` locked reads ≈ 30,000; inserts ≈ 10,000, almost none new |
| 7 | One socket request checks the session version three or more times | `EventChannelHandler` frame receipt, `routeConcurrent`, validity lambdas | Session check ≈ 63,000 calls |

Caching the inner reads (the authority snapshot, `knowledge-facts`, `log-tree-facts`) made each
repeat cheaper. It does not remove the repeats, and every cached read still pays its confirmation
statement.

## Principles

1. **Resolve once per operation.** An operation is one tool call, one job step or one socket
   request. Its identity, selection, project, Application and admitted authority are resolved at
   its start and carried, not looked up again by name or id further down.
2. **Fetch once, pass the object.** A value loaded by a guard is the value the executor uses. An
   API that takes an id when its caller already holds the object is the smell to remove.
3. **SQL does set work; code does not repeat it.** A statement that authorizes and fetches a set in
   one pass stays. A per-row predicate, or the same small lookup repeated per item or per step, is
   replaced: by one set-based statement, by a decision in code from cached facts, or by the value
   the operation already holds.
4. **Two read shapes per object, chosen once.** A plain object fetch by id runs after the decision
   is made in code. The current authorizing statement is the fallback, optimised where it can be
   (statement-local batched admission instead of per-row predicates). This is the shape `a35003da`
   introduced for single-revision reads.
5. **Freshness is per operation, not per call.** An operation's decisions are taken against one
   authority snapshot and one set of item versions, confirmed once at the end of the read stage. A
   long-running operation, such as a waiting tool, re-confirms on change notifications or at a
   bounded interval measured in seconds, not on every poll.

## The pipeline

```text
request ─► 1 resolve ─► 2 authorize ─► 3 fetch ─► 4 assemble ─► 5 confirm ─► response
           operation    decide what    plain      typed DTO,    one staleItems
           context      may be seen,   fetches    facets,       statement for the
           (identity,   once, from     by id,     sizes         whole operation;
           selection,   the snapshot   batched                  stale → rerun 2–3
           project,     and cached     per type                 or fall back
           Application) facts
```

- **1 Resolve.** Build an `OperationContext` from the existing operation types:
  `OperationAuthority.Admission`, `InformationContext`, `KnowledgeReadScope`, the run's
  `UsageAttribution` owner, `DefinitionResolver.Caller` and `ws/Asking`. Load each project and
  Application once: `ProjectRecord`, `ApplicationPolicy.Source` with its boundary, root and
  directories, and the member role. These are typed values; nothing in the context is a raw map.
- **2 Authorize.** Decide in code against the context and the authority snapshot: tool assignment,
  scope admission, readability, log visibility. Readability is decided per document from its
  required-scope set ([ADR 0070](decisions/0070-document-level-readability.md)), with no per-revision
  checks or closure walk at read time. Each decision is memoised in the context for the
  operation's lifetime, so a second asker gets the same answer without recomputing it.
- **3 Fetch.** Plain object fetches by id for what stage 2 admitted, from the item-scoped object
  caches; misses are batched as one `WHERE id = ANY(?)` per type. Lists take candidate ids from
  stage 2, then page and fetch only the page.
- **4 Assemble.** Facets, sizes and the response DTO.
- **5 Confirm.** One item-version confirmation covering every item stages 2–3 read. A stale item is
  invalidated alone and the operation reruns stages 2–3 once, then falls back to the current
  authorizing SQL.

When stage 2 cannot decide (an own-write transaction, no snapshot, a fact budget exceeded), the
operation uses the current authorizing statement for that read, optimised as in principle 4.

## Operation context

- **Lifetime.** Created at the operation entry point (`JobRuntime` tool dispatch,
  `ApplicationToolRegistry.LiveTool.run`, `ws/FrameRouter` handlers, `ProjectRelayPorts.publish`)
  and passed explicitly as a typed parameter, not through a thread-local. A tool's waiting loop
  keeps its context and re-confirms on notification rather than rebuilding it.
- **Contents.** Only values the operation was authorized for. The context is never shared between
  operations or callers; the shared caches remain the cross-operation layer.
- **Bound.** The context holds references to values the caches already hold, plus a bounded memo of
  decisions. It adds no database capacity and no new cache line.
- **Write paths.** Mutations and admissions inside write transactions keep their own checks; they
  may read context values but re-verify what their transaction must lock.

## Repository changes this implies

- APIs gain variants that take the object instead of its id, for example `allowsUsing` taking the
  binding already fetched rather than re-fetching `live` per grant (pattern 5).
- Loops hoist invariant reads: the hosted tool list is read once per `snapshot` (pattern 1).
- Topic registration happens once per topic per process, remembered in memory and re-registered
  only after a notification that the topic changed (pattern 6).
- Name-to-id and name-to-record lookups move to the context or to an item-scoped project cache
  (pattern 2).

## Slices

1. **Pure repetition removal, no new mechanism.** Hoist the hosted tool list out of the per-tool
   loop; pass the fetched binding into `allowsUsing`; register each topic once per process; check
   the session once per socket request. Each change is local and is proven by a statement-count
   test (before/after counts for one operation).
2. **Operation context for agent tool calls.** `JobRuntime` and `ApplicationToolRegistry` share one
   context per tool call: project, Application source, role, admitted authority, tool entry. Covers
   patterns 2, 4 and 5.
3. **Relay waits and publishes.** The waiting tool keeps its context and re-confirms on change
   notifications or every few seconds; publish checks once (patterns 3 and 6).
4. **List reads through the pipeline.** Catalogue pages take candidate ids from stage 2 and fetch
   the page with plain fetches, after the object caches exist.

## Verification

- **Statement budgets.** Each slice adds a test that runs one operation and asserts the number of
  statements by kind, as `JdbcInformationCatalogueReadBudgetTest` does today, so repetition cannot
  return silently.
- **Behaviour.** Existing authorization tests stay; any decision moved to code gets a differential
  database test against its SQL, as for readability.
- **Profiling.** A JFR recording with `plowshare.JdbcExecute` enabled (SQL hash and Java stack per
  statement) is taken on BETA before and after each slice. This is how slice 1's targets are
  confirmed and ranked. The recording must use a per-process file name (`%t`), because a second JVM
  start in the same container can overwrite a fixed name.
- **PROD.** The same `pg_stat_statements` delta used to rank these patterns is repeated after each
  deployment.

## Open questions

- **Re-confirm interval for long waits.** How long a waiting tool may run on its first decision before
  re-confirming, beyond change notifications.
- **Context across sub-agents.** Whether a delegated sub-agent operation inherits its parent's
  context or resolves its own; resolving its own is the safe default.
- **Project cache.** Whether projects become an item-scoped line (`project:<id>`), which needs a
  version trigger on `projects`.
