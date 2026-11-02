# 0052: Count complete MLX prompts outside the inference runtime

Status: accepted for implementation; image admission requires deployment verification.

A model can accept images while its serving API exposes only text tokenization.
Plowshare needs a complete context count before admitting retained image observations.
This is a general model-provider capability, separately authorized as platform work;
it does not extend core APIs or persistence for an external SDK adapter.

The existing full-chat counter gains an explicit LM Studio MLX protocol. It sends
its existing generation projection unchanged, validates the complete count object,
exact model and pinned SHA-256 revision, and retains its existing bounded exchanges.
The default vLLM text protocol and llama.cpp protocol are unchanged. The MLX path
requires zero cache entries because each exchange revalidates live serving settings.

A deployment-owned count-only Python companion uses the selected engine's installed
packages and local model metadata, without loading weights, starting an agent runtime
or acquiring artifact ownership. Its narrow typed counter contract receives validated
message/media DTOs. Raw provider/JSON shapes are confined to HTTP, SDK configuration
and model-library boundaries. Model profiles still own media routing declarations.

The public LM Studio SDK's string-only count method cannot perform image expansion;
using it as complete would undercount. Changing inference providers or building a
second tokenizer/image formula registry is unnecessary. The companion reuses the
installed preparation implementation and configured serving template, fences changes
with a revision and live binding checks, and rejects unverified prompt options.

Counter requests remain authenticated, same-origin, bounded and non-replayed. No
SDK, public operation, database, Relay, Trajectory or BinaryBlobStorage change is
needed. Model-host paths, credentials, origins and ports are deployment configuration.
The companion owns no durable execution state or media lifecycle.

Unit/HTTP tests use fakes without MLX or a database. Live acceptance explicitly
compares synthetic PNG/JPEG/WebP and repeated/multi-image/history/tool prompts with
actual generation usage. A mismatch keeps image admission disabled; count observations
never replace or correct consumed provider usage. See the [companion manual](../../extensions/mlx-counting/README.md).
