# Decision 0044: Filter native media routes in the existing dispatcher

Status: authorized routing slice, 21 October 2026.

## Platform need and scope

The user authorized implementing the routing slice while preserving existing
text routing. Disk-loaded profiles now declare formats, but a declaration alone
cannot establish that a particular pool's serializer accepts those formats.
This belongs to the server's existing model selection boundary, independently of
any browser or protocol adapter. An SDK-owned router cannot enforce pool-specific
transport support, reserved-pool selection or server inference authority.

## Decision

Inject the narrow `ModelMediaProfiles` read contract into `LlmDispatcher`, using
the existing `SamplingProfiles` startup snapshot. Add immutable `MediaRequirements`
to internal chat requests. Old constructors and every request-copy helper preserve
their existing behavior, with no explicit requirements by default.

Ordinary text requests keep the original dispatcher route, load comparison,
tie-breaking, alias/system resolution and accounting order, without consulting
media profiles. Legacy image requests without a media declaration also retain
their existing behavior and all-pool vision boot guard.

Explicit format contracts require a matching model declaration. Image-bearing
requests automatically use strict matching when any serving model has a media
declaration. Include every image from the effective post-filter prompt, regardless
of the caller's declared input set. Reuse `ImageFormat` to verify the data URI's
MIME against its bounded byte signature; full decoding remains artifact
admission's separate responsibility. No image bytes appear in refusal messages.

Before comparing load, remove candidates that fail any of the model declaration,
transport declaration or endpoint vision restriction. Model and transport must
share one supported output combination satisfying the required/permitted outputs;
do not combine separate output modes. OpenAI-compatible serialization declares
the existing four raster image inputs and text output. Other transports default
to text only. Socket-enabled LM Studio conservatively declares text only because
its alternate prefill path cannot carry images.

Use the same predicate for complete, stream, fold, counting, request-aware pool
enumeration and final `streamOn` validation. A reserved incompatible pool refuses;
it does not retarget. Unknown model and unsupported media remain distinct faults.
Input filters may transform prompt text but cannot drop/change an explicit media
contract. Recompute native input requirements from their returned prompt.

For a profiled vision model, the boot guard requires at least one eligible image
route; runtime filtering now excludes blind pools. Preserve the legacy conjunction
when no declaration exists. No implicit model substitution, new pool lane, network
discovery, public operation, SDK contract or persistence change is introduced.

## Limits and verification

The existing scheduler only has a model specifier before a prompt is built. Its
capacity query remains unchanged; a request-aware dispatcher query is available
for admission when a complete prompt exists. The pinned inference check always
enforces the actual request. This slice does not enable media swarm inputs.

Format eligibility does not establish context fit, full file validity or native
audio/video generation. Preserve existing counter unknowns and accounting;
metadata inspection, image context admission and the durable screenshot tool
path remain later slices. Profiles cannot enable an absent response decoder.

Tests use fake transports and latches to prove filtering precedes load selection,
same-model host selection, pinned refusal, actual prompt/type checks, output
combination matching, configuration injection and legacy behavior. No database or
live model is required for these routing assertions.

Verification passed on 21 October 2026: 217 focused tests across `MediaRoutingTest`,
`LlmDispatcherTest`, `FilteredDispatcherTest`, `RequestTest`, `LlmConfigTest` and
`AgentsConfigTest`, followed by `./gradlew formatCheck check` after formatting.
The complete default repository checks passed without the full-db opt-in. The new
coverage includes 12 routing tests and one production configuration wiring test;
this is not live multimodal acceptance evidence.
