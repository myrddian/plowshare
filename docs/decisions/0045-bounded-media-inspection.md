# 0045: Bounded media inspection separate from document extraction

Date: 2026-10-21

Status: Accepted and implemented as an internal capability. Artifact admission and
the screenshot-to-active-model flow remain separate work.

## Context

The existing image store identifies PNG/JPEG/GIF/WebP from signatures and keeps
bytes plus sidecars on disk under existing scoped image IDs. Signature checks do
not establish complete file validity, dimensions or bounded decoding cost. Future
invocation admission needs those properties without building another image store
or expanding document extraction.

The prior exclusion of a general Tika document extraction pipeline does not rule
out Tika for media detection. File metadata also cannot establish an LLM's image
token cost or supported formats; model profile/transport routing owns those claims.

## Decision

Add `MediaInspection.inspectImage` returning an immutable `ImageDescriptor` with
existing `ImageFormat`, dimensions, byte length and full SHA-256. Keep ownership,
storage IDs, paths, raw Tika metadata and model capability outside this value.

Use pinned `tika-core` for content detection and explicitly select the JDK PNG
reader for pixel validation. Do not install the parser collection or invoke parser
discovery. The optional image metadata module adds no required PNG property beyond
what the PNG reader supplies, so it is deferred until a required format/property
needs it. Document text extraction remains unchanged.

Apply byte, pixel, dimension, chunk and concurrency limits before expensive work.
Validate PNG framing/CRCs and exact IDAT expansion, reject animation and trailing
data, then decode pixels using a transient view without optional metadata chunks.
Ancillary metadata remains opaque but contributes to the original-byte digest.
No resizing, conversion, implicit temporary file or durable write occurs.

Wire one shared capability through `ImagesConfig`, reusing the existing raw image
byte ceiling. Do not change legacy upload behavior to reject other image formats
or treat legacy sidecars as newly validated descriptors. The admission slice will
be its first runtime consumer and must bind exact validated bytes to retained
invocation authority before publishing a successful receipt.

## Consequences and verification

The new capability can run without a data directory and grants no ability to store
or disclose media. Disk storage, sidecar layout and existing IDs are preserved.
An inspection descriptor is not an admission receipt and does not make current
trajectory/tool content multimodal.

Tests exercise real PNG color depths/interlacing, corrupt and truncated streams,
CRC/order/animation refusals, expansion and pixel limits, opaque compressed metadata,
sanitized failures, concurrent capacity, startup binding and legacy image behavior.
No database assertion is needed. See [media inspection](../media-inspection.md) for
the operational contract and remaining ownership/context-admission dependencies.

Verification: 102 focused inspection/image/upload/document tests passed, including
50 new inspection/configuration cases. `./gradlew format` and
`./gradlew formatCheck check` passed with the default database-free selection.
This establishes local validation and compatibility, not live model inference or
invocation admission.
