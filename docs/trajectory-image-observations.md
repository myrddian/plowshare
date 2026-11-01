# Image observations in the active loop and Trajectory

This describes implemented harness support, which supports still PNG, JPEG and WebP for typed
tool observations. A model/backend may accept additional formats without this
admission and replay path supporting them. The
[model/backend/harness capability map](multimodal-first-slice-plan.md#model-backend-and-harness-capability-projection)
separates model, backend and implemented harness capabilities.
[Decision 0051](decisions/0051-typed-still-image-formats.md) extends the original
PNG slice with retained validated MIME; V228 backfills existing references as PNG.

A negotiated Node `content/1` result can supply an admitted still image to the active
agent's next model request. This extends the [typed producer path](typed-png-tool-results.md)
and reuses its invocation, admission receipt, image ID and immutable disk object.
The default text tool path and existing upload API remain unchanged.

## Delivery and history

The agent records a content-capable tool's call durably before executing it.
`AgentTool.Result` carries the ordinary text projection and optional typed
`ToolObservation`. The latter records the producing invocation UUID, original
ordered `ToolContent.Body`, and effective body after hooks. There is no JSON/prose
sniffing. Scripts and `tool_invocation_read` remain on their explicit text path.
A producer completing asynchronously can still be inspected through the existing
owner-scoped invocation read; that read does not itself attach pixels.

Unchanged successful post-hooks preserve the original observation. Any text
rewrite or withheld result excludes its pixels; the original body remains audit
evidence. Hooks do not gain an image-substitution protocol. `OUTPUT` references
remain descriptors; `OBSERVATION` and `BOTH` may supply native image input.

Each tool reply keeps its original `tool_call_id` and text role. After all tool
replies in the batch, the harness adds a derived user message containing the
image references in result/part order. It identifies the source entry and image
ID. This uses the existing user-image serializer; it does not invent a tool-image
wire format. Reconstruction derives the same message from the retained entries,
without adding another durable execution record or rerunning capture.

`Content.StoredImage` holds only a conversation, entry ordinal, admission slot
and image ID. Dispatch materializes authorized references as transient
`Content.Image` data URIs. Neither the entry nor the loop's standing history holds
base64. Context snapshots expose image IDs with the existing omitted-bytes marker.

## Independent ownership and retention

Migration V227 adds optional typed entry metadata and
`trajectory_image_references`. Entry insertion, reference insertion and independent
`trajectory-entry` blob-claim acquisition share the existing short append
transaction. The source invocation must be completed and match the entry's project,
owner, retained accounting run/turn and exact tool-call ID. The existing run-ownership
snapshot validates that link; job provenance remains separate and may change on
resumption. Its retained body must equal the original body.
Every image must match an existing admission slot, image alias, full digest, byte
length and invocation claim. The blob repository locks/checks READY state when
acquiring the entry claim. No file access occurs in that transaction.

The entry references have no invocation foreign key. Removing the invocation or
pruning Relay cannot erase the entry's claim. Later reads validate the current
conversation scope and use that claim, independently of admission retention.
Disk reads retain BinaryBlobStorage's digest/length and containment checks; a
missing or corrupt object is an explicit failure, with no alternate-path fallback.

A fold changes projection visibility and leaves entry claims intact. Existing
text-reference/drop policies still apply to earlier turns; this does not add typed
`result_read` redemption. Resumption of the producing turn reconstructs the retained
effective observation. Legacy opening image attachments keep their existing
lifecycle and are not migrated by this slice.

Payload ejection clears typed metadata and removes the entry's reference rows and
claims in the same transaction as text ejection. Rollback restores all of them.
Defensive entry-delete cascading releases only that entry's claims. Plowshare's
existing restriction on deleting conversations with entries remains in force.
The last-claim collector decides physical deletion; other image, invocation or
Relay claims continue to retain the same canonical file.

## Native model context admission

Typed observations require a model media profile admitting `image/png`, a
compatible transport and text output, using the existing dispatcher. Before
inference on the selected route, the dispatcher requires a complete `MEASURED`
count, matching pool/model, nonblank verified revision, no gaps, a known context
length and an explicit supported `max_tokens` generation reserve. It checks:

```text
complete prompt tokens + generation reserve <= selected model context length
```

Unknown, estimated, incompatible or over-budget observations fail before the
model request. This is applied to blocking, streaming, folding and explicitly
pinned-pool calls. The ordinary text admission/routing behavior is unchanged.
Counts are reused for the existing inference preflight observation.

The existing OpenAI-compatible counter rejects images by default. The deployment
may set a pool's `counting.image-tokens-included: true` only after verifying that
its configured counter counts the complete native image prompt, including image
encoder/context tokens. This opt-in requires an explicit counting URL and pinned
`counting.revision`. The request sends the same image parts as generation. A MIME
profile, PNG geometry or text tokenizer count cannot establish this capability.
No new deployment backend is selected or enabled by this change.

Transient reconstruction is bounded to 16 image occurrences and 20 MiB of unique
retained image bytes per prompt. Repeated addresses reuse their materialized
bytes. These are protocol/resource bounds, independent of model token admission.
Internal SYSTEM work additionally requires the owning conversation's bound
inference authority; accounting metadata is not permission.

## Verification boundary

Deterministic tests cover Node-style typed invocation output through the real
agent loop/dispatcher to the next request, exact PNG bytes, batch placement,
output/withheld exclusions, replay without another provider call, text behavior,
and rejection when native context cannot be established. Counter HTTP tests
verify opt-in, revision requirements and native request serialization.

Focused PostgreSQL tests cover the V226-to-V227 upgrade, atomic append/claims,
foreign provenance refusal, restart from persisted entries after invocation
removal, locking against invocation deletion, fold retention, ejection rollback,
entry deletion, distinct resumed-job/accounting-run identities, rejection of
non-transactional typed appends, final-claim disk collection and corrupt-history/byte
failures.
These are controlled tests, not verification of a deployed vision backend or an
actual desktop capture tool. Selecting that backend and comparing its image-aware
count against actual context use remains a release acceptance step.
