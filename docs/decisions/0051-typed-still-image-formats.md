# 0051: Validated still-image formats through the existing harness

The typed tool admission and Trajectory path previously reconstructed every image
as PNG. Existing uploads and the generic HTTP serializer already recognized more
formats; that did not establish complete harness or backend support.

Extend the existing `MediaInspection` implementation, admission receipts and
Trajectory references for still PNG, JPEG and WebP. Reuse Tika Core byte detection;
add only the pinned TwelveMonkeys WebP ImageIO module for pixel validation. JPEG
uses the Java 21 reader. No Tika parser package, OCR, embedded extraction or document
pipeline is added. MIME comes from detected, validated bytes, never the filename
or caller declaration. Metadata remains in original bytes but is excluded from
the transient validation decoder view.

Preserve byte, pixel, dimension, concurrency and structure limits. JPEG scope is
8-bit grayscale/RGB baseline and progressive images. Reject animation,
multi-picture JPEG, inconsistent framing, trailing files and decoder warnings.
The WebP decoder does not implement uncompressed alpha: validate that plane's
bounded one-byte-per-pixel framing separately and decode color independently.
Compressed alpha is decoded by the library. Neither path changes stored or sent
bytes. This validates admitted media; it neither estimates image context tokens
nor transforms files into other formats.

V228 adds validated MIME to both receipt/reference tables, backfilling historical
rows as PNG and requiring explicit supported values for future inserts. Entry
claims copy MIME alongside existing provenance and remain independent of invocation
retention. Resolve actual MIME before routing, including request-aware pool
inspection; unresolved references cannot acquire an invented PNG requirement.

The existing dispatcher intersects model profiles and transport declarations.
LM Studio's HTTP declaration additionally narrows its generic serializer to its
documented PNG/JPEG/WebP set. Socket-enabled prefill remains text-only. A broader
model declaration cannot override these harness/backend restrictions. Native
outputs remain text; a tool's image output is a separate reference contract.

Shared SDK receipts accept the same three MIME values. Node exposes
`admitToolImage`, `imageToolResult` and `toolImagePart`; existing PNG helpers retain
PNG-only checks. Regenerate operation/native SDK contracts through their owners.
Uploads, image IDs, `BinaryBlobStorage`, Relay delivery/claims, and text routing
keep their existing ownership and behavior. No new router or capability registry.

Deployment model declarations still load independently from disk, without a
resource build. See [model profiles](../model-profiles.md). Actual backend
acceptance and verified image-aware context accounting remain separate live
release evidence. This work does not deploy the feature or enable an unverified
counter.

Implementation tests cover encoded JPEG and lossy/lossless/transparent WebP,
truncation, dimensions, animation, receipt reconciliation, Node journal recovery,
all dispatch shapes, MIME-aware refusal, and PostgreSQL upgrade/reference retention.
The complete validation results must be reported with the change; mocked transports
and decoder tests do not prove live screenshot-to-active-model acceptance.

Sources: [LM Studio image input](https://lmstudio.ai/docs/python/llm-prediction/image-input),
[TwelveMonkeys 3.15.0](https://github.com/haraldk/TwelveMonkeys/releases/tag/twelvemonkeys-3.15.0),
[WebP container specification](https://developers.google.com/speed/webp/docs/riff_container).

Verification on 23 October 2026: `./gradlew format` and
`./gradlew formatCheck check sdkCheck` passed. The focused PostgreSQL run
`./gradlew :plowshare-server:test -PfullDb --tests '*TrajectoryArtifactsPersistenceTest' --tests '*JdbcArtifactRepositoryTest' --tests '*StillImageInspectionTest'`
passed 28 database cases and seven decoder cases. The full default server suite
passed 6,449 tests with zero failures/errors. SDK conformance passed for Node,
Python, .NET and Go, including the expanded MIME receipt fixtures. Binary test
vectors are retained as base64 text to preserve source-containment scans.

Main integration on 23 October 2026: feature work remains uncommitted on top of
`0785a3f08a22873e70c6f359000ba91393e1af3c`. Unpublished migrations were renumbered
184–188 without changing their contents; published main migrations remain intact.
`formatCheck check` passed with 6,613 server tests; all 43 focused blob, Relay,
admission and Trajectory PostgreSQL tests passed. `sdkCheck` passed after updating
the inherited Node capacity conformance assertion to match main commit
`c8bfba2255dcdafde32f68986ba3e16579e923ef`'s wait-only admission behavior: cancel a
parked request before submission, retaining exactly 64 transmitted requests.
