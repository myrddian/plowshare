# Operation write fences

An agent tool call is checked by `JobRuntime` before the tool runs and again after it returns.
Both checks run **outside** any write transaction the tool opens:

- The **before-tool** check refuses a call whose run admission or retained operation has already
  lost authority.
- The **after-tool** check is a **disclosure gate, not a mutation guard**. When authority changed
  while the tool ran, it withholds the tool's result from the model. It cannot undo a write that
  has already committed.

A revocation that lands while a write is in flight is caught only by an **in-transaction fence**,
a live authority check that runs inside every transaction the write opens. In each transaction it
runs after that transaction's row locks and before the transaction writes. When the fence throws,
that transaction rolls back. Units the same call committed earlier are not rolled back, for example
a gate receipt claim or a pipeline log. Inside a write transaction `BoundedVerifiedAdmissions`
bypasses its reuse window and the scoped grant cache reads SQL, so a fence always walks current
authority.

Ungated writes (relay publication, navigation relationships) have one transaction, so the fence
runs before their first durable write. Gated information writes (`InformationWriteGates`) commit
several units, each fenced:

1. The receipt claim, after the claim's request lock.
2. A check immediately before the `document_pipeline` log is opened outside any transaction.
3. The opened-log record.
4. A check immediately before the `stage.pre`/`stage.post` hook chain fires (hooks may call
   models).
5. The `preGate` and `postGate` records, each in its own transaction.
6. The approval.
7. The final transition.

A revoked run therefore fires no hook and opens no new log. A refusal at the claim leaves no
receipt row. A later refusal records the receipt as `blocked`, as any other transition failure
does. The final transition also runs the fence a second time inside the catalogue's own unit, after
that unit's locks. Both runs are in one transaction, so this repeats a walk; it does not widen any
window.

The fence contract is `access/OperationFence`. For a run, `JobRuntime.offeredTo` builds one fence:

```java
OperationFence writeFence =
    () -> {
      admittedAuthority.run();
      if (operation != null) operations.require(operation);
    };
```

This repeats the after-tool check's authority (the run admission, then the retained operation when
the run has one). Like the after-tool block, a run with no admission and no operation gets a fence
that does nothing. A refusal is rethrown as `OperationFence.Withdrawn`, which is deliberately not a
`CallerFault`, so a tool's own "refused" handling cannot catch it. `JobRuntime`'s tool loop maps it
to the same words as the after-tool check: "the result of '…' was withheld because this run's
authority changed while it ran. Any change the tool made has already happened." `tool.post` is
skipped. A script command that hits the fence fails like a script command whose after-tool check
refused: no journal receipt, and no automatic replay.

Socket handlers, system work and administration call the overloads without a fence, which pass
`OperationFence.none()`. Their own authorization is unchanged.

## Replays

For run callers, the fence runs before any idempotency receipt is replayed, on every path:

- The relay receipt read.
- The navigation receipt lookup.
- The gate's completed-receipt return.

A revoked run is refused, which puts authority before replay, and the model sees the withheld
wording, never "refused". The committed receipt row is never rolled back by a fence. An authorized
person can still learn the outcome through socket and administrative paths, which use
`OperationFence.none()` and replay exactly as before.

## Mutating agent tools

| Tool | Write path and transaction | Fence |
| --- | --- | --- |
| `memory_write` | `BoundMemoryWrites.mutation` inside the content transaction opened by `Archive.file` | In transaction: `memories.lock`, then `require()`, then `requireRecipient()`. `requireRecipient()` runs the origin authority (`operations.requireTargets` for the run's operation) and live scope checks. This predates `OperationFence`, and its origin authority does not run the run-admission check. |
| `relay_publish` | `GrantedRelayPublishing.publish` checks grants, membership, personal ownership and definition before the transaction. `JdbcRelayPublishingRepository.publish` then runs one `UnitOfWork` transaction: receipt insert, broker append, receipt update | `OperationFence` in transaction, after the receipt-row `INSERT … ON CONFLICT` (the unit's serialization point) and before the append or the read of a retained receipt |
| `information_write` `upload` | `InformationCatalogue.admitForSession` → write gate (`InformationWriteGates.executePrepared`, all units fenced as above) → final transaction → `admit` (joined) → `repository.admit` | Gate units as above. In the final unit, again after `access.requireWork` and the content-registry `recheck` lock, before `repository.admit`. |
| `information_write` `content.revise` | `InformationCatalogue.reviseMetadata`: the snapshot `projections.prepare` transaction (inside the gate's prepare step) → write gate → final transaction → prepared transition | First in the snapshot-preparation transaction. Gate units as above. In the final unit, again after the registry `recheck` lock, the `lockResource` lock (`requireWrite`) and `requireReadable`, before `projections.copy` and the revision event. |
| `information_write` `evidence` | `InformationCatalogue.evidenceForSession` → write gate (`execute`) → `evidenceUnchecked` transaction → `repository.recordEvidence` | Gate units as above. In the recording transaction, which takes no row lock of its own, first, before `requireReadable` and `recordEvidence`. |
| `information_write` `report` | `InformationCatalogue.reportDetailsForSession` → write gate → `reportUnchecked` transaction → `admit` (joined) → `recordReport` | Gate units as above. In the joined `admit` transaction, after the registry `recheck` lock, before the report revision, its dependencies and its details are written. |
| `information_write` `relationships.create` / `relationships.remove` | `DocumentNavigation.mutate` in one `UnitOfWork` transaction: `lock`, `lockAccount`, live work check, receipt lookup, write, receipt record | `OperationFence` after the two locks and the live work check, before the receipt lookup or the write. A replayed receipt is also refused once the fence throws. |
| `information_write` `acquire` | `InformationAcquisitions.submit` retains a durable ticket in a short transaction (`submitRetained`). A background worker later fetches and admits the source in its own transaction under the ticket's receipt fence and the account's live `access.requireWork` | **Not fenced.** This is asynchronous submission. The run's operation does not reach the worker's admission transaction, so only the JobRuntime before/after checks and the worker's account check apply. Fencing it would mean carrying run authority into the worker, which is a separate change. |
| File `write` / `edit` (and other `FileTools` mutations) | Workspace provider through the run's `ProviderRouter`; no database transaction | JobRuntime before/after checks only |
| `TodoTools.Write` | `TodoBoard.apply` commits the run's own conversation todo rows in a `UnitOfWork` transaction | Not fenced. JobRuntime before/after checks only. |
| `SendMessageTool` | Messaging services (`RelayMessaging` and related classes) in their own transactions | Not fenced. JobRuntime before/after checks only. |
| `BoardTools` (post, open, wake and so on) | Board seat actions (`BoardRunExtras.SeatActions` → `board/`) in their own transactions | Not fenced. JobRuntime before/after checks only. |
| `StudioTools` | `Commands.Port` command submission | Not fenced. JobRuntime before/after checks only. |

Rows marked "JobRuntime before/after checks only" can commit a write after a mid-call revocation.
For those tools, the after-tool check only withholds the result. Any tool that later gains an
`OperationFence` belongs in this table with its transaction owner, its lock and the fence's
position.

## Tests

- `relay/GrantedRelayPublishingTest.revocationBeforeCommitRollsBackPublish`: a throwing fence rolls
  the publication unit back after the receipt insert, and nothing is appended.
- `information/InformationCatalogueFenceTest`: fence order for revise (snapshot preparation, then
  gate transaction, lock, fence, copy), upload, evidence, and rollback on refusal.
- `information/InformationWriteGatesFenceTest`: every gate unit, the log opening and the hook chain
  are fenced. Refusals leave no receipt, log or hook. A completed receipt is not replayed to a
  revoked run but is replayed to `none()` callers.
- `agents/OperationExecutionTest`: a fence refusal inside a tool reaches the model as the withheld
  wording.
- `agents/RelayPublishScriptTest`: the runtime's fence is not `none()`, and it refuses once the
  retained operation is revoked.
- `information/DocumentNavigationTest`: relationship writes run the fence after both locks and
  before any receipt lookup or write.
- `agents/RelayPublishToolTest`: the run's fence reaches the publication, and its refusal propagates
  instead of becoming "Relay publication refused".
