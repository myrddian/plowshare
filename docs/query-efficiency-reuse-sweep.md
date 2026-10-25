# Query reuse, cold authority and client refresh

This slice implements the four follow-up areas after the
[Manual and facet slice](query-efficiency-manual-slice.md): cold readability,
event-driven client refresh, the remaining confirmed query waste, and suitable
tracked DTO adoption. Its source baseline is
`fddcd5d64a6e409df1826bde2a87281d33a66c00`. It is local implementation and test
evidence; deployment and representative production latency are separate work.

## Cold selections resolve authority in sets

V178 adds invoker-rights PostgreSQL functions for one operation's live account,
grant and scope relation, then a readable revision relation. Catalogue and Manual
selection SQL use that relation before ordering, pagination and facet aggregation.
The relation stays in PostgreSQL; no application-wide allowed-ID set or permission
lease is created. A coarse home/public filter avoids traversing unrelated private
corpora before the full dependency checks.

The functions preserve account activity, exact Personal ownership, project roles,
service-token issuance and current owner authority, Global inclusion, grant
lineage and expiry, root audience, dependency cycles and the fail-closed closure
limit. Pinned revision and live reference-head semantics remain distinct.
Retained scope ceilings constrain selection as before. Mutation authority remains
separate. V195 extends statement-local reuse to log disclosure and bulk worker
eligibility as described below; lease checkpoints still resolve current authority.

The authority database tests compare the new relation with existing policy for
those cases. They also count PostgreSQL function work across 48 selected roots and
a corpus skewed with 32 unrelated Personal roots. These assertions establish
bounded repeated authority work, not a production latency target.

## Logs and workers share authority within each statement

V195 batches retained log input selections against one `knowledge_read_authority`
relation. Targeted `information_log_readable` calls walk only their own ancestry,
deduplicate exact retained selections and share dependency facts across inputs.
Collection reads use the uncorrelated `information_unreadable_logs(account)`
relation before ordering, pagination and counts, rather than invoking a permission
function per result row. Quarantined or unreadable seeds propagate through the
inverse of all four ancestry edges. Ownership, each retained scope and Global
inclusion flag, privacy, live grant lineage and the per-root dependency limit
remain disclosure fences. Missing accounts and missing dependency facts fail
closed; logs carrying no protected inputs retain their existing behavior.

Bulk processing claims and both metadata sweeps use
`information_system_processing_revisions()`. It resolves intrinsic SYSTEM lineage
and the four required actions once, then checks each revision's registered home
and transitive input validity. Creator access is provenance, not processing
authority. Claim ordering, row locks, `SKIP LOCKED`, generation, explicit-stage
authorization and lease predicates remain in their owning repository statements.
Targeted claims and post-lock checkpoints retain their scalar current-authority
checks: these are deliberate freshness boundaries, not a cross-request cache.

`LogAndWorkerAuthorityDatabaseTest` compares admitted log sets with the shipped
V186 predicate and processing eligibility with the unchanged scalar SYSTEM
predicate. Transaction-local PostgreSQL function counters verify that adding
inputs, log rows and worker candidates does not multiply account/grant checks.
The existing processing persistence suite exercises concurrent claims and lease
withdrawal. These checks establish SQL behavior and work bounds; deployed latency
still requires measurement after release.

On 25 October 2026, standalone query bodies were compared in a bounded read-only
snapshot of the running database, without installing the migration. For the same
65 retained input selections, the scalar predicates admitted all 65 in 1,473.9 ms
and made 11,776 `account_active` calls; the batched query admitted the same 65 in
17.8 ms and added 44 calls. The bulk SYSTEM eligibility relation took 13.3 ms of
database execution in that snapshot. These are individual query measurements,
not end-to-end Desktop timings or proof of a deployed release.

## Desktop refresh follows invalidation

Committed DOCUMENTS/AUTHORITY notifications and listener recovery emit the
fieldless `information.invalidated` push to authenticated sockets. It contains no
resource, project, account, content or permission decision. A dedicated one-slot
hint lane coalesces a blocked socket's hints without displacing replies or
lifecycle events. There is no database account scan or durable replay queue.

The Desktop collection owner replaces its four-second refresh with a 250 ms
first-hint deadline, immediate reconnect/manual refresh, and a 60-second active
view reconciliation timer. Continued hints cannot postpone the first deadline.
Ordinary state emissions do not refetch the collection. Account, server, selected
connection, scope and socket generation fence late responses.

An invalidation immediately hides protected read surfaces. Normal authorized
reads can restore them; refusal or context loss clears retained metadata and
typed registry options. Editing drafts survive a successful refresh within the
same identity. Lists render independently while facets load or fail. Lost hints
recover through reconnect and bounded periodic reconciliation.

Hints in the same pending debounce batch invalidate once; a hint received after
the recovery read starts still fences that read. The periodic reconciliation
does not hide an unchanged snapshot merely because its timer fired. While a
hint requires protected surfaces to be hidden, a separate progress message stays
visible. Reader signatures are reusable only when the selected text window has
finished loading: an interrupted read's loading placeholder cannot satisfy a
later refresh with identical metadata.

## Additional tracked DTOs

| Projection | Durable dependencies | Admission and consistency |
| --- | --- | --- |
| Manual relationship/connection page | DOCUMENTS, AUTHORITY | Complete context, retained ceiling, parent resource, relation, direction, other endpoint and pagination in the key; one parent/edge/neighbor statement |
| Transcript page | TRANSCRIPTS, DOCUMENTS, AUTHORITY | Exact account, log, ordinal/kind/direction/page and historical bound; current log-input authorization on every public call |
| Content-type list, unlocked version and attached contract | DOCUMENTS, AUTHORITY | Namespace/version/revision selection in keys; owning service authorization remains live; locked mutation reads bypass sharing |

Each loader checks a complete SQL byte bound before mapping large content. Oversize
results preserve the ordinary complete output without admission. Empty results
and lookahead rows retain their existing meaning. Transcript cold loads recheck
log-input authority after hydration: a preflight before version capture cannot
admit data under a token captured after a revocation. The coordinator validates
the retained token afterward as well. Warm hits retain the caller's live preflight.

V179 adds a TRANSCRIPTS counter and transactional notifications for entries,
conversation/child metadata, orchestration ancestry and retained or quarantined
information inputs. Its capture function preserves the independent committed
probe and pending-XID fences. Rollback can conservatively change a sequence but
cannot emit a committed hint. Empty DML does not invalidate; TRUNCATE does.

Production now registers 16 projections. The default registration bound increases
from 12 to 16; existing per-projection entry, weight, load and waiter limits remain
explicit configuration. The default resident allowance is 32 MiB per projection,
at most 512 MiB across these registrations before nonresident caller-held DTOs and
separate in-flight/metadata allowances. Cache operations retain concurrent indexes
and the foundation's bounded O(1) recency bookkeeping.

A warm shared hit still performs three version-capture statements and validation
of the actual stored token, in addition to any owning live admission. A cheap
single-row embedding descriptor or live runtime setting therefore stays direct:
adding four probes would increase its work. Operation-local reuse takes priority.

## Remaining owner work and required repeated reads

The historical audit contains 102 owners, including 49 with confirmed waste.
This sweep changes the relevant paths of 47 confirmed owners (including citation
caller composition). The other two, keyed schedule definitions and Relay log
inspection, already received the relevant query/cache fixes in earlier slices.
This is coverage of the named findings, not a claim that every query or subpath in
those owners is optimized.
The following ledgers describe the changed paths, exact query budgets, necessary
freshness reads and remaining subpaths; historical candidates are not silently
reclassified as established problems.

- [Archive, Board, tokens and navigation](query-efficiency-analysis/archive-implementation.md)
- [Information, publication and discovery](query-efficiency-analysis/discovery-implementation.md)
- [Runtime, authority and accounting](query-efficiency-analysis/runtime-implementation.md)

The Relay and hosted-scope changes in this slice are:

| Owner/path | Saved work | Retained boundary |
| --- | --- | --- |
| Relay subscription/topic registration | New rows return their durable projection directly; unchanged configured policies avoid a write | Conflicts still obtain the current locked row; generation and family checks remain |
| Relay outstanding SDK batch retry | One retained-page read instead of a discarded requested page followed by another read | Exact token/boundary, smaller-limit refusal, transport allowance, retention gaps and acknowledgement fences |
| Relay control completion | UPDATE returns its actual receipt result and database completion time | Earlier locked receipt/access/fingerprint checks and transaction rollback |
| Relay delivery transitions | Locked snapshot plus UPDATE RETURNING, without another joined payload/source hydration | Current state, worker, fence, lease, idempotency and unknown-effect reconciliation |
| Hosted worker lookup | Reuse the supplied typed binding; one fresh narrow worker query, or one per 256-binding cohort | Supplied generation must match current durable binding and worker; socket, expiry, credentials and catalogue checks remain live |

Claims, leases, credentials, mutable allowance counters and execution admission do
not enter the shared display cache. For example, conversation updates keep a fresh
post-write allowance projection so a lock wait cannot hide concurrent spending.
Digest grouping keeps sorted locks followed by fresh scope/revision checks.
An INSERT conflict may also require a separate statement to observe the winning
transaction after waiting. These reads have explicit concurrency reasons.

## Application composition and test fixtures

The outgoing integration run exposed a pre-existing startup problem: Spring's
default class-based exception translation could not proxy final specialist
repository implementations. The first reported class, `JdbcScopedGrantRepository`,
acquired that declaration in `b234e1325`. Eight annotated repository types now
permit class proxies, with interface injection retained and no global proxy-mode
override. A focused proxy regression and authenticated outgoing-work tests cover
both construction and the actual application composition.

Old isolated fixture resets now include newer foreign-key dependants through the
existing authority-preserving test reset helper. No production constraint was
relaxed. New cohort fixtures use canonical conversation/delegation constructors
and assert shared allowance ownership. The outgoing no-inference fixture declares
its model identities explicitly; attribution fixtures use an exact test tokenizer
and the current accounting repository contract.

## Verification

The focused PostgreSQL runs now pass **301 distinct cases**, including authority
equivalence/work counts, configured DTO loaders, actual RETURNING/array/recursive
and cohort mapping, per-parent attempt limits, scope isolation, lock/rollback
races, uncertain work, fresh stall marks and transactional notifications. Failed
fixture/setup cases were corrected and rerun; they are not counted as passing
until their final successful run. Commands used the explicit `-PfullDb` flag with
named classes or methods from the three owner ledgers, plus the core
`ProjectionVersionsDatabaseTest` and relevant Relay repository tests.

The shared TypeScript SDK's 918 tests and the focused Desktop's 79 tests pass. The
controlled browser collection fixture passes stale-scope/logout, permission-loss,
coalesced-hint, draft-retention and independent-facet-rendering checks. Shared
native DTO fixtures include the fieldless push and ignored future output fields;
all derived contracts were regenerated through their owning scripts.

The required `format`, `formatCheck`, repository-wide `check` and `sdkCheck` gates
pass. The server default suite contains **6,215 tests with zero failures, errors
or skips**. Native conformance passes for Node, Python, .NET and Go, including
strict Python checks, .NET warnings-as-errors compilation and Go race tests.
The native conformance consumers follow the regenerated notification union.

Mocks establish operation budgets and disclosure composition; the selected
database tests establish the changed SQL/transaction contracts. Neither proves a
deployed speedup or representative production latency. This slice is local; it
has not been deployed or benchmarked against a production corpus.
