# MLX prompt counting companion

A count-only companion for an explicitly configured LM Studio Gemma 4 MLX model.
It uses the model host's **existing bundled Python environment**, tokenizer,
processor and `prepare_prompt_inputs` implementation. It never loads model
weights, calls a generator, owns artifacts, or forwards inference. No package
installation is required. It is independent of the server resource build.

This is an opt-in platform counter, not an SDK integration or a second agent
runtime. Existing disk-loaded model profiles continue to declare eligible MIME
formats and output modes. Relay, Trajectory and BinaryBlobStorage are unchanged.

## Configuration and startup

Run with the selected engine's bundled Python, not the system Python or another
installed MLX environment. Supply a deployment-owned JSON file to `--config`:

| Field | Required value |
| --- | --- |
| `listen_host`, `listen_port` | Explicit bind address and port; port zero is for isolated verification only. |
| `model` | Exact identifier of the already loaded model. |
| `model_path` | Absolute existing model directory matching the loaded model's path. |
| `engine_manifest` | Absolute manifest of the selected installed MLX engine. |
| `upstream_ws_url` | Explicit `ws://` or `wss://` LM Studio `/llm` URL, without credentials/query/fragment. |
| `token_env` | Environment-variable name holding the configured LM Studio token; no token in the file. |
| `enable_thinking` | Explicit boolean matching the serving template's default; checked against its template-only RPC at startup. |
| `revision` | SHA-256 returned by `--inspect`, required before listening. |

The same token authenticates the companion and its read-only LM Studio SDK calls.
Keep configuration and environment files private; do not put them in the image,
repository or logs. For a network listener, use the deployment's authenticated
TLS proxy and retain bounded request sizes/timeouts. The companion has one active
processor and at most two accepted connections; overload is refused.

```sh
"$MLX_PYTHON" -B service.py --config "$MLX_COUNT_CONFIG" --inspect
"$MLX_PYTHON" -B verify.py --config "$MLX_COUNT_CONFIG" --base-url "$LM_STUDIO_BASE_URL"
"$MLX_PYTHON" -B service.py --config "$MLX_COUNT_CONFIG"
```

`--inspect` loads only the tokenizer/processor, validates template defaults and
prints a revision without starting a listener. Set the resulting revision in the
configuration only after reviewing the live verification. `verify.py` makes eight
**real generation requests**, each capped at four output tokens, using synthetic
PNG/JPEG/WebP images, multiple images, a system prompt and tools. It records counts
and usage metadata only and exits **2** on any mismatch. It never enables admission.
The verifier uses a temporary authenticated HTTP listener and requires an explicit
loopback `listen_host` and `listen_port: 0` in its verification configuration.
Use a separate configuration with the deployment port for normal serving.
Repeat requests test total-count consistency; absent cache metadata does not prove
a KV cache hit. There are no automatic inference probes during service startup.

The revision covers the model metadata/tokenizer, installed preparation code,
package versions, configured serving template/settings and default thinking mode.
Each count checks the live loaded model, selected engine and template/settings,
then checks them again after processing. File replacement, model reload during a
count, changed settings, busy/unavailable runtime or malformed input is an error.
Restart, inspect and reverify after upgrades or changes; do not relabel an old
revision. Disable runtime auto-updates under the deployment's existing policy.

Initialization includes heavy Python libraries; the investigation observed about
six seconds to initialize and roughly 1.4–1.7 GiB of process memory. Keep the
processor warm and account for its memory separately from the serving model.

## HTTP and existing counter integration

The only operation is authenticated `POST /v1/chat/completions/input_tokens`.
It accepts the existing non-streaming Chat Completions body and returns:

```json
{"object":"response.input_tokens","model":"configured-model","revision":"<64 hexadecimal characters>","input_tokens":272}
```

The complete count includes template tokens, ordered history, tools, tool results
and every image occurrence. PNG/JPEG/WebP data URIs are supported; URLs are never
fetched. Byte, image-count, dimension, pixel and JSON-complexity limits are enforced.
Animated images are refused. The service does not persist prompts or image bytes.

Supported prompt options are `chat_template_kwargs.enable_thinking` and absent/
`auto` tool choice. Sampling temperature/top-p/top-k/output limit are validated
but do not change prompt preprocessing. This slice refuses reasoning-effort,
structured-output, forced/required/none tool choice and other unverified fields.
Unsupported fields never silently become partial counts. System messages must
appear first. Tool-call JSON arguments are parsed before applying the template;
the underlying tokenizer renderer preserves scalar/array system content.

Configure the existing HTTP pool, retaining its model profiles:

```yaml
counting:
  chat-protocol: lmstudio_mlx_input_tokens
  url: ${MODEL_INPUT_TOKENS_URL}
  revision: ${MLX_COUNT_REVISION}
  image-tokens-included: false # Keep false until actual image usage agrees.
  cache-entries: 0
  timeout: 2s
  embeddings: false
```

The existing same-origin rule remains: inference and count URLs must share scheme,
host and port. Route the count path to the companion through a deployment-owned
proxy and other paths to LM Studio; configure that origin on the pool. The
companion does not become an inference proxy. Preserve authentication headers and
disable body/access-detail logging. No new credential destination is implicitly
allowed. Responses must echo the exact model and configured revision.

Counts use the existing concurrency/deadline/owner-scoping machinery. Cache entries
must be zero for this protocol so a cached response cannot bypass the live binding
checks. vLLM and llama.cpp counting and ordinary text generation remain unchanged.
Image admission remains separately controlled by `image-tokens-included`; publishing
a MIME capability or a processor count is not evidence of serving parity.

## Release gate

The inspected LM Studio deployment reported **15** prompt tokens for an image
prompt whose installed processor produced **272** input IDs. Its HTTP `stats` was
empty. The same deployment refused the valid WebP generation fixture with HTTP 400,
although its installed processor counted it successfully. Model/processor support
does not establish HTTP harness support. That is a serving usage discrepancy, not permission to subtract image tokens
or introduce a calibrated constant. Verify the target runtime again before release.

The companion's full context count is `len(prepared.prompt_input_ids)`. The inspected
engine's *prefill progress* reports one fewer token and separates cached prefixes;
it is not the full context count. Do not compare against progress percentages or
uncached work alone. Admission must remain disabled while the actual usage
comparison fails or coverage is unknown. This change does not modify the runtime
or reinterpret its consumed usage/accounting.

## Tests

Default tests use a fake counter, fixture sockets and standard Python libraries:

```sh
python3 -B -m unittest discover -s extensions/mlx-counting
./gradlew mlxCountingTest
```

They run in `check` without MLX, model weights, network inference or a database.
Live verification is explicit and uses the actual model host's environment.
