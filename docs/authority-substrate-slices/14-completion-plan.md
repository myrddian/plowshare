# Slice 14 completion plan: Global admission and preparation embeddings

Status: agreed planning direction, 18 October 2026. Documentation only. This
document does not authorize implementation, migration execution or deployment.
The implemented subset remains at `007c83280e44ff197e38a454dc82b997e876ef07`.

This is the bounded follow-on to [slice 14](14-legacy-cutover-and-integrated-proof.md).
It supersedes the original [cutover specification](14-cutover-specification.md)
only for the planned explicit destination and document-embedding work below.
Historical evidence and current refusals remain accurate until implementation.

## Completion outcome

Demonstrate one supported path: an authenticated human with scoped Global ADMIN
authority admits a document into Global account custody; bounded SYSTEM workers
prepare it using its existing processing allowance; authenticated human and
service readers read it under current root policy. Creator access loss does not
stop valid SYSTEM work, while withdrawal, invalid operation authority and exhausted
allowance stop further effects. Private source material and processing logs remain
protected.

Completing this path and the retained slice 14 migration/negative/recovery gates
finishes the bounded slice 14 outcome. It does not enable every previously fenced
consumer or establish unrestricted platform-wide cutover. Existing compatibility
adapters remain until their specific reader/writer and revocation/commit evidence
allows removal.

## 1. Explicit Global account destination

Extend the existing information operation contract to identify an account
knowledge destination separately from the authenticated actor. The planned wire
shape is `scope: {kind: "account", account: "00000000-0000-0000-0000-000000000004"}`.
The account GUID resolves through the identity registry to Global's fixed knowledge
scope, `00000000-0000-0000-0000-000000000005`. Neither is a project identifier or a
credential. This initial extension supports Global as the additional destination;
it does not enable arbitrary cross-account knowledge access.

- Keep existing Personal, Project, Shared and omitted-selection behavior. Shared
  remains a read selection, and a project named `global` remains an ordinary
  project. Reject malformed/mixed selectors and unsupported account targets;
  the account target does not accept a project or `includeShared` option.
- Authenticate the actual human. Require their current ADMIN binding with WRITE
  on the resolved Global scope, using the common authority substrate. Recheck and
  lock supporting authority in the repository's mutation transaction. A role
  label, creator equality, client label or Global UUID comparison cannot authorize
  a write. Use normal scoped RBAC helpers; add no role, ACL editor or SYSTEM login.
- Persist Global custody and explicit home independently of the actor. Keep the
  authenticated actor as resource/revision `created_by` under existing provenance
  rules. Do not rewrite existing creator evidence or impersonate Global.
- Reuse current root-policy initialization for Global-held knowledge and existing
  GLOBAL_READ eligibility. Home selection does not override privacy, retained
  inputs, reference restrictions or log policy. Ordinary Global conversation/run
  creation remains refused; SYSTEM preparation retains its declared purpose.
- Carry the target through existing upload, status/read, retry/allowance and
  content/metadata maintenance paths needed by the supported installation flow.
  Extend destination admission and existing-resource Java/SQL checks together;
  a parser-only change is insufficient. Unsupported operation/target combinations
  must refuse explicitly before effects, including typed/type-registry operations
  whose destination has not been supported and verified.

Use the existing information upload operation for the initial Markdown/manual
path. Extend its owning shared schemas, validation and affected SDK/client
bindings, regenerating derived contracts through their owning scripts. Update the
manual installer to select the destination explicitly and include it in durable
journal identity/fingerprints. Add no new operation or endpoint, general transfer
API, new Global type registry or SDK/client feature-parity requirement. Existing
multipart or other consumers must not silently reinterpret an unsupported target
as Personal; support beyond the selected acceptance path is not implied.

This is an explicitly planned public-contract extension despite using existing
operation names. The prior implementation's restriction against adding missing
capabilities is not evidence that this extension is already implemented or that
its implementation is now authorized.

Install Global-held chapters with new destination-bound admission receipts.
Existing Personal/project-held manuals and their journals retain their custody
and history. A mismatched destination must refuse journal reuse; no automatic
move, deletion, SQL import/adoption or replay of uncertain effects is included.

## 2. Embedding uses the existing document-processing budget

The information pipeline already captures a configured allowance on admission and
retains `allowance_total`/`allowance_spent`. Chat preparation uses that source.
Chunk and summary embeddings currently carry accounting attribution without the
required claimed allowance and live authorization. Connect those calls to the
existing source rather than adding an embedding wallet or separate document quota.

- The document's retained processing allowance covers all its preparation
  inference: chat, chunk embeddings and summary embeddings. Count actual dispatcher
  requests, not vectors or input rows. Each dual-model/active-or-staging model
  request consumes a claim; reusing an existing compatible vector consumes none.
- Supply a typed internal embedding authorization under bounded SYSTEM document
  preparation. Bind revision, generation, lease, model, exact input batch and
  permitted output targets. Extend the existing chat-only SYSTEM claim carriage
  appropriately; accounting attribution and tokenizer/time limits are insufficient.
- Claim atomically before dispatch using the existing revision spending and
  inference-admission repositories. Retain a stable budget-source identity and
  unique ordinal across stages, workers, retries and restart. Never create a fresh
  allowance per batch, worker, resumed lease or model lane. Any required schema
  changes use a new Flyway migration and reuse the existing evidence stores.
- Recheck SYSTEM authority and operation/source validity after queueing and before
  publishing vectors. Generation/lease changes, withdrawn inputs and exhausted
  allowances refuse further work. Creator membership/token loss alone does not
  invalidate otherwise valid SYSTEM preparation.
- Retain incurred spending, accounting and completed checkpoints after failures.
  Known receipts are reconciled without another dispatch; uncertain delivery does
  not authorize an automatic repeat. An explicit supported retry must preserve
  the original source and cannot silently refund or replace it.

Both configured legacy and dual embedding implementations must preserve this
boundary when used for document preparation. Reuse existing dispatcher,
InferenceAdmissions, SYSTEM validity fences, batching, vector validation and
publication checkpoints. Keep domain contracts typed and SQL in specialist
repositories; no new provider runtime, billing API or monetary reservation ledger.

Counting embeddings changes the effective reach of existing ingest allowances:
the same configured total may leave fewer chat calls and may exhaust earlier.
Preserve visible progress and the existing explicit allowance-increase/retry path.
Do not automatically increase limits, reset spending or change historical costs.

## Deferred budget gaps

| Consumer | Missing contract | Treatment after bounded slice 14 completion |
| --- | --- | --- |
| Semantic query embedding | Configured per-search operation allowance and bounded read-service authorization | Remains fenced without supported evidence. Acceptance uses existing non-inference read/list operations; no semantic-search availability claim. |
| Independent background embedding repair/rebuild | Explicit maintenance allowance and retained repair-operation binding | Remains deferred. Embedding batches performed under an admitted document-preparation lease use that document's source; this does not authorize the independent maintenance worker. |
| Nested/model-triggered or caller-conversation orchestration | Retained parent-budget sharing/allocation and launch semantics | Existing refusal remains. No subdivision/reservation API or replacement allowance. |
| Transient, non-conversation child continuation | Durable allowance identity/recovery after approval or restart | Existing refusal remains. No replacement live Budget object treated as the original source. |

These gaps do not block the bounded Global document path. They still prevent a
claim that all affected platform consumers are available or fully cut over.

## Verification and acceptance

1. Extend mocked boundary tests for strict destination parsing, scoped human ADMIN
   writes, rejected service/non-admin/foreign targets, actor-versus-custody
   provenance and unchanged ordinary Global execution refusal. Verify budget and
   validity denial prevents any provider call or output publication.
2. Run selected PostgreSQL tests for destination/grant locks and revocation races,
   root/privacy/input SQL, provenance, atomic spend and claim identity, concurrent
   last-call exhaustion, retained receipts and restart without duplicate spending
   or effects. State that SQL/transaction justification and select tagged tests
   with `-PfullDb`; do not enable the full database suite by default.
3. Through the supported public upload/installer path, admit a Global-held manual
   document as human ADMIN. Use configured preparation allowance for chat and
   both applicable embedding lanes, then verify human/service read/list without
   exposing private sources, receipts or SYSTEM logs. Include existing-resource
   updates and refusal of destination-mismatched journal/request reuse.
4. Prove valid SYSTEM continuation after creator access removal, and safe refusal
   after document/input withdrawal, operation invalidation or exhaustion. Reopen
   retained state, reuse completed checkpoints and reconcile receipts; do not
   replay an uncertain provider submission to demonstrate recovery.
5. Retain the existing synthetic legacy classification and disk-preservation
   rehearsal. Run relevant formatting, root checks and shared-contract generation/
   verification for changed bindings. Record actual results separately from this
   plan; historical test counts are not completion evidence for the new behavior.

No production tenant inventory, data move, credential issuance, deployment,
backup restoration or old-binary rollback is authorized. Preserve existing
grants, root policies, provenance, receipts, budgets and historical costs. The
remaining curation revocation/commit and compatibility-removal gates stay explicit;
this plan does not certify them through the Global document acceptance path.
