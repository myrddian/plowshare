# Deployment-owned model profiles

The server reads model sampling profiles at startup, and version 2 profiles may
also declare native input formats and output combinations. The existing dispatcher
now enforces these declarations on profiled image calls and explicit internal
format contracts. See [Decision 0043](decisions/0043-disk-model-media-profiles.md)
and [routing Decision 0044](decisions/0044-profile-aware-media-routing.md).

## Load profiles from disk

Set `plowshare.llm.sampling-directory` in deployment configuration to the directory
containing operator profiles. For example, reference a required deployment value:

```yaml
plowshare:
  llm:
    sampling-directory: ${MODEL_PROFILE_DIRECTORY}
```

Mount that directory read-only to the server. It is separate from runtime image
storage and project agent definitions. Without an override, the directory is
`sampling` relative to the process working directory; if absent, shipped profiles
stand alone. A present invalid profile stops startup.

The loader discovers `.yaml`, `.yml` and `.json` files; filenames determine profile
names, with `models` reserved for mappings. Duplicate stems such as `reader.yaml`
and `reader.json` are refused. Files must be UTF-8 and at most 256 KiB each.
An operator file replaces a shipped profile of the same name completely. The
`models` mapping merges by model key, with operator rows replacing shipped rows.
New profile names require no resource catalogue change. Edit the files and restart
the same binary; no JAR/resource/container-image rebuild or live reload is involved.

## Version 2 format declarations

An illustrative `reader.yaml`:

```yaml
version: 2
modes:
  default:
    precise: {}
    balanced: {}
    exploratory: {}
media:
  inputs: [text/plain, image/png]
  outputs:
    text:
      formats: [text/plain]
```

Empty sampling rows send no overrides. Existing sampling parameters remain
available in those rows. Every mode needs all three intents; version 2 requires a
`default` mode and permits up to 32 modes. The media declaration applies across
the file's modes. It is optional; its absence means unknown media support.

Add the deployed model to external `models.yaml`:

```yaml
models:
  deployed-reader-v1:
    profile: reader
    mode: default
```

Matching is case-insensitive against the wire model after removing its vendor
prefix: an exact key wins, otherwise the longest matching prefix. A family key
declares the profile for every matching version, so prefer exact deployed keys
when format support varies. Missing version-2 modes fail at startup; legacy
profiles retain their existing warning/default fallback.

Inputs and outputs are independent. For example:

| Output ability | `outputs` entries |
| --- | --- |
| Text only | `text: {formats: [text/plain]}` |
| Images only | `image: {formats: [image/png]}` |
| Text or image separately | Both individual entries |
| Text and image together | `mixed: {formats: [text/plain, image/png]}` |

Separate modes do not imply a mixed response. A mixed mode permits both formats
together but does not guarantee both appear; a future request's required formats
will impose that obligation. Names are local labels, not backend request values.
Output names use lowercase letters, digits, `_` and `-`, start with a letter,
and have at most 64 characters. Each format list contains 1..32 exact MIME strings;
there are 1..32 output modes. MIME case is normalized, but wildcards, parameters,
duplicates and malformed values are refused. Versioned files reject unknown fields
at every declared level. Duplicate YAML/JSON keys and malformed versions also fail.

Audio/video MIME types can be declared using the same structure. Declaration does
not implement their decoders, admission rules or native model transport. A profile
also supplies no image token cost merely by naming `image/png`.

Unversioned sampling profiles remain supported with no inferred media capability.
Adding `media` requires integer `version: 2`; quoting the version is invalid.
Use a profile-aware server: older binaries may ignore these new fields rather
than enforce them. No shipped model's media support is assumed from its name.

## Model response guards

A version 2 profile can select a JavaScript response guard with
`response-guard: harmony` alongside `modes` and `media`. Shipped GPT-OSS
and Qwen 3 profiles select `harmony` and `qwen-hermes`, respectively.
Resolution uses the selected route's wire model and the same exact and
longest-prefix matching as sampling. For a Qwen endpoint with a different
chat template, map the exact model to a profile that selects the right
guard or omits the field.

Place operator-owned filters in `response-guards/<name>.mjs` inside the
sampling directory. A local file of the same name overrides the shipped
filter. The server loads referenced modules at startup. Each module exports
`reject(text)` and returns a boolean. The function receives the assembled
response text, runs in the existing no-I/O JavaScript sandbox with a
two-second deadline and has no access to tools, credentials, or previous
invocations. The name is a lowercase identifier using letters, digits and
hyphens. Unknown, unreadable, or invalid modules fail startup. A guard that
fails during evaluation withholds the response.

The dispatcher buffers guarded streams until the assembled response passes.
A successful response delivers any reasoning the caller had opted to receive
under its existing disclosure policy.
It also refuses a completion that claims to have called a tool but has
no structured calls. Provider usage is still accounted. The agent runtime
stores a bounded excerpt of a rejected response after disclosure checks in
the authorized, non-projecting trajectory, together with the guard and
inference receipt. Server logs carry only diagnostic metadata.

A turn can spend one remaining model call on regeneration from its current
history, including completed tool results. The regeneration request offers
no tools, and the runtime rejects any attempt to call one. Required-tool turns,
guard failures, exhausted budgets and cancelled turns do not regenerate.
If the retry fails, the turn stops without publishing the malformed response.
Other dispatcher consumers receive the typed refusal and own their recovery.

## Model declarations and effective harness support

Model support and harness support are separate projections. A profile declares
model input/output ability; the serving backend restricts which encodings and
operations it accepts; the Plowshare harness restricts which paths it implements.
The effective capability is their intersection for the selected wire model, pool,
operation and direction. Current authority, artifact availability and bounded
image context admission remain additional invocation gates.

For example, LM Studio documents PNG, JPEG and WebP input, while the current typed
Node tool admission and Trajectory observation path implements still PNG, JPEG
and WebP. The HTTP
serializer's broader format declaration does not extend that path. A socket-enabled
LM Studio provider currently declares text-only support because its prefill path
refuses images. A model profile cannot override either harness restriction.
See the [capability map and planned format expansion](multimodal-first-slice-plan.md#model-backend-and-harness-capability-projection)
for current evidence and gaps, including the backend documentation source.

Keep this projection derived from the existing model profiles, deployment/backend
restrictions and owning harness components. No second registry is needed.
Deployment configuration can narrow effective support; it cannot implement a
decoder or enable an unsupported transport. Input and output support are separate:
retaining a tool-produced image does not imply native model image generation or
client rendering support. Apply the same distinction to future audio/video paths.

For the configured Gemma 4 E4B model, a deployment can place this complete profile
in `gemma4-reader.yaml` in its configured sampling directory:

```yaml
version: 2
modes:
  default:
    precise: {temperature: 1.0, top_p: 0.8, top_k: 64}
    balanced: {temperature: 1.0, top_p: 0.95, top_k: 64}
    exploratory: {temperature: 1.0, top_p: 0.95, top_k: 64}
media:
  inputs: [text/plain, image/png, image/jpeg]
  outputs:
    text: {formats: [text/plain]}
```

These sampling values preserve the existing Gemma profile. Add the exact model
mapping to the deployment's `models.yaml`, then restart:

```yaml
models:
  gemma-4-e4b-it:
    profile: gemma4-reader
    mode: default
```

This matches the configured vendor-prefixed wire name under the existing matching
rules. This example narrows the documented encoding set to PNG/JPEG: the inspected
MLX HTTP runtime refused WebP even though its installed processor accepted it.
Verify the serving runtime before adding formats. No resource rebuild is required;
the socket-prefill harness restriction still applies.

## Retained image context policy

Format support does not require an exact counter. Retained tool images use an
optional policy inside the same version-2 model profile, with these defaults:

```yaml
media:
  inputs: [text/plain, image/png, image/jpeg]
  outputs:
    text: {formats: [text/plain]}
  image-context:
    tokens-per-image: 4096
    framing-tokens: 1024
    max-images: 16
    max-prompt-bytes: 262144
```

The dispatcher uses the existing text tokenizer on history, tool arguments/results,
tool declarations and response schema, retaining one-third headroom on estimated
components. It adds framing and the per-image reservation for every occurrence,
including repeated references. The combined input reservation and explicit output
limit must fit the selected model's known context length. Byte and occurrence
limits are hard admission limits; image bytes keep their existing separate bounds.

Reservations are estimates, not proven model token bounds. Tune them to the
backend/model; the backend still enforces its actual context window. Integers are
strict: `tokens-per-image` is 1..1000000, `framing-tokens` 0..1000000, `max-images`
1..16 and `max-prompt-bytes` 1..1048576. Omitted fields use defaults. Invalid/unknown
fields fail profile loading. Configuration takes effect on restart.

The sidecar and full-prompt counter are optional. With the existing
`counting.automatic: true` opt-in, a verified complete measured count can refine
the context decision. Unavailable or incomplete counts fall back to the policy;
hard byte/image limits still apply. Reservations are recorded as estimated
preflight and never as consumed usage. Keep counter coverage flags disabled until
exact counting is verified. See [Decision 0053](decisions/0053-bounded-image-context-admission.md)
and [counter configuration](model-usage-accounting.md).

## Routing behavior

Ordinary text requests retain the existing model/class/system resolution and
least-loaded selection, including declaration-order ties. They do not acquire a
media requirement merely because a profile exists. Existing image calls without
any serving model's media declaration also retain their legacy path.

When a call contains images and its serving model has a media declaration, the
dispatcher checks every image in the effective prompt after input filtering. The
image MIME must agree with its byte signature. This reuses bounded signature
inspection; complete file validation and media context bounds remain separate
admission work. An internal caller can also attach explicit `MediaRequirements`
to a `ChatRequest`; those requirements survive every copy and cannot be dropped
by an input filter. Undeclared models cannot satisfy an explicit contract.

Eligible pools must satisfy the model profile, actual transport support and
existing pool `vision` restriction. Input formats must all fit; required output
formats must fit one common model/transport combination containing no disallowed
formats. Matching never unions separate text and image modes. Unknown model and
unsupported media are different failures. Profiles cannot enable an unimplemented
response decoder: current production transports declare text output only.

The OpenAI-compatible transport declares its existing PNG, JPEG, GIF and WebP
input serializer. Other transports default to text only; LM Studio without a
prefill socket narrows the base declaration to PNG, JPEG and WebP. A socket-enabled LM Studio provider
conservatively declares text only because its alternate prefill path refuses
images. No automatic protocol fallback is added.

Filtering happens before load comparison on complete/stream/fold/count paths.
`streamOn` refuses an incompatible reserved pool, even if another pool could serve
the request. `poolsServing(ChatRequest)` provides the same eligibility predicate
for admission with a complete request. The existing scheduler's specifier-only
capacity query remains unchanged; the actual pinned call is always checked.
Counting is an explicit preview of the supplied request and does not run the
possibly effectful input review again; inference checks the post-filter prompt.

For vision agents using a media profile, startup now requires one compatible
image route; runtime filtering excludes the others. Without media declarations,
the existing all-pool vision guard remains in force. Neither mode switches the
configured wire model or grants artifact access. Audio/video transport, generated
native media and audio/video remain separate capabilities. Retained tool-image
admission uses the policy above; this does not change ordinary upload behavior.
