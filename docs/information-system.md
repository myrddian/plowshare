# Information system

Plowshare's information API uses authenticated **WebSocket frames only**. The
server owns documents, evidence, lifecycle and access. Existing authentication and
multipart binary upload are transport exceptions; there are no new information
REST endpoints. PostgreSQL and its existing pgvector extension hold the domain.

## Scope and access

Omitted scope means personal plus explicitly shared revisions. Select a project
only when the authenticated account is currently a member; selecting one never
creates membership. `scope: {kind: "project", project: "research", includeShared:
false}` limits discovery to that project. Shared is an explicit revision grant,
not an unowned global corpus. Owner controls require the resource owner; project
membership grants reading, not editing another owner's source.

Filters apply before ranking, counts, title matching and paging. Direct guessed
IDs receive the same refusal as absent IDs. Excluded revisions disappear from
ordinary discovery but remain available for authorized direct evidence reads.
Withdrawal blocks content reads and dependent outputs. Removing a collection link
never deletes evidence or changes its audience.

Every report input constrains its access, including uncited material delivered to
the producing run. The server records those reads and propagates them to parent
logs/orchestrations. Jobs, saved results, trajectories, approvals, inbox delivery
and delayed events recheck current permissions. Document-bound logs are excluded
from general memory/digest export, and their model tools cannot write findings to
the shared board. This is a document dependency policy, not universal data-loss
prevention for every explicitly authorized file/command extension.

## Faceted navigation and tags

`information.list`, `information.facets`, `information.search` and
`information.rank` accept an optional `filter` object. Its fields intersect:
`kind` (`source` or `report`), `tags` (all selected user tags), `autoTag` (all
selected generated tags), `tagGroup` (a category of related tags), `author` (the resource owner's account handle),
`documentAuthor` (identified person, issuing organisation, or owner account fallback),
`when` (a UTC saved-revision year or `YYYY-MM`), `subtype` (format or code
language) and `search` (lexical search over the retained text, name, title and
tags). Corpus and scope remain separate selections. `kind` does not replace
`document_type`/`document_subtype` or a code symbol's declaration kind.

```json
{"scope":{"kind":"personal"},"filter":{"tags":["my project"],"autoTag":["postgresql"],"when":"2026-10"}}
```

`information.facets` returns `total`, `facets` and `hasMore`. Each facet contains
`{value,count}` entries within the selected intersection, limited to its 100 most
frequent values. Counts describe retained revisions, including readable drafts
in the catalogue, rather than unique resources. Filters apply in SQL before
paging, retrieval limits, ranking and coverage. Values and counts use live
permissions and cannot reveal excluded, withdrawn or unreadable information.
The desktop source/report Library exposes these choices and removable selected
filters. Search/rank retain their existing published-report retrieval policy.

User `tags` belong to the resource and therefore survive new revisions. Only its
owner can replace them through `information.tags`, supplying `revision`, a stable
`requestId`, and a string array `tags` (an empty array clears them). This human
control is unavailable to model tools. Lists/status return `tags` and `autoTag`
as separate arrays. Tags are trimmed, lowercased, deduplicated and bounded to
32 tags of 64 printable characters each.

The tool-free `information_tagger` model worker fills revision-specific `autoTag`
from a bounded excerpt of the retained extraction. Generated tags are navigation
suggestions, not evidence. The `autoTag` processing step uses the existing
captured allowance, owned logs, hooks, paid-response checkpoints and generation
fences. It can run after extraction/derivation even if embeddings or summaries
fail; it never edits user tags. Inspect its state with `information.status`,
retry failures with `information.retry`, or explicitly regenerate with
`information.rebuild` and `stage: "autoTag"`. Rebuilding tags preserves summaries
and embeddings. Automatic processing calls the tagging model only when both user
`tags` and revision `autoTag` are empty. A document tagged by its owner skips this
automatic pass; an explicit metadata rebuild remains available regardless of user
tags. Eligibility is checked again after hooks and before charging a model call.

On worker startup and every 60 seconds, a sweep queues up to 100 older untagged
revisions with retained readable text and completed extraction/derivation. This
includes previously skipped tagging steps and retained workspace code snapshots.
The sweep uses each revision's remaining captured processing allowance, without
raising it. Excluded, withdrawn, deleted, unreadable, unprepared and ownerless
revisions are not queued, and failed/blocked/cancelled work requires an explicit
retry. The sweep only queues work; the leased worker reads the retained extraction
and calls the model. It does not fetch the original URL again or rewrite user tags.
Pending work is not queued twice. `auto_tag_generated` records successful attempts
even when the model finds no topical tags, preventing endless paid rescans of an
empty result. Clearing user tags makes an unprocessed revision eligible on the
next sweep. New admissions still queue applicable processing normally.

`documentAuthor` is separate from ownership (`author`). The same `autoTag` model
pass identifies an explicit personal byline first, then a clearly identified
issuing organisation. Unknown, conflicting or questionable attribution falls back
to the resource owner's account. Attribution requires a bounded, exact quote from
the retained content containing the displayed name; model candidates marked
uncertain, unsupported by the content, or based only on a name/URL are discarded.
An exact quote establishes source support, not independent verification of authorship.
Lists/status include `documentAuthor` and `documentAuthorSource` (`person`,
`organisation` or `account`). Ownerless legacy resources may have no fallback name.
The `documentAuthor` facet uses the same resolved value before counts, paging and
retrieval. Blocked/uncommitted attribution is hidden and uses the account fallback.
Attribution is revision-specific, is cleared along with its supporting quote on
deletion, and is regenerated with `stage: "autoTag"`. Existing untagged sources use
the account fallback until their automatic sweep finishes; manually tagged sources
use that fallback until an explicit metadata rebuild if no attribution was stored.

Tag groups are a lightweight many-to-many map: a category contains existing tags,
and a tag can belong to more than one category. For example, `postgresql` and
`sqlite` can belong to `databases`, while `postgresql` also belongs to `software`.
Membership is assigned per document; the selected collection combines these edges
for browsing. Categories are navigation suggestions and carry no evidence status.

New automatic tags and their groups share one `information_tagger` response.
Existing tagged documents use the private, tool-free `information_tag_grouper`
worker, which only classifies the supplied tags. On startup and every 60 seconds,
a sweep queues at most 100 eligible revisions with missing or stale grouping,
using their remaining captured allowance and current permissions. Successful
empty results are recorded to prevent repeat paid calls. Failed or blocked work
requires explicit retry. Tag edits hide stale model groups until reclassification.
Grouping can be rebuilt independently with `stage: "tagGroups"`; report finalisation
still requires the original five content-processing steps.

Lists/status expose `tagGroups` as `{category: [tag, ...]}` and `tagGroupsSource`
(`automatic` or `manual`). Owners override membership through
`information.tags.groups` with `revision`, stable `requestId` and `groups`.
An object replaces model categories; `{}` intentionally removes all groups.
`groups: null` releases the override and resumes automatic grouping. Overrides
belong to the resource and survive new revisions. Removed tags disappear from
memberships; owners cannot introduce new tags through group editing. Categories
are bounded to 16 names of 64 printable characters, with at most 32 existing tags
per category. Model tools cannot write overrides.

`information.facets` also returns `tagGraph: {edges: [{group,tag,count}],hasMore}`,
limited to 1,000 relationships. Counts represent readable revisions in the same
filtered selection as the facets. The graph hides excluded, withdrawn, unreadable
and stale relationships, and records contributing document dependencies for model
reads. Library offers category filtering, related-tag cards and owner group editing
with a control to return to automatic grouping.

## Retention and lifecycle

A source name identifies a resource in its personal or project namespace. Content
creates immutable UUID revisions under that resource; evidence names a revision,
exact UTF-16 extraction offsets and a quote. URLs and paragraph IDs alone cannot
create new evidence. The original bytes, extraction and content hash survive fetch
cache expiry and later revisions. Legacy sources missing original bytes explicitly
lack that capability; they are never reconstructed from truncated passages.

Processing has seven durable steps: `extract`, `derive`, `embed`, `summarise`,
`summary_embed`, `autoTag` and `tagGroups`. Status includes current generation, attempt, lease outcomes,
configuration identity, failure, allowance/spending and committed unit counts.
Readiness is per capability. Stored text or lexical passages can remain usable
while embeddings or summaries are incomplete. `compatible: false` means a ready
projection was built with a different configuration, so semantic retrieval ignores
it until explicit repair. Changed embedding model identity matters even at the
same vector width. Actual counts are not estimated completion percentages.

`information.retry` resumes missing/failed work using the captured allowance;
`information.allowance` raises/changes the owner's allowance without moving below
spending. `information.rebuild` accepts `embed`, `summarise`, `summary_embed`, `autoTag` or `tagGroups`.
Embedding rebuild keeps paid summaries; summary rebuild clears downstream summary
vectors. Source refresh/replacement creates a new revision. Extraction/structure
changes also require a new revision, preserving old evidence coordinates.

URL intake returns a durable acquisition ticket promptly. Inspect it using
`information.status` with `acquisition`; it reveals the retained revision after
success. Failed acquisition is retained and retryable. Processing is independently
queued from retained rows. Expired worker leases resume after restart, with
revision/generation fencing against late writes. Model responses are checkpointed
before post gates, so a denied completion can reuse paid work.

Reports are documents from admission. `information.record.report` retains Markdown,
all inputs, exact evidence citations and optional objectives/findings/reviews/scope
changes. Findings retain `holds`, `weakened`, `refuted` or `not_checked` judgments;
reference validation does not prove factual support. Producer log and orchestration
definition hash come from the actual run. Models cannot forge them or finalise/share.
Readable drafts and final reports appear in the scoped catalogue and desktop Reports
collection, with `report_status` distinguishing their state. Superseded reports stay
out of ordinary discovery. Reading a draft does not finalise it or change its
visibility; all input permissions still apply. Explicit owner finalisation keeps the
revision UUID and supersedes earlier final revisions of the same report resource.
Feedback uses `record.report` with the same name and a parent report in `feedback`;
its content/objectives form a new immutable revision.

Deletion is an explicit owner action on one revision. It atomically removes source
bytes, extraction, stored evidence quotes, model checkpoints and corpus projections,
redacts report details and leaves a restricted tombstone. Dependencies remain
restrictive. All these objects live in PostgreSQL, so transaction failure rolls
back the whole deletion; there is no advertised asynchronous cleanup job or
external blob reference counting. Deleted content cannot be restored. Unshare,
withdrawal, exclusion and deletion commit safety reductions before hooks.

## Hooks

All production intake, acquisition, model processing, evidence/report recording
and lifecycle management reach service-owned `stage.pre`/`stage.post` gates.
The chain is harness, project, then the owner's pinned local hooks. Positive
publication gates a prepared transition before applying its grant. Pre denial
prevents work; post denial preserves private checkpoints and actual spending while
preventing completion/publication. Errors/timeouts deny. Hooks cannot overrule
permissions, missing readiness or generation fences. Committed receipt replay does
not refire transition hooks. Pre-commit evaluations after a crash are not promised
exactly once.

`context.document` exposes operation, resource/revision (nullable before allocation),
generation, stage, attempt and optional source URI. The enclosing context supplies
log/project/origin. Information logs use `submission`, so a hook restricted to
`orchestration` alone will not run for them. Java and TypeScript contract/script
fixtures cover this extension without changing orchestration payload semantics.

## Controls and adapters

The canonical [operation list](../plowshare-protocol/src/main/java/io/aeyer/plowshare/protocol/frames/InformationOperations.java)
is registered by `InformationFrames`; all 34 controls have a neutral TypeScript
socket adapter and Java socket transport. TUI `/information` and the standalone
information command preserve JSON and quoted project names. The headless TypeScript
CLI accepts all 34 controls as `information <operation> '<JSON payload>'` with an
explicit `scope` in the payload. Source/report admission receipts remain pending;
`--wait information ask` waits only for the returned document-answer job.
Java consumers use the public SDK’s typed information client. The MCP `information` tool offers the
source/evidence/report subset and refuses publication/migration before transport.
Internal `information_read`/`information_write` bind the root account and run home,
never model-supplied ownership. The Librarian holds read only; `deep_research`
holds both and retains sources/evidence/draft reports through its existing stages.
The [detailed JavaScript research guide](scripted-research.md) explains its two
retrieval waves, exact evidence, uncited source retention and fetch audit.
The [script authoring reference](scripted-orchestrations.md) documents the runtime
contract for people and agents building another workflow over these tools.

The native desktop library offers personal/project/shared collections, paging,
retained-text windows, selected-quote evidence, input/citation inspection, findings,
ask jobs and owner lifecycle controls. Scope changes clear prior content and ignore
stale replies. The console offers catalog/inventory/acquisition views and the full
operation family. Mutations need a durable UUID `requestId` kept across uncertain
delivery; frame `id` is only correlation. Reconnect reconciles status instead of
resubmitting work. Acquisition retry only changes its durable ticket and does not
need another receipt.

Example payload for `information.acquire`:

```json
{
  "scope": {"kind": "project", "project": "research", "includeShared": false},
  "name": "source-paper",
  "url": "https://example.org/paper",
  "requestId": "c5b6d3e4-4e40-471e-906e-1da8382602d3"
}
```

The fetcher retains its public-network/DNS/redirect restrictions, timeouts and media
validation. It accepts no credentials or private-network exception. Accepted raw
sources are capped at 32 MiB; existing multipart upload is capped at 8 MiB. JSON text
frames must also fit the existing one-Mi-character socket envelope. Reads default
to 8,192 and cap at 32,768 UTF-16 characters; discovery pages cap at 100. Structured
report metadata caps at 512 KiB, with at most 100 records per list and bounded text.
Processing captures the configured ingest model-call allowance at admission;
standalone asks use the configured ask allowance, and delegated research keeps its
existing root budget. There are no automatic bulk rebuild or retention schedules.

## Migrating an existing corpus

V82 quarantines every pre-existing document; `ingested_by` is attribution, not an
owner. V83 preserves IDs and actual surviving projections, marks absent original
bytes/extraction as unavailable and quarantines known legacy information-bearing
jobs/logs without a reliable input ledger. No automatic processing or broad shared
publication occurs. Unknown legacy embedding identity stays semantically
incompatible until its owner explicitly rebuilds it.

Migration controls are disabled by default. Set the exact existing admin handle in
`plowshare.information.migration-account` (environment
`PLOWSHARE_INFORMATION_MIGRATION_ACCOUNT`) for an explicitly designated operator.
Use `migration.list` to page documents and payloads; `migration.adopt` requires
revision, owner, visibility, optional `collectionProject`, reason and stable receipt.
It atomically assigns policy, updates the resource namespace and records its audit.
Names colliding within the destination namespace roll back instead of silently
renaming sources. The operator need not join the destination project; the owner
must have the appropriate selection.

`migration.inspect` requires payload ID and reason and audits its bounded page of
legacy entries. After reviewing every input, `migration.release` requires payload,
owner, destination scope, retained input revision IDs, reason and stable requestId.
It establishes ownership and the input ledger before releasing quarantine. Later
source withdrawal/membership loss makes that released payload unavailable again.
Never infer authority from an old job's agent name or resume an ownerless run with
invented provenance. Disable the operator setting after the audit is complete.

## Verification and scope

Real PostgreSQL tests exercise policy filtering, revisions, exact evidence, report
finalisation/feedback, migration audit, recovery, generation races, retained costs,
embedding identity and revocation through derived records. Authenticated server
WebSocket tests exercise actual adapters; Java socket tests cover correlation,
refusal and disconnect receipts. Console/TUI/desktop tests cover scope and stale
reply handling; native Electron smoke exercises the library, evidence, asks and
publication refusal. Shared hook fixtures use the actual script runtime.

The shipped research workflow now uses the [scripted Aletheia driver](scripted-research.md):
objective decomposition, two evidence waves, author/editor expansion, rebuttal,
adjudication and detailed retained reports. OSINT entities/relations, automatic
monitoring and live factual-quality benchmarking remain separate work.

## Readiness fence

`information.await` accepts scoped `sources`, each containing exactly one revision
UUID or acquisition-ticket UUID, and optional `waitMs` (0..30000). Duplicate
references count once. The reply gives `expected`, `settled`, `ready`, `pending`,
`complete` and per-reference `outcomes`. A fetch success alone is not readiness:
its current revision must be readable and its current-generation extraction ready.
Failed, blocked, cancelled, skipped or unavailable sources settle without readable
text. Downstream summary/embedding work does not delay the text-readiness fence.

An observation deadline returns incomplete progress, not a timeout failure. Observe
the same references again until all have outcomes. Durable catalogue/acquisition
states are authoritative, so missed or duplicate push notifications cannot change
the count. The operation only observes; it never acquires, retries, cancels or
changes a source. Agents access it as `information_read` operation `await` in their
server-bound namespace. Script journals retain the fence's references and outcomes,
and an interrupted observation can be resumed safely.

Research reports keep `research-<run>.md` as their collision-safe resource name.
Their first Markdown heading uses the research question and supplies the display title
when text extraction completes. Feedback retains the same resource name and creates a
new revision. Runs offers **Read retained report** for the main report revision, with
the run's project scope; reading leaves the report as a draft until explicitly finalised.
