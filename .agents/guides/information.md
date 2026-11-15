# Information

Read this for document acquisition, immutable revisions, metadata/tagging,
processing stages, discovery, content reads or processing controls. Read
[AUTH](auth.md) when changing homes, audiences, grants, retained processing
identity, hook authority or result/log disclosure.

## Mental model

The catalogue retains resources and immutable revisions, and a leased stage
pipeline derives structure, metadata and searchable content from them. Discovery
selects authorized retained evidence; it does not create a second processing queue
or turn a listing into permission to read every underlying byte.

## Start in code

- [InformationCatalogue](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationCatalogue.java) owns durable
  catalogue mutations through [InformationCatalogueRepository](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationCatalogueRepository.java).
- [InformationLifecycle](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationLifecycle.java) owns leased
  stage execution; [InformationProcessingRepository](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationProcessingRepository.java)
  owns durable leases and checkpoints.
- [InformationReadAccess](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationReadAccess.java) and
  [InformationWriteGates](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationWriteGates.java) fence use and mutation.
- [InformationDiscoveryService](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationDiscoveryService.java) is the
  cursor discovery boundary; [InformationContentRepository](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationContentRepository.java)
  owns retained content retrieval. Extraction lives in `documents/`; bytes in `blobs/`.

## Rules that are easy to miss

Database leases are the queue. An expired worker resumes committed checkpoints;
local cancellation is a liveness hint, not authority to publish. Preserve the
lease/generation fence inside each checkpoint transaction and independently renew
leases during silent provider work. Manual controls carry their own current
command validity rather than borrowing a background worker lease.

Keep extraction, models and hooks outside short catalogue transactions. Retained
SYSTEM processing has its own bounded purpose and input/output binding; creator
provenance and accounting attribution do not authorize it. Creator access loss
alone does not cancel valid processing, while changed or withdrawn inputs and
invalid operation/output bindings still fence effects.

A resource, a revision, a selection of content and a processing attempt are
different identities. Preserve revision/readiness and metadata-version contracts
when composing list, facets and content reads. A read request must not silently
select a different revision or widen the admitted scope.

## Bounded work

Required contract: each active catalogue, acquisition or processing unit needs a
configured positive finite deadline covering queueing, waits, IO and result delivery.
Short discovery/read/control/acceptance work uses a 2-second default ceiling and
should normally finish much sooner. Extraction, transfer and model stages need
appropriate configured deadlines and leases, not a 2-second limit on the whole job.
Retries/nested work inherit the earlier remaining deadline; renewals are bounded.
After active expiry, cleanup has a separate positive finite configured drain budget.
On expiry stop admission/effects and request owned cancellation; withhold late
checkpoints/publication under current lease, generation and input/output fences.
Keep physical capacity accounted until work actually ends. Preserve committed
checkpoints; uncertain mutations retain original intent/receipts without auto-replay.
Current: [InformationLifecycle](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationLifecycle.java) renews stage ownership;
a renewable lease is not a completion deadline for extraction, IO and publication.

## Legacy traps

Read-only Global knowledge is distinct from an ordinary project named `global`.
Do not infer Global publication from historical null-project execution. Private
processing logs have their own read checks: a catalogue row or source reference
does not grant log access. Legacy and newer operations can have different request
contracts; inspect the owning frame/service rather than copying another DTO.

## Checks

Select lifecycle, authority or discovery tests for the affected behavior:

```sh
./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.information.InformationQueueLifecycleTest' --tests 'io.aeyer.plowshare.server.information.InformationLeaseHeartbeatTest'
./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.information.InformationProcessingAuthorityTest' --tests 'io.aeyer.plowshare.server.information.InformationDiscoveryTest'
```

Use `InformationContentAdmissionTest`, `InformationWriteGatesFenceTest` and
`InformationReadinessTest` for their corresponding boundaries. These are focused
default tests; use full-db only for a necessary SQL, locking or transaction
assertion. The [information manual](../../docs/information-system.md),
[authored information](../../docs/authored-information.md) and
[portable interchange](../../docs/portable-information.md) contain operation details.
