# 0049: Negotiated PNG content in existing tool results

Status: implemented in the current worktree following explicit user authorization,
23 October 2026; not deployed. See the [manual](../typed-png-tool-results.md).

The next general platform capability is an admitted screenshot surviving provider
completion and retained invocation reads as typed content. Existing text envelopes,
invocation identities, producer authorization, deterministic result publication and
provider uncertainty phases already provide the execution lifecycle. Keep them.
Relay BLOB delivery is independent infrastructure; no tool-topic migration is
necessary to carry immutable references in the existing TEXT envelope.

Add an opt-in `content/1` result profile and v3 envelope. Shared protocol records
describe ordered text/PNG parts and intended use; image references name existing
image IDs and invocation admission ordinals. Store the typed result in the existing
invocation column and derive its legacy text accessor. Reject unsupported versions,
changed profiles, invalid references, conflicting retries and artifacts in failed
or unknown results. Bound parts, image slots, aggregate text and encoded envelopes.

Reuse the existing invocation-admission claim. Its FK and the result already share
the retained invocation lifetime; a duplicate result-owner claim adds no lifecycle
protection. Complete under the existing invocation lock after one set-based receipt,
alias/length and retained-claim validation. Disk I/O remains outside SQL. No new
ledger, catalogue of images, table, claim kind or migration is needed.

Start producer authoring with Node, deployment bindings and non-hosted Application
catalogues. The SDK validates returned admission evidence and persists it with the
ordered result before publication. A private bounded file adapter implements its
existing journal interface; callers retain exclusive ownership using the existing
SDK lock. Uncertain local saves require reopen; uncertain Relay publication requires
retained read reconciliation. Before READY, restart remains UNKNOWN without
recapturing. Capture spooling and hosted content negotiation are later work.

Add one owner-scoped WebSocket read over the existing invocation reconciliation
service. Generic Relay log reads cannot retrieve an invocation result after broker
expiry; an SDK-only text wrapper cannot establish admission provenance or retain
ordered result semantics. Shared generated read contracts are updated for every
SDK, while other producer SDKs keep their text authoring behavior.

Trajectory and native model observation remain separate. Current string consumers
show an explicit pixels-withheld fallback. They do not claim an image was supplied
to a model, create entry claims or bypass current media/context routing.

Verification includes ordered content and image-only results, legacy text envelope
compatibility, profile mismatches, Node admission integrity, durable journal restart
and no-recapture reconciliation, owner read boundaries, actual PostgreSQL completion
rollback and retained claims, and shared native SDK malformed-content cases.
Verification passed:

- `./gradlew format formatCheck check sdkCheck` completed successfully. The SDK
  gate includes 637 shared DTO cases and Node/Python/.NET/Go WebSocket conformance.
- Focused `-PfullDb` runs of `JdbcArtifactRepositoryTest` (13) and
  `JdbcRelayToolRepositoryTest` (6) passed all 19 tests. They verify the actual
  receipt/alias/claim join, unchanged claim count, completion rollback, conflicting
  results, admission/settlement locking and durable text behavior.
- Default tests cover text/v3 envelope compatibility, ordered and image-only
  content, negotiated profile mismatches, authenticated owner reads, Node journal
  restart and uncertain-save fencing, and result reconciliation without capture or
  publication replay. All SDK readers check exact invocation response identity.
- Earlier migrations and archive/Trajectory implementation files are unchanged.
  Documentation links and `git diff --check` passed.

This is local repository/controlled-fixture verification. It does not establish a
live browser-to-model observation or a deployed feature.
