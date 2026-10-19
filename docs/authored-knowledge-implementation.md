# Authored knowledge implementation plan

Status: first milestone implemented and verified. Baseline main
`a6d642a9c8a12d419e6bb3c680617c7e826cdc3e`, inspected 15 October 2026.
The [proposal](authored-knowledge-design.md) describes the product direction;
[ADR 0027](decisions/0027-authored-information-contracts.md) fixes the initial
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

1. **Contracts and reuse policy.** Land ADR 0027, this plan and the tested pure
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

## Full agent workflow validation — 16 October 2026

`AuthoredKnowledgeEndToEndTest` exercises the running Spring Boot application,
authenticated Java SDK over segmented WebSocket transport, all Flyway migrations
and isolated PostgreSQL/pgvector. Documents, types, tags, relationships, evidence
and reports are created through public operations. SQL only seeds fixture accounts
and independently inspects the retained producer and input ledger.

Chat decisions and embedding vectors are scripted. The agent is the shipped
`interlocutor`, using the production tool binding, dispatcher and durable run
lifecycle. Its script derives subsequent resource/revision/evidence identifiers
and text offsets from actual tool responses. A real pinned local fixture tokenizer,
extraction, derivation, embedding persistence and summary pipeline run normally.
The fixture uses isolated storage and closed fallback inference endpoints; it
never needs a deployed server or live paid model.

| Step | Boundary and verified result |
| --- | --- |
| Ingest | SDK creates and activates a runbook type, uploads service/runbook/decoy sources and applies a billing tag; real content processing reaches ready |
| Reject invalid classification | Metadata revision with a missing required field returns 422 and leaves the current head unchanged; the agent also receives structured invalidity from `types.validate` |
| Classify | Agent `information_write content.revise` creates revision 2 with validated metadata; body hash remains unchanged, extraction is reused, processing is explicit-only and document spending is zero |
| Discover | Agent `information_read documents.list` intersects the type and billing tag and receives one current resource entry, excluding the older revision and unrelated source |
| Follow relationships | Agent creates an unpinned `runbook` relationship from the service, lists its actual connection and resolves the continuing target to the classified revision |
| Consume | Agent reads exact retained text, including a supplementary Unicode character; the evidence operation uses the returned UTF-16 window and exact quotation |
| Retain provenance | Agent records a draft report with the evidence UUID and no caller-supplied inputs; the server inherits the consumed service, original runbook and classified revision, excluding the unconsumed decoy |
| Reconnect | A fresh SDK connection recovers the original classification receipt and reads the retained answer, type/version binding, revising actor, producing log, input ledger and exact evidence |
| Enforce access | Another account cannot open the source or evidence; withdrawing the consumed revision makes the report, evidence and job result return 404 |

The agent completes twelve scripted model steps. These assertions establish the
platform path and durable provenance. They do not measure a live model's ability
to choose a classification, identify relevant sources or produce accurate prose,
and do not establish deployment or a server-restart recovery result.

### Defects found and corrected

- Three component-scanned repositories were declared `final`, preventing Spring's
  class-based persistence exception-translation proxies from starting the server.
  The declarations originated in `86b1930a` (`JdbcApplicationRegistrations`),
  `02d742da` (`JdbcConversationContextRepository`) and `261126ab`
  (`JdbcApplicationIdentityRepository`). They now support those proxies;
  `RepositoryProxyCompatibilityTest` proves typed injection and invocation with
  mocked JDBC and the actual Spring postprocessor.
- `InformationAccess.forRun` treated an account's Personal workspace home as an
  ordinary project information namespace. The mapping was already present in
  `d38d55f4`; the schema milestone retained it. It now maps the exact owning
  Personal identity to the account information collection using existing Personal
  address/ownership validation. Mocked tests cover both Personal address forms,
  foreign-account refusal and unchanged machine project/no-Shared ceilings.
  Explicit project selections retain their project namespace; no stored records
  are migrated by this mapping change.

### Explicit SDK scope and external Application workflow

The Java selector mismatch originated in `f78e4f04`: the helper compared an omitted
project to the returned project, while current server admission can resolve the
omission to Personal. SDKs now require callers to choose the runtime destination
before submission. `agent.run` requires an explicit project unless it continues a
conversation; `conversation.open` and `orchestration.start` require a project.
Read-only `conversation.list/latest` require a project selector, allowing explicit
null only for Global history. Java image upload also requires its destination.
Explicit conversation selection owns its project and cannot be combined with another project selector.
Reply correlation remains strict, and uncertain creations are never replayed.

The acceptance test runs the same journey in two scopes: an explicitly selected
human Personal workspace and a named Application project. In the external case,
the manager deploys a manifest declaring a CONTRIBUTOR service identity through
`ApplicationClient.deploy`, obtains its credential through `application.token.issue`,
and configures/activates the type. The external client uses only that issued
credential and project scope with `includeGlobal:false` for admission, agent work
and reconnect recovery. No service principal or token is manufactured by SQL.

Independent persistence checks assert that the producer belongs to the issued
principal and named project, all three source resources belong to that project,
and the service has no unscoped information or out-of-project conversations.
Missing runtime scope is refused before conversation creation. Existing access,
source-withdrawal, metadata-only reuse and exact evidence/provenance assertions
apply to both journeys. [SDK scope and migration](sdks.md#explicit-execution-scope)
describes the caller-visible change; no stored records are migrated. Human
terminal clients select their advertised Personal identity explicitly. Global
history remains a deliberately selected read-only scope and cannot create work.
The CLI validates execution destination selection before authentication. MCP advertises and enforces
required projects for `agent_run` and `conversation_list`, retaining its frozen
legacy menu as migration evidence rather than changing that historical fixture.

### Reproduce and results

The focused database run selects only the acceptance class: its assertions require
real Application credential issuance, admission/processing transactions, persisted
projections, provenance row mapping and access enforcement after withdrawal.

```sh
./gradlew :plowshare-server:test -PfullDb \
  --tests 'io.aeyer.plowshare.AuthoredKnowledgeEndToEndTest' \
  --no-daemon --no-parallel --max-workers=2
```

Passed: two full-server journeys, zero failures/errors/skips, with 46 seconds of
test execution (1 minute 6 seconds for Gradle). The service case additionally
proves that explicit Global history, Personal information, Shared access and an
ungranted project are refused without creating work outside the configured project.
The prior information-access and Spring proxy regressions remain in the default
mocked suite. Chat and embeddings are scripted in both journeys.

The repository `check` and `formatCheck` gates passed (125 tasks, 1 minute
24 seconds). `sdkCheck` passed (19 tasks, 58 seconds) with
verified Python and an isolated local .NET 8 SDK after replacing a missing local
toolchain path: strict Python lint/format/mypy, Python/.NET/Go contract tests, Go
race tests, and real WebSocket conformance for Node, Python, .NET and Go. The
canonical generator verified 267 operations and 405 shared DTO boundary cases.
These gates exclude PostgreSQL by default. SDK contracts and shared DTO boundary
cases are regenerated by their owning scripts; CLI, MCP and human-client scope behavior are
covered alongside Java, Node, Python, Go and .NET contracts. Integration and
deployment remain separate actions.

## Main integration review — 16 October 2026

Integrated public main `f01431a3ffcfe4f889266f51ee14fce0ac57ed82` into the
implementation branch. The merge preserves the Application service-identity,
accounting-policy and project-folder changes already on main. Main's ADRs 0025
and 0026 remain unchanged; this branch's authored-information and navigation
contracts are now ADRs 0027 and 0028, with their references updated.

All 142 pre-existing Flyway migrations are byte-for-byte unchanged. New versions
143–147 are unique. Regeneration and owning checks agree across the operation
catalogue, native SDK contracts, shared fixtures, client manifest and CLI audit:
267 operations and 405 shared DTO boundary cases.

The review checked admission/namespace ownership, live read authorization,
admin/project-manager policy authority, service project ceilings, receipt
recovery and metadata-only projection reuse. No source conflict required a
behavior change. Runtime SDK starts still require explicit scope; no server
default, production deployment or feature activation is changed by integration.

Verification on the integrated branch:

- `./gradlew check formatCheck --no-daemon --no-parallel --max-workers=2`
  passed: 132 tasks, including 5,466 default server tests with no failures,
  errors or skips. PostgreSQL is excluded from this gate.
- `./gradlew sdkCheck --no-daemon --no-parallel --max-workers=2` passed using
  the documented Python environment and isolated .NET SDK: Python/.NET/Go
  lint/compiler/contracts and real Node/Python/.NET/Go WebSocket conformance.
- Desktop `test:authored-information` and `test:document-navigation` passed
  against isolated protocol fixtures, including validation/conflicts, explicit
  processing, live heads, locked references, saved paths, pinning and revocation.

- The focused PostgreSQL run passed 63 tests with no failures, errors or skips:
  `AuthoredInformationPersistenceTest`, `DocumentNavigationPersistenceTest`,
  `DocumentPoliciesTest` and `AuthoredKnowledgeEndToEndTest`. These require actual
  migrations, SQL access/propagation, transaction rollback, concurrent revision
  fencing and persisted provenance. The acceptance class passed both explicit
  Personal and named-project Application service journeys through the public SDK.

These are integration checks, not deployment or live-model quality evidence.
Chat and embeddings in the full-server fixture remain scripted. A published PR
does not establish production migration or activation of navigation writes.

## Portable exchange extension — 16 October 2026

[ADR 0029](decisions/0029-portable-information-interchange.md) and
[the exchange guide](portable-information.md) define the implemented bounded
export/import extension. Five public WebSocket operations cover capabilities,
original-byte bundles, readable Markdown/sidecars, atomic import and retained
origin reads. The shared catalogue now contains 272 operations and 437 DTO
boundary cases. V148 adds immutable, deletion-redacted imported origins; the
142 migrations on main remain unchanged and versions 143–148 are unique.

Imports require explicit scope, destination/type/reuse mappings and a relationship
choice. They create new local source revisions with foreign claims retained
separately, `explicit_only` processing, zero allowance spent and no authorized
processing stages. Export checks required dependency closure before reading
bytes. Bundles preserve original bytes, exact bindings, history and included
live/pinned relationships. TypeScript/Node helpers expose caller-owned files
without filesystem access; Library provides download and reviewed-plan import.

Final repository validation passed:

```sh
./gradlew format check formatCheck sdkCheck sdkPackageCheck \
  --no-daemon --no-parallel --max-workers=2
```

This completed 163 tasks in 7 minutes 48 seconds. All 5,472 default server tests
passed without failures, errors or skips; this gate excludes PostgreSQL. Native
SDK checks and fresh-installed Node/Python/.NET/Go distributions passed real
WebSocket conformance. Installed Node interchange helpers also preserved exact
BOM, Unicode and CRLF bytes. Mock tests verify hidden required-dependency refusal,
readable representation labels, origin access checks and receipt fingerprints
that survive schema/metadata JSON object-key reordering.

The four focused PostgreSQL suites listed above passed 63 tests after the
interchange extension: both full-server journeys additionally cover original-byte
and schema round trips, explicit new/reused revision mappings, collision and
stale-head refusal, rollback after admissions, stable receipt recovery, origin
redaction, and live versus pinned links after an edited import. These assertions
require actual migrations, constraints and committing transactions.

After the final receipt-fingerprint and portable-validation review, rerunning
only `AuthoredKnowledgeEndToEndTest` with `-PfullDb --tests` passed both journeys
again, with zero failures/errors/skips (1 minute for Gradle). Desktop
`test:information-interchange`, `test:authored-information` and
`test:document-navigation` also passed against isolated protocol fixtures. The
portable smoke checks exact downloads, review before mutation, stable request-ID
recovery and unsafe-plan refusal; its rendered Library panel was inspected.

Portable v1 is bounded to 16 revisions, 64 relationships, 2 MiB original bytes
and 8 MiB JSON. Foreign claims confer no local membership or processing authority;
copies follow explicit destination policies and cannot be remotely revoked.
Chat/embeddings in acceptance tests remain scripted. Source validation does not
establish production deployment, migration or navigation-write activation.

## Portable exchange review corrections — 16 October 2026

Independent review of commit `00a2a11` found four issues. The corrections share
navigation's current/pinned neighbour-disclosure predicate with portable export
and require a readable current relationship source before exporting an optional
edge. Included readable history cannot reveal a hidden live relationship. Exact
permitted pins remain exportable. `PortableInformation` now consumes the existing
`InformationReadAccess` interface.

Every SDK now verifies readable text against its SHA-256 hash over exact UTF-8
bytes. The shared boundary corpus contains 447 cases, including correct and
incorrect hashes for empty text, whitespace, BOM/CRLF, non-BMP and combining
Unicode. The previously reproduced TypeScript and Python mismatches are refused.

Library keeps the scoped in-memory recovery key when resetting its view. Before
submitting, it uses a Library-only typed IPC operation and native save dialog to
write the complete validated recovery record at a user-selected destination.
The private temporary file is synced and atomically renamed before submission
can proceed. Cancellation/write failures submit nothing and retain the draft/key.
After closing Library or restarting, users review that saved file under the
original connection/account/scope and explicitly recover the original request.
No automatic replay is introduced.

Focused verification passed 18 PostgreSQL tests: all navigation persistence cases,
including the new readable-history/hidden-current regression, and both public-SDK
full-server journeys. Withdrawal models unavailable content; exclusion retains its
existing discovery-only semantics. The Electron smoke checks canceled/failed
saves, independently reads the actual saved record before accepting an import,
then destroys/reopens Library and recovers the same request ID. Three default
filesystem tests verify complete private writes, symlink-safe replacement and
failure/invalid-record refusal. The independent reviewer found no further
actionable issues in the corrected diff.

Final `check formatCheck` passed (132 tasks, 4 minutes 51 seconds), including all
5,472 default server tests with no failures/errors/skips. `sdkCheck
sdkPackageCheck` passed (29 tasks, 1 minute 40 seconds), including all 447 shared
boundary cases and fresh-installed Node/Python/.NET/Go WebSocket conformance.
The final Desktop check passed all 231 tests, with recovery-file tests included
in both its Gradle and package entry points.
Portable exchange, authored-information and navigation Electron smokes all passed
on the final build. These are source checks against controlled fixtures; no
deployment or feature activation is included.

## Scope and estimation

The first milestone includes all admission paths, native SDK contracts and the
Library workflow. Projection reuse deliberately accepts only complete compatible
content stages; automatic tags/groups remain unprepared and require explicit
processing. This conservative policy avoids inventing completeness evidence.
Backlinks, arbitrary-field queries and a general form designer remain outside
this milestone. Collaborative editing is outside Plowshare's information-system
scope. The subsequent portable-exchange extension supports external authoring
through explicit new revisions; see [the exchange guide](portable-information.md).
Deployment is a separate operational action.
