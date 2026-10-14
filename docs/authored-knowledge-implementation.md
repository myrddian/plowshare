# Authored knowledge implementation plan

Status: first milestone implemented and verified. Baseline main
`a6d642a9c8a12d419e6bb3c680617c7e826cdc3e`, inspected 15 October 2026.
The [proposal](authored-knowledge-design.md) describes the product direction;
[ADR 0025](decisions/0025-authored-information-contracts.md) fixes the initial
contracts. The [usage guide](authored-information.md) describes the implemented
Library and public API workflow. This document records source and test evidence,
not a live deployment.

## Baseline findings and implementation owners

| Owner | Baseline behaviour | Milestone change |
| --- | --- | --- |
| `InformationRequest`, `InformationFrames` | Revise/replace resolve an owner-authorised revision to a name, then use ordinary source admission; no expected-head or type binding exists | Typed DTOs, exact revision/head checks and refusal of typing bypasses |
| `InformationCatalogue`, `InformationCatalogueRepository.AdmissionWrite` | Admission requires positive model allowance and fingerprints the existing content/provenance tuple | Separate metadata-only transition with zero document allowance; preserve legacy fingerprints |
| `JdbcInformationCatalogueRepository.admit` | Locks request and namespace/name, allocates the next ordinal and creates processing stages | Atomically bind typing, policy, source copies, lineage and retained reuse plan |
| `InformationWriteGates` | Pre/post gates authorise a prepared transition, retain approval and recover completed results | Include metadata and exact reuse manifest in the candidate; preserve hooked policy/accounting |
| `JdbcInformationProcessingRepository` | Worker claims pending/expired stages; independent tag/group sweeps can queue work | Require durable processing authority at claim/renew/commit and in every sweep |
| `InformationLifecycle` | Extraction/derivation build revision-owned projections; final content stages run in queue order | Materialise compatible copies without calls, keep absent capability visibly unprepared |
| `DocumentStore` | Each revision has its own document, paragraphs, chunks and chapter/section hierarchy | Specialist projection-copy repository with fresh relational identities and exact logical content |
| `EmbeddingWorkRepository`, `DualEmbeddings`, maintenance | Corpus vectors also have slot/space/source-revision identities and independently repairable work | Copy complete compatible slot outputs; prevent maintenance from automatically processing explicit-only copies |
| Catalogue deletion/finalisation | Deletion redacts owned corpus rows atomically; final reports require five compatible ready stages | Extend redaction and reuse checks, retain the finalisation gate |
| `SchemaDefinition`, `SchemaCodec`, `ToolValue`, `ToolSchemas` | Shared bounded vocabulary and canonical tokens exist, but executable values are tool-oriented and failures generic | Neutral validation facade and structured errors preserving tool/SDK semantics |

The baseline findings explain the changes now implemented by those owners. Code remains authoritative
when integrating later work onto a moving main.

## Projection reuse investigation

The reuse unit is a committed output snapshot for one stage and generation, not
the body hash or an old ready flag. Store a manifest with source/extraction
identity, configuration, logical input digest, complete logical output digest,
row counts and any required embedding space/source-revision identities. Digests
exclude newly remapped UUIDs so equivalent copied content can be verified. A
repository constructs these manifests only from authorised, completed outputs.
Do not deserialize a caller-supplied manifest as proof of readiness.

| Stage | Owned data to preserve | Compatibility proof |
| --- | --- | --- |
| extract | Retained extraction, text hash, converter, title and outline | Same retained source/media and committed extraction configuration/output |
| derive | Document/chapter/section/paragraph/chunk structure and offsets | Matching extraction and derivation configuration, complete relational remapping |
| embed | Chunk vectors and applicable primary/retrieval slot state | Every required chunk/slot has the exact current space and source-revision identity; no partial coverage |
| summarise | Committed paragraph/span/section/chapter/document summaries | Matching complete derivation and configured summariser identity; no paid/private checkpoint substitution |
| summary embed | Document summary vectors and applicable slot state | Exact committed summary identity and full current slot coverage |
| autoTag/tagGroups | Generated tags, source-supported attribution, groups and input-tag identity | Conservatively unprepared in this milestone; explicit selected processing can regenerate it |

`InformationProjectionReuse` provides the pure compatibility planner. The specialist
`JdbcInformationProjectionRepository` captures bounded manifests, copies/remaps
rows and rechecks the approved snapshot inside the owning transaction.
`InformationContentAdmission` independently enforces assignment and head authority.
Pure tests establish policy; the focused PostgreSQL suite establishes row copying,
rollback, immutable constraints and concurrency.

Copy transactions lock embedding slots, the owning resource/revision, current
stage rows, then source projection rows. Worker claims lock revision and stage
together. Embedding publication keeps its slot-before-corpus order. Final copying
rechecks head, generation, configuration, source outputs and embedding targets
against the retained approved manifest. Conflicts roll back without replaying a
mutation or choosing a replacement snapshot.

## Delivery units and completion criteria

The following units are implemented together. Their acceptance criteria remain
the release gate for future modifications and integration onto moving main.

1. **Contracts and reuse policy.** Land ADR 0025, this plan and the tested pure
   content-stage compatibility planner. Fix request/result DTOs, error limits,
   lifecycle transitions, stage selection and negotiated views. Add future wire
   examples to the owning fixture inventory only when operations are registered;
   do not advertise unsupported methods. Completion: decisions are concrete,
   planner tests cover full/partial/stale manifests and scope remains visible.
2. **Shared validation.** Extract neutral immutable schema values and validation
   into the protocol layer with tool/document facades. Add document size checks,
   bounded pointer issues, immutable presentation and document canonical identity.
   Completion: existing tool fixtures/fingerprints and all SDK schema fixtures
   remain unchanged; document invalidity and budget exhaustion have distinct tests.
3. **Registry repository/service.** Implement narrow `InformationContentTypes`
   and `InformationContentTypeRepository` contracts, compose JDBC implementations
   only in configuration, and reuse live project `MANAGER` authority. Add immutable
   versions, active-version uniqueness, control revisions, durable receipts and
   attached-contract access. Completion: mock role/namespace/receipt tests and the
   minimum required migration/activation-concurrency PostgreSQL suites pass.
4. **Projection snapshots and selective queue authority.** Add specialist
   `InformationProjectionRepository` read/copy/recheck methods, remap logical corpus
   rows and preserve dual embedding slots. Persist processing policy and selected
   stage authority. Integrate claim/renew/commit, sweeps, restoration and embedding
   maintenance. Completion: copies have independent row ownership, zero new usage,
   and no unrequested inference survives enqueue/restart/configuration changes.
5. **Account-owned vertical slice.** Compose type activation, typed Markdown
   admission, invalid metadata refusal and `information.content.revise` over the
   actual repository/gate path. Add metadata reads and committed receipt recovery.
   Keep exposure disabled for resources writable through unsupported paths.
   Completion: the acceptance scenario below passes through public contracts,
   including compatible-copy and explicit-repair variants.
6. **All-path enforcement and reports.** Cover ordinary upload/revise/replace,
   multipart intake, source/report tools, URL acquire/refresh, report admission
   and legacy writes. Add lineage/reviser attribution and atomic metadata deletion.
   Completion: no route silently drops typing or schedules automatic work for a
   metadata-only revision; old receipts retain their identity and recovery.
7. **SDKs, filters and Library.** Generate shared operation/native contracts,
   preserve null versus absence, add capability/view negotiation and access-filtered
   type facets. Deliver minimal type management plus author/read/metadata-revise,
   bounded errors, provenance, explicit processing and conflict recovery in Library.
   Completion: SDK conformance and client smoke/authorization fixtures pass.
8. **Release gate.** Re-integrate moving main, audit unpublished Flyway numbers,
   run required format/check/generated-contract/SDK gates and focused persistence
   suites, then enable the capability only after every checklist item below passes.
   A PR or passed mock suite is not deployment proof.

## Vertical slice acceptance scenario

Use an isolated personal account and an `incident-report` type requiring severity,
services and status. Test server-owned records and public SDK operations; do not
give an SDK consumer direct server/database access.

1. Create an immutable draft and activate version 1 under a stable request UUID.
2. Admit Markdown with complete metadata and retain the exact version/profile/hash.
3. Reject missing severity before committing any revision, processing work or spend.
4. Complete ordinary body processing under the existing configured allowance.
5. Change only status against the current revision UUID. Verify unchanged body,
   new revision identity, committed compatible copies, zero new document usage,
   zero allocated document allowance and explicit-only policy.
6. Lose the response, reconcile the original request and recover exactly that
   revision without new hooks, validation, copying or inference. Changed payload
   under the same request UUID conflicts.
7. Repeat with an incomplete or incompatible source projection. Metadata save
   succeeds, readiness stays unprepared and no worker, sweep or embedding repair
   automatically spends. Explicit owner allowance plus selected repair enables
   only the necessary stages and survives restart.
8. Race another edit and a rebuild/delete or target-space change. The stale plan
   conflicts without partial copies or silently selected new outputs.
9. For a report, retain inputs/citations/producer, record the reviser separately,
   keep the copy draft and require five compatible content stages before explicit
   finalisation. Verify live input withdrawal still restricts the new report.

## Verification plan

Pure policy/validation and service tests use immutable fixtures and mocked
repository interfaces. Cover invalid inputs, namespace/role ownership, explicit
null, exact version pinning, changed request identity, stale head/generation and
gate refusal. No PostgreSQL is needed for the pure foundation.

Real database coverage becomes necessary when adding migrations or projection
copy SQL. Select focused `@Tag("full-db")` suites with `-PfullDb --tests` only for
activation uniqueness, row mapping/remapping, transaction rollback, lock races,
receipt concurrency and atomic redaction. State that persistence-specific reason
before running them. Do not run the whole database suite as a confidence check.

Java changes require `./gradlew format` and `./gradlew formatCheck`; broad milestone
handoff requires `./gradlew check`. Shared SDK changes additionally require
`sdkCheck` and their owning generation gates. Current generator locations are
`sdk/typescript/scripts/generate-operation-schemas.mjs`,
`scripts/generate-sdk-contracts.mjs` and `test-support/contracts/build_manifest.py`;
verify task names and regenerate outputs from source before publication. Client
smokes must exercise conflict/validation/processing states and access-filtered
provenance rather than asserting only that a panel opens.

## Activation checklist

- [x] Exact contract/profile errors, sizes and identities have cross-SDK fixtures.
- [x] Registry lifecycle, namespace/manager authority and attached-reader access hold.
- [x] Every admission path enforces typing, expected heads and stable receipt identity.
- [x] Metadata-only commits need no document allowance or document-model call.
- [x] Copied stages have complete compatible owned outputs and retained provenance.
- [x] Claims, renewals, sweeps, restoration and embedding maintenance respect policy.
- [x] Explicit selected repair is durable, bounded, authorised and correctly charged.
- [x] Reports retain input restrictions and truthful producer/reviser lineage.
- [x] Atomic deletion covers metadata and all owned copies without provenance leakage.
- [x] Legacy/typed views and rollback behavior are exercised with actual SDK codecs.
- [x] Library supports author/read/revise, errors, conflicts and explicit processing.
- [x] Required default, focused persistence, SDK and generated-contract gates pass.

## Verification evidence

- `format` and `formatCheck` pass for Java and affected TypeScript sources.
- Protocol/service tests cover schema bounds, private diagnostics, exact inheritance,
  namespace/management authority, published-only model reads, completed receipt
  recovery and strict JSON/multipart command boundaries. Mocked MVC tests verify
  multipart receipt/typing requirements and HTTP 422 field diagnostics before any
  waiting job can be submitted.
- `AuthoredInformationPersistenceTest` passes its seven focused PostgreSQL tests
  with `-PfullDb`: new migrations/immutability, independent owned report copies,
  deletion, dual embedding slots/staging, stale-plan rollback, generation fences,
  concurrent metadata heads and concurrent type activation.
- `sdkCheck` checks Node, Python, Go and .NET against the common DTO inventory and
  validates the Java SDK. Native contract/operation graphs come from the owning
  generators. The inventory contains 357 shared boundary examples.
- `test:authored-information` passes an isolated Electron Library smoke for type
  creation/activation, invalid metadata, typed admission, reads, validation,
  metadata revision, stale-edit recovery and selected processing. It uses a
  synthetic protocol backend; persistence claims come from the PostgreSQL suite.
- Repository-wide `check formatCheck` passes on the final implementation, including
  the multipart tests. The default run excludes the PostgreSQL suite above.

These are source/build checks. No production server was migrated or deployed.

## Scope and estimation

The first milestone includes all admission paths, native SDK contracts and the
Library workflow. Projection reuse deliberately accepts only complete compatible
content stages; automatic tags/groups remain unprepared and require explicit
processing. This conservative policy avoids inventing completeness evidence.
Backlinks, collaboration, arbitrary-field queries, a general form designer and
export/import bundles remain outside this milestone. Deployment is a separate
operational action.
