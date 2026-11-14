# Operation write fences

An agent tool call is checked by `JobRuntime` before the tool runs and again after it returns.
Both checks run **outside** any write transaction the tool opens:

- The **before-tool** check refuses a call whose run admission or retained operation has already
  lost authority.
- The **after-tool** check is a **disclosure gate, not a mutation guard**. When authority changed
  while the tool ran, it withholds the tool's result from the model. It cannot undo a write that
  has already committed.

A revocation that lands while a write is in flight is caught only by an **in-transaction fence**:
a live authority check that runs inside the write's own transaction, after that transaction's row
locks and before its first durable write. When the fence throws, the whole unit rolls back,
including any idempotency receipt. Inside a write transaction `BoundedVerifiedAdmissions` bypasses
its reuse window and the scoped grant cache reads SQL, so a fence always walks current authority.

The fence contract is `access/OperationFence`. For a run, `JobRuntime.offeredTo` builds one fence:

```java
OperationFence writeFence =
    () -> {
      admittedAuthority.run();
      if (operation != null) operations.require(operation);
    };
```

This repeats the after-tool check's authority (the run admission, then the retained operation when
the run has one). Socket handlers, system work and administration call the overloads without a
fence, which pass `OperationFence.none()`. Their own authorization is unchanged.

## Mutating agent tools

| Tool | Write path and transaction | Fence |
| --- | --- | --- |
| `memory_write` | `BoundMemoryWrites.mutation` inside the content transaction opened by `Archive.file` | In transaction: `memories.lock`, then `require()`, then `requireRecipient()`. `requireRecipient()` runs the origin authority (`operations.requireTargets` for the run's operation) and live scope checks. This predates `OperationFence`, and its origin authority does not run the run-admission check. |
| `relay_publish` | `GrantedRelayPublishing.publish` checks grants, membership, personal ownership and definition before the transaction. `JdbcRelayPublishingRepository.publish` then runs one `UnitOfWork` transaction: receipt insert, broker append, receipt update | `OperationFence` in transaction, after the receipt-row `INSERT … ON CONFLICT` (the unit's serialization point) and before the append or the read of a retained receipt |
| `information_write` `upload` | `InformationCatalogue.admitForSession` → write gate (`InformationWriteGates.executePrepared`) committing transaction → `admit` transaction (joined) → `repository.admit` | `OperationFence` in the admission transaction, after `access.requireWork` and the content-registry `recheck` lock, before `repository.admit` |
| `information_write` `content.revise` | `InformationCatalogue.reviseMetadata` → write gate committing transaction → prepared transition | `OperationFence` in the gate's committing transaction, after the registry `recheck` lock, the `lockResource` lock (`requireWrite`) and `requireReadable`, and before `projections.copy` and the revision event |
| `information_write` `evidence` | `InformationCatalogue.evidenceForSession` → write gate → `evidenceUnchecked` transaction → `repository.recordEvidence` | `OperationFence` first in the recording transaction, which takes no row lock of its own, before `requireReadable` and `recordEvidence` |
| `information_write` `report` | `InformationCatalogue.reportDetailsForSession` → write gate → `reportUnchecked` transaction → `admit` (joined) → `recordReport` | `OperationFence` in the joined `admit` transaction (after the registry `recheck` lock), before the report revision, its dependencies and its details are written |
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
- `information/InformationCatalogueFenceTest`: fence order for revise (gate transaction, lock,
  fence, copy), upload, evidence, and rollback on refusal.
- `information/DocumentNavigationTest`: relationship writes run the fence after both locks and
  before any receipt lookup or write.
- `agents/RelayPublishToolTest`: the run's fence reaches the publication.
