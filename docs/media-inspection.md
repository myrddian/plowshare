# Media inspection

The server exposes `MediaInspection` as an injectable capability for validating
image bytes and deriving an immutable `ImageDescriptor`. It supports still PNG,
JPEG and WebP; see [Decision 0051](decisions/0051-typed-still-image-formats.md). It is consumed by the explicit
[invocation-bound admission profile](invocation-image-admission.md). Ordinary
uploads, Relay, Conversation/Trajectory and the dispatcher do not invoke it.

Existing image uploads, workspace image notes, `img_` IDs, disk bytes and JSON
sidecars retain their current behavior. Inspection creates no file, database row,
artifact ID or ownership grant. The admission profile stores validated original
bytes through the existing image store and commits its invocation/retention link.
A descriptor alone is neither a durable admission receipt nor permission to read
the image.

## Contract and implementation

`inspectImage(byte[])` returns detected MIME through the existing `ImageFormat`,
width, height, original byte length and full SHA-256. Filename and declared MIME
are deliberately absent from this interface. The digest includes original
metadata and matches the hash prefix used by `ImageStore.idFor`; it does not
replace that existing storage identity. Nothing is resized or re-encoded.

The implementation uses pinned `tika-core` for byte detection. Tika supports
[selecting dependencies individually](https://tika.apache.org/docs/4.1.x/using-tika/java-api/index.html#_dependencies);
no parser package, OCR engine or recursive extraction is enabled. The optional
image parser adds metadata extraction that this contract does not need.
Dimensions and pixels use allowlisted Java 21 PNG/JPEG readers and the pinned
TwelveMonkeys WebP reader.
`TextExtraction` continues to accept its existing text/PDF formats.

Validation proceeds as follows:

1. Reject empty/oversized arrays and acquire shared inspection capacity before
   copying bytes. Callers still have to bound multipart ingress before allocating
   their array. The inspector cannot undo an allocation already made by its caller.
2. Copy a private snapshot and detect its format from a bounded prefix. Both Tika
   detection and the existing shared image signature must agree on a supported MIME. Other
   detected types remain unsupported regardless of Tika's broader detector coverage.
For PNG, continue with these checks:

3. Check complete PNG framing, chunk CRCs, header/depth/color combinations,
   palette/transparency placement, consecutive image-data chunks and final IEND.
   Refuse unknown critical chunks, malformed lengths, trailing bytes, APNG control
   or frame chunks and more than 1,024 chunks. Bound dimensions before pixel work.
4. Stream-inflate IDAT into an 8 KiB scratch buffer, enforcing the exact scanline
   length derived from bounded dimensions/depth, including Adam7 passes. Refuse
   truncated zlib streams, surplus compressed data and excess expanded bytes.
5. Decode the pixels with the JDK PNG reader. The transient decoding view includes
   only header, palette, transparency, pixel and end chunks. Ancillary metadata is
   CRC-checked but remains opaque; compressed text/ICC/EXIF is never expanded or
   interpreted. This view is discarded and never used as a replacement stored file.
6. Hash the original snapshot and return only the typed descriptor. Dispose decoder
   resources and release capacity on both success and failure.

JPEG validation bounds marker/scan structure, dimensions and components before
pixel decoding and requires a complete single image with no trailing file. It
supports 8-bit grayscale/RGB baseline and progressive encoding. APP/COM metadata
is excluded from the transient decoder view; multi-picture MPF is refused.

WebP validation bounds RIFF/chunk lengths, canvas and encoded image geometry,
then decodes lossy/lossless pixels and compressed alpha. Animation is refused.
Uncompressed alpha is validated as an exact bounded byte-per-pixel plane because
the pinned reader does not implement it; color pixels are decoded independently.
Opaque metadata is excluded from validation decoding. Original bytes and MIME
are preserved for storage and model input, including alpha and metadata. Decoder
warnings refuse admission. These checks do not certify metadata for other parsers.

The inspector uses memory-backed Image I/O streams and does not change Image I/O's
global cache setting or create temporary files. Metadata validation is intentionally
limited to framing and CRC, not interpretation of optional embedded metadata.
The pixel/decompression checks do not certify ancillary metadata for another parser.

## Configuration and limits

Limits are read at server startup. Raw byte size reuses
`plowshare.images.max-bytes`, which defaults to 5 MiB. The new settings are:

| Property | Default | Accepted range |
| --- | --- | --- |
| `plowshare.media.inspection.max-pixels` | 16000000 | 1–64000000 |
| `plowshare.media.inspection.max-dimension` | 16384 | 1–32768 |
| `plowshare.media.inspection.max-concurrent` | 2 | 1–8 |

Both width and height must fit the dimension bound, and their product must fit the
pixel bound. Invalid limits fail startup. These inspection limits do not reject
previously accepted legacy upload formats or add a pixel decode to those uploads.

Capacity is fail-fast: excess concurrent calls return `BUSY` without an internal
queue, snapshot allocation or external effect. All callers use the shared bean.
Inspection cost is bounded by configured bytes, pixels and the chunk ceiling, not
a promised wall-clock timeout. Peak memory includes the private raw snapshot,
memory-backed decode stream and raster (up to eight bytes per pixel), plus decoder
overhead. Size the concurrency setting against that per-call budget and the
caller's separately bounded upload memory. Raising limits requires a matching
deployment memory budget.

Refusals expose a typed reason: `EMPTY`, `TOO_LARGE`, `UNSUPPORTED_FORMAT`,
`INVALID_IMAGE`, `ANIMATION`, `DIMENSIONS`, `TOO_COMPLEX` or `BUSY`. Messages and
exceptions omit input bytes, embedded metadata and underlying parser causes.
Busy inspection may be retried without repeating the producer's external effect.

## Boundaries still to implement

Invocation admission now authorizes its owner/executor, retains the descriptor
against finalized bytes and establishes the invocation hold. Result/trajectory
consumers retain independent transactional claims under the implemented
[Trajectory observation contract](trajectory-image-observations.md). The admission profile never
trusts a caller-supplied descriptor or stores different bytes from those inspected.
The sidecar schema and physical retention policy remain unchanged.

File dimensions and MIME do not establish model support or context cost. Routing
continues to use model profiles and transport declarations. A verified model/backend
counter or conservative bound is still needed for native image context admission.
Audio/video inspection, native transport and persistence follow through this same
separation of file properties, ownership and model capability; they are not enabled
by installing Tika.
