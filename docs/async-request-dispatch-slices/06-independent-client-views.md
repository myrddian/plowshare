# Slice 06 Independent client views

Status: source implemented; verification and rollout are tracked in the
[validation record](../async-request-dispatch-validation.md).
This implements the view work in the [umbrella plan](../async-request-dispatch-plan.md).
Prerequisites are the server completion pipeline in slice 03 and SDK completion
semantics in slice 05. Resource ordering from slice 04 governs conflicting work.
The execution design is internal to Plowshare; adapted Ciotola/Pasta ideas add no
external dependency or compatibility adapter to clients.

## Outcome and scope

A slow or failed schedule-file read must not prevent available schedules,
triggers or firings from appearing. Each independently meaningful collection owns
its loading state, error, last confirmed rows and response revision. Information
lists must continue rendering independently of facets. Connection and selection
changes fence every completion, including failures and cleanup callbacks.

Console event correlation, request limits and transport terminal transitions
belong to slice 05. Bounded concurrent stdio belongs to [slice 07](07-concurrent-mcp.md).
This slice changes view orchestration and audits causal client workflows; it does
not create another transport, local durable runtime or generic request coalescer.

## Source assessment

| Owner | Verified behavior and implementation target |
| --- | --- |
| [Desktop schedules](../../plowshare-desktop/src/schedules.ts) | `refresh` submits four reads together but commits their results after one `Promise.all`. Split completion ownership while retaining independent schedule/file cursor histories and page validation. |
| [Desktop state](../../plowshare-desktop/src/shared.ts) and [activity renderer](../../plowshare-desktop/src/renderer/activity.ts) | Represent and display collection-specific loading/errors, including old rows retained during refresh. Avoid disabling the whole page merely because unrelated inspection is pending. |
| [Console schedules](../../plowshare-console/src/screens/schedules.ts) | `load` submits list/files together but commits both together. Keep section cursors, render independently and preserve unsettled mutation reconciliation. |
| [Information collection reads](../../plowshare-desktop/src/renderer/information-collection.ts) | Already starts list and facets separately, owns facet rejection and fences stale replies. Preserve this pattern instead of rebuilding it. |
| [Desktop lifecycle/workflows](../../plowshare-desktop/src/client.ts) | Has connection generations, subscribe-then-catch-up, per-conversation history queues, retained job tickets and uncertainty fences. Audit these dependencies before relaxing call sequences. |
| [TUI composition](../../plowshare-tui/src/view/main.ts) | Already sends independent reads concurrently and fences obsolete sockets. Viewer reads intentionally serialize mutations of one view while the key loop remains responsive. |

## Implementation steps

1. Give each Desktop collection an owned read revision and presentation state.
   Start independent requests before awaiting any result. Attach rejection and
   cleanup ownership immediately; detached work must use the existing background
   error owner. A panel renders as soon as its checked reply is usable.
2. Reset only the cursor belonging to a collection whose first-page refresh
   succeeds. A failure retains that collection's rows/cursor and exposes its own
   retry. Obsolete completion cannot clear a newer loading state or error.
3. Apply the same independent completion model to Console schedules/files.
   Represent unavailable file metadata honestly; a pending file page cannot imply
   that a schedule is legacy, Global or outside its actual project.
4. Enable controls using the relevant confirmed snapshot and mutation state.
   Keep current permission checks and bounded-page identity validation before a
   mutation. Do not infer permission from a successful unrelated read.
5. Audit adjacent refresh chains for genuine data dependencies. Parallelize only
   independent finite reads, reuse typed snapshots already held and record a
   freshness/scope reason for repeated reads. Do not add unconditional refreshes.
6. Update view comments and tests to state which work is independent and which
   is intentionally sequenced. There is no blanket `Promise.all` conversion.

## Snapshot and causal invariants

Capture server, account, project/scope, connection generation, view identity and
collection revision before submission. Validate every relevant captured value
before committing success, error or loading cleanup. Logout, close, account
switch and reconnect invalidate retained callbacks before starting new reads.
Transport ID matching alone does not establish that a reply belongs on screen.

An older retained snapshot may remain visible with an explicit stale/loading
state. It must not authorize a mutation after scope or connection replacement.
Use current server authorization and existing version/identity fences; no extra
permission query is justified solely because completion is asynchronous.

Keep these causal sequences explicit:

- Subscribe acknowledgement precedes work relying on those notifications; then
  catch up through durable reads to cover events around the acknowledgement.
- Mutation terminal reply precedes its authoritative reconciliation read. Sending
  a read later on the same socket alone does not establish execution ordering.
- Reads merging the same conversation/view retain a queue or revision protocol;
  different views may progress independently.
- Relay topic selection precedes a log read that needs that selection. Relay
  mutations retain displayed topic/subscription generations and delivery fences.
- Retain a job ticket before submission so early pushes have an owner. A request
  reply carrying `ACCEPTED` still leaves durable job completion outstanding.

Cancellation of a view detaches its observation; it does not cancel server work
or a coalesced cache load needed by another caller. A mutation timeout retains
unknown delivery and its receipt identity. Never replay it to make a panel look
fresh. Console's unsettled controls survive unrelated panel success or failure.

## Acceptance and tests

Extend existing owners with controlled deferred promises and socket fixtures:

- [Desktop schedules tests](../../plowshare-desktop/src/schedules.test.ts): hold
  files indefinitely; schedules/triggers/firings render independently. Refuse one
  panel and retain only its last confirmed state. Reverse same-panel replies and
  prove the newest revision wins without changing another panel's cursor.
- [Schedule controls tests](../../plowshare-desktop/src/renderer/schedule-controls.test.ts):
  controls reflect relevant snapshot readiness, mutation fences and page state.
- [Console schedules tests](../../plowshare-console/src/screens/schedules.test.ts):
  one collection completes while the other fails or remains pending; confirmed
  pause reconciliation alone clears an unsettled control.
- [Information collection tests](../../plowshare-desktop/src/renderer/information-collection.test.ts):
  retain existing list-before-facets, rejection isolation and account/scope/socket
  replacement tests; add shared-server overlap coverage through slice 08.
- [Desktop client tests](../../plowshare-desktop/src/client.test.ts) and
  [TUI composition tests](../../plowshare-tui/src/view/composition.test.ts): delay
  subscription acknowledgement and prove dependent submission waits; deliver a
  push before its reply; switch scope during old successes and failures.

Exercise keyboard escape/navigation while reads remain pending. Use deterministic
gates rather than elapsed-time sleeps. Record time to first useful panel in live
acceptance separately from full refresh time; source fixtures prove ordering and
state ownership, not deployed latency.

## Exit and boundaries

Exit when independent panels publish without waiting for each other, stale
callbacks cannot update the current identity, and conflicting mutations retain
their existing explicit authority and uncertainty behavior. Run owning client
checks and repository `check` during implementation; these tests need no database.
Slice 08 owns the cross-language/live evidence matrix.

CLI remains one selected command per invocation. Integration journal sequencing,
publication/consume/acknowledgement and mutation receipts remain unchanged unless
the audit identifies a concrete public-contract gap. No new polling, server SQL,
SDK escape hatch or cache policy belongs in this view slice.
