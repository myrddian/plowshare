# Bounded image admission independent of exact counting

Status: accepted. Supersedes the mandatory complete-count gate in Decisions 0050
and 0052; their storage, authority, transport and counter contracts remain intact.

## Decision

Image eligibility remains the intersection of the selected model profile, backend
and harness formats. Exact context measurement is an optional refinement, rather
than a prerequisite for delivering supported retained images to that model.

Reuse the disk-loaded model profile for a typed `media.image-context` policy.
Defaults reserve 4096 tokens per image occurrence and 1024 framing tokens, with
hard limits of 16 occurrences and 256 KiB of non-image prompt text/declarations.
Meter the complete retained history, tool arguments/results, tool declarations
and response schema with the existing tokenizer; estimated components retain
the existing one-third headroom convention. Add turn/tool-call framing. Require
a known selected context length and an explicit supported output reserve, then
check input reservation plus output reserve against that context length. Refuse
overflow before inference; do not truncate, switch models or retry inference.

These are operational reservations, not a mathematical encoder/tokenizer upper
bound. The backend still enforces its actual token window. Profile settings can
be tuned independently on disk and take effect on restart without a resource
rebuild. Existing artifact byte/pixel limits and access/claim checks still apply.

Only the existing pool `counting.automatic` opt-in contacts the counter. A complete
measured result with matching model/pool, a revision and no gaps can refine the
context decision, including refusing a measured overflow. Missing, failed,
incomplete or unverified counts fall back to the local reservation. Hard image
occurrence and prompt-byte limits apply in either case. The MLX companion remains
optional; its verification gate governs claims of exact counting, not MIME
eligibility or reservation-based image delivery.

Reservations enter the existing preflight channel as `ESTIMATED`, source
`IMAGE_CONTEXT_RESERVATION`, with policy revision and an explicit unmeasured gap.
They never replace consumed provider usage. Explicit context-count operations
continue to report the counter's own coverage; they do not return the reservation
as a measured preview. Compaction is unchanged, so retained history can eventually
exceed the policy and require an existing fold/ejection workflow.

## Scope

Apply this policy to retained-image completion, streaming, folding and pinned
streaming. Preserve legacy text routing/counting and ordinary upload behavior.
Relay, Trajectory persistence, blob claims, native output formats and audio/video
remain unchanged. Socket-prefill image support is still unavailable.

## Validation

Focused tests cover all four retained-image inference paths without a counter,
unknown/unavailable counter fallback, local and measured context overflow, explicit
output limits, hard request limits, per-model overrides, estimated preflight and
unchanged text routing. Generated PNG and JPEG visual fixtures also reached the
configured Gemma MLX model through completion and pinned streaming, respectively,
with no counter configured; the model read random digits present only in the
pixels. The live reservation was 5335 tokens against a discovered 131072-token
context, with a 64-token output limit. This exercises a fixture artifact resolver
and the actual HTTP backend; it does not claim public-client desktop capture or
production deployment. Provider usage remained separate from the reservation.
