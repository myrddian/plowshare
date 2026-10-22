# Desktop replicated-state waste: phase 1 implementation plan

Status: implementation plan, 22 October 2026. Verified source baseline
`c9a2cf67` (merged main). Phase 1 only — see **Phase 2** for the structural
change, which is deliberately out of scope here.

## Context

The desktop client replicates server state into one `DesktopState` blob in the
Electron main process and ships the whole blob on every interaction. Measured on
the evaluation VM at `c9a2cf67` against the synthetic protocol fixture:

- A document operation (`information.list` / `facets` / `inventory`) issues
  **13 `structuredClone` calls** of full state per request.
- **98.2% of every reply is state**: an 11.39 KB reply delivering a 0.20 KB
  answer, on a fixture holding two messages.
- Clone cost scales with accumulated session state: 0.01 ms when empty,
  2.21 ms at 526 KB, 18.25 ms at 4.7 MB, 84.58 ms at 24.8 MB. With 13 clones
  per operation that is ~29 ms, ~237 ms and ~1,100 ms respectively.
- Opening one document issues **two sequential server round trips**
  (`document.detail`, then `information.read`) and **four `emit()` broadcasts**,
  two of which exist only to set a `loading` flag.

Comparison across clients: `plowshare-cli` 0 `structuredClone` calls,
`plowshare-tui` 0, `plowshare-console` 2, `plowshare-desktop` 36.

The IPC decode cost previously measured here was fixed by #103 and is no longer
a factor; `bootstrap` round trip is now 1.6 ms end to end.

### Authority

There is no separate spec document. The binding authority for this plan is
[AGENTS.md](../AGENTS.md), specifically:

- "Clients and adapters expose server capabilities; they do not create a second
  durable runtime."
- The query-efficiency rules: fetch-discard-refetch, repeated reads within one
  operation and unconditional refreshes are anti-patterns.

Where this plan and AGENTS.md disagree, AGENTS.md wins.

### What phase 1 is not

Phase 1 removes measurable waste **without changing the architecture**. It does
not move the SDK connection, does not change `DesktopState`'s shape, and does not
touch the IPC action vocabulary. Those are Phase 2, gated on reproducing the
severity reported in real use — current measurements are from a synthetic fixture
with an empty workspace and the large-state figures are extrapolated.

## Global constraints

Binding on every task. A task that cannot satisfy these reports BLOCKED rather
than relaxing them.

1. **No behaviour change.** Every task is a refactor. The app must accept and
   reject exactly the same inputs, render the same output, and surface the same
   errors. Task 3 changes *timing* (concurrency) but not observable results.
2. **No server, protocol, SDK or contract change.** Do not edit
   `plowshare-protocol`, `plowshare-server`, `sdk/**`, or anything under
   `test-support/contracts/`. The desktop is an SDK consumer. A task that
   appears to need a protocol change reports BLOCKED with the exact capability
   gap named.
3. **Do not edit generated files.** `plowshare-desktop/src/request-schemas.ts`
   is generated (`sdk/typescript/scripts/generate-operation-schemas.mjs
   --desktop`). If a change would alter it, regenerate through that script.
4. **Scope is `plowshare-desktop/` only.** Do not refactor `clients/`,
   `plowshare-console/` or the shared SDK, even where this plan's analysis names
   them as problems. Those belong to the separate simplification plan.
5. **Preserve snapshot semantics where they are load-bearing.** Removing a clone
   is safe only where nothing mutates the state between the handler returning and
   Electron serialising it. Any `async` handler that `await`s *after* capturing
   state keeps an explicit snapshot. Task 1 names the audit.
6. **Formatting and tests.** After edits run
   `pnpm --dir plowshare-desktop typecheck` and
   `pnpm --dir plowshare-desktop test`. Both must pass before commit. Do not run
   `./gradlew check -PfullDb`, `dockerSmoke`, or any database test — nothing here
   touches persistence.
7. **One task, one concern.** Do not opportunistically fix other findings noticed
   in passing. Record them in the report; they are not yours to fix.

## Task 1 — Stop deep-cloning the whole state on every property read

**Files:** `plowshare-desktop/src/workspace.ts` (primary),
`plowshare-desktop/src/main.ts` (call sites)

**The actual defect.** `DesktopWorkspace.state` is a **getter**
(`workspace.ts:120`) that deep-copies the entire state every time the property is
read:

```ts
get state(): DesktopState {
  const state = structuredClone(this.control.state);
  if (this.localFileStores) state.localFileStores = structuredClone(this.localFileStores);
  // … up to 6 further structuredClone calls, then a byId dedup pass
}
```

`client.state` is read **75 times** in `main.ts`. Each read is ~7 deep clones plus
a `Map`-based dedup. A property access that reads as free is one of the most
expensive operations in the process, and the cost scales with session state
(measured: 0.01 ms empty, 18.25 ms at 4.7 MB, 84.58 ms at 24.8 MB).

**Correction to an earlier claim.** A previous draft argued the explicit clones
were provably redundant because `main.ts` returns `{ state: client.state }`
"live, no clone" at 38 sites. **That argument is false** — those sites clone via
this getter. There is no control group. The real safety argument is independent
and still holds: Electron structured-clones `ipcMain.handle` return values and
`webContents.send` arguments regardless, so a copy made for the sake of crossing
the boundary is a second copy.

**Change:** make reading state cheap. The implementer chooses the approach and
justifies it in the report. Two candidates:

1. **Memoise per change.** Compute the composite once when underlying state
   changes and serve the same object to subsequent reads, invalidating on
   mutation. Requires identifying every mutation point.
2. **Split the getter.** Keep a cloning `snapshot()` for the few callers that
   genuinely need an isolated copy, and expose a non-cloning accessor for the
   many that only read fields (`identityOf(state)`, `state.connected`,
   `state.mode`). Most of the 75 call sites only read.

**Required audit.** Classify all 75 `client.state` reads in `main.ts` as
read-only (needs no copy) or escaping (the object is returned over IPC, retained,
or mutated). Report the counts. Reads that only inspect fields must stop paying
for a deep copy.

**Constraint:** `workspace.ts:121-133` builds a derived composite and then mutates
it (`state.localFileStores = …`). Whatever approach is chosen must not mutate
`this.control.state`. That base copy is load-bearing unless the composite is built
some other way.

**Deferred to a follow-on, explicitly not this task:** the 19
`structuredClone(this.state)` expressions in `client.ts` return values. They are
real but secondary to the getter, and Task 2 deletes several of them outright.

**Verification:**
- `pnpm --dir plowshare-desktop typecheck`
- `pnpm --dir plowshare-desktop test`
- Report clone count per `client.state` read before and after, and the
  read-only/escaping split across the 75 sites.

## Task 2 — Stop returning full state alongside a payload

**Files:** `plowshare-desktop/src/client.ts`, `plowshare-desktop/src/main.ts`,
and the renderer sites that consume `reply.state`.

**Why:** Handlers that return a domain payload (`information`, `usage`, `proc`,
`relay`) also return the entire state. The renderer already receives state through
`window.plowshare.subscribe(...)`. Measured: the state is 98.2% of such a reply.

**Change:** For each handler that returns both a payload and `state`, drop the
`state` field.

**Required audit — this determines the task's correctness.** `reply.state` is
consumed at 28 renderer sites (`plowshare-desktop/src/renderer/*.ts`), the
majority as `update(reply.state)`. For every one of those sites:

- Confirm the subscription delivers the same update. Where it does, remove the
  `reply.state` use.
- Where a site depends on reading state *synchronously with the reply* and the
  subscription would arrive later, that handler keeps returning state. Record the
  site and the reason.

`bootstrap` is explicitly **excluded** — it legitimately returns state as its
payload and must keep doing so.

**Verification:**
- `pnpm --dir plowshare-desktop typecheck`
- `pnpm --dir plowshare-desktop test`
- Report: handlers changed, renderer sites changed, sites kept with reasons.

## Task 3 — Fix the document-open path

**Files:** `plowshare-desktop/src/library.ts`

**Why:** `LibraryClient.document(id)` awaits `document.detail`, then awaits
`sourceText(id)` which issues `information.read`. The second call does not consume
the first's data — only a guard checks the reply names the right document. The
transport multiplexes concurrent requests (`sdk/typescript/src/binding/connection.ts`,
covered by `binding/concurrent.test.ts`), so the serialisation is purely a
client-side choice. Separately, `read()` calls `this.emit()` when setting
`loading = true` and again in its `finally`, so one document open costs four
full-state broadcasts, two of them only to show a spinner.

**Change:**

1. Issue `document.detail` and the `information.read` of `sourceText` concurrently.
   Preserve every existing guard: the epoch/`generation` check, the
   `row.documentId !== id` check, and the `v.document.id !== id` check. If a guard
   must move to after both resolve, it still runs and still produces the same
   error. **If concurrency cannot preserve a guard's meaning, keep that read
   sequential and say so in the report** — correctness outranks the optimisation.
2. Remove the `emit()` that only announces `loading = true`, or make it
   conditional so it does not fire when no observer renders a loading state.
   The completion `emit()` in `finally` stays.

**Constraint:** `read()` is shared by other callers in `library.ts` (6 `emit()`
call sites total). A change to `read()` affects all of them. Either make the change
safe for every caller or scope it to the document path. State which you chose.

**Verification:**
- `pnpm --dir plowshare-desktop typecheck`
- `pnpm --dir plowshare-desktop test`
- Report: the number of server round trips and `emit()` calls per document open,
  before and after.

## Phase 2 — not in this plan

Recorded so the phase-1 work aims at the right destination, **not** to be executed
here.

The target is that the renderer holds its own SDK connection — as
`plowshare-console` already does — authenticated by a short-lived ticket minted by
main (`sdk/typescript/src/binding/auth.ts`, `ticket()` → `POST /v1/auth/ticket`,
consumed by `openSocket`). Main keeps only genuinely privileged work: credentials,
FileStores, MCP stdio hosting, Git/Personal sync, window management. The MCP split
is already drawn correctly and is the model: hosting in main, editor UI in the
renderer.

Phase 2 is gated on:

1. Reproducing the reported severity against real data — the live evaluation rig
   (`plowshare-overlay`, `deploy/evaluation/live.yml`) or a fixture with realistic
   conversation volume. All large-state figures here are extrapolated.
2. Phase 1 landing and being re-measured, so Phase 2's scope is sized against what
   remains rather than against today's numbers.

## Verification of the whole phase

After all three tasks:

- `pnpm --dir plowshare-desktop typecheck` and `pnpm --dir plowshare-desktop test`
- `./gradlew :plowshare-desktop:desktopTest` if available without Docker
- Re-run the profiling harness on the evaluation VM and compare against the
  baseline recorded in **Context** above: 13 clones, 11.39 KB reply / 0.20 KB
  payload, 2 serial round trips, 4 emits per document open.

A phase that does not reduce the clone count has not done its job, regardless of
whether the tests pass.
