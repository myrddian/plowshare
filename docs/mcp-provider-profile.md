# Bounded MCP provider profile

This is the adapter profile for [ADR 0019](decisions/0019-mcp-provider-hosting.md).
The [reusable outbound HTTP integration](../integrations/mcp/README.md) implements
the shared-schema tools profile. [Native HTTP/STDIO hosting](native-mcp-hosting.md)
uses the same direct client; only explicit server sharing uses Relay.

## Protocol selection and transport

Use official `@modelcontextprotocol/client` and `@modelcontextprotocol/core` 2.3.1
with the public Plowshare Node SDK. Keep this dependency outside protocol/server
and separate from the outward-facing `plowshare-mcp` adapter.

| Explicit connection profile | HTTP or native STDIO behavior |
| --- | --- |
| `2026-07-28` | Probe `server/discover`; pin revision and send per-request metadata; no initialization/session assumption. |
| `2025-11-25` | Initialize and acknowledge the legacy connection; verify returned revision and keep its session semantics isolated. |
| Other revisions / HTTP+SSE | Refuse with an actionable diagnostic; no silent fallback. |

The [versioning specification](https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning)
and [discovery contract](https://modelcontextprotocol.io/specification/2026-07-28/server/discover)
distinguish these profiles. Version selection finishes during read-only discovery,
before any effect. Operators may explicitly change profiles and rediscover; old
unresolved invocations keep their original journal identity.

Modern HTTP requires protocol metadata plus method/name headers and supports JSON
or request-scoped SSE results. Closing that response stream signals cancellation;
STDIO sends a request-specific cancellation notification. Neither proves rollback.
See [HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http),
[STDIO](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/stdio)
and [cancellation](https://modelcontextprotocol.io/specification/2026-07-28/basic/patterns/cancellation).

The audited SDK defaults need deliberate overrides:

- Pin modern negotiation rather than `auto`; verify the legacy handshake revision.
- Advertise empty capabilities and set `inputRequired.autoFulfill: false`. The
  default automatically performs interactions and repeats the original request.
- Execution transports use a token provider without an unauthorized-refresh
  callback; insufficient-scope handling is `throw`. Prepare credentials before
  dispatch. A challenge updates status and stops work, never resends the call.
- Set execution redirects to `error`, stream reconnect retries to zero and bounded
  request/body readers. Caller metadata cannot override reserved protocol fields.
- Isolate client/cache instances by connection, subject, credential generation and
  immutable catalogue revision, even for SDK `public` cache hints. Self-reported
  upstream server identity never authorizes cache sharing across owners.

Server-side hosting only permits configured HTTP destinations, with HTTPS for
authenticated remote deployment. Destination/redirect/discovery policy includes
OAuth endpoints and DNS resolution. Private org endpoints need explicit operator
configuration; they do not allow arbitrary private-network access.

Native STDIO uses a configured executable, argument vector, working directory and
selected environment, without shell interpolation or inherited secrets. Explicit
revision selection avoids the SDK auto-negotiation sibling spawning startup effects
twice. Bound stdout messages, redact stderr and shut down owned process trees with
an OS-appropriate grace period. CLI hosting is persistent. Browser clients never
launch STDIO; local host exit removes availability, without another device fallback.

## Discovery, schema admission and names

Decode untrusted MCP JSON at the adapter boundary into the existing typed schemas,
values and a typed immutable mapping. Unsupported keywords/dialects, duplicate
names/fields, invalid numbers and exhausted budgets refuse admission. Preserve
input constraints exactly; never flatten or stringify arguments or substitute a
vendor validator for the shared profile.

Input and optional output schemas must fit the [shared executable profile](tool-schemas.md),
including its object-root requirement. With no output schema, bounded structured
JSON still uses the recursive value decoder. Record per-tool compatibility diagnostics.
Only publish a coherent snapshot after the bounded discovery pass completes;
a failed/partial refresh cannot renew the old catalogue as if it were current.

The modern HTTP binding requires `x-mcp-header` parameter mirroring, which the
merged schema profile cannot represent. Refuse those tools visibly in the first
delivery. Do not strip the keyword or hide a separate schema in execution. Supporting
it needs an explicit shared annotation/mapping decision, compatible fingerprints
and validated header destinations/values. The same refusal policy covers patterns,
remote references and other unsupported schema features. This is a bounded tools
consumer, not complete MCP or JSON Schema conformance.
See the [upstream tool contract](https://modelcontextprotocol.io/specification/2026-07-28/server/tools).

Discovery defaults to 32 pages and 1 MiB per upstream HTTP request/response,
with a configuration-supplied finite total deadline. The existing shared provider
contract permits at most 128 tools per catalogue; this is not an MCP-specific
configuration limit. The HTTP integration
accepts shared defaults and per-binding overrides for discovery, payload and result
budgets; see its [configuration reference](../integrations/mcp/README.md). Operator
budgets remain within platform contract ceilings. Reject repeated cursors and
inconsistent pages. Complete catalogues follow Relay TEXT admission, independently
of 64 KiB transport chunks. The adapter has an explicit publication-byte budget
(1 MiB by default). Schema/value and invocation envelope limits also apply after
conversion; these limits are cumulative.
Use the retained execution deadline and configured timeout, at most 300 seconds.
Progress does not extend that absolute limit.

Reserve the existing scope prefix, at most 48 characters.
Map each case-sensitive upstream name to a
valid lower-case platform name of at most 256 characters (or the configured
lower offered-name budget), using a bounded readable slug and the first 32 lower-case hexadecimal digits of SHA-256, leaving room for
the prefix and a separator. Hash the original name and complete execution
contract: selected protocol, input/output schemas, published description/title,
supported annotations/execution metadata, binding generation and resolved
discovery/result budgets. Generate the
slug without adjacent/trailing underscores and validate the complete final name.
Detect collisions rather than silently renaming or selecting another tool.

An output-only contract change must also change the offered name, so an input
fingerprint match cannot select a new output validator. Exact grants then need
an explicit update; wildcards retain their current provider/owner/project/agent
fences. Store the original name and typed contract in the handler's immutable
mapping and journal configuration. Metadata refresh creates a new snapshot/client;
it cannot mutate a running handler or its cache. Retain old journal generations
for reconciliation. Existing input fingerprints and whole-definition registry
equality remain authoritative; no new tool registry is introduced.

Publish/renew catalogues through existing Relay topics with fresh publication
UUIDs and retained metadata intent. Reconnecting or refreshing never repeats an
unknown call or an uncertain result publication.

## Results and capabilities

Render supported output as bounded JSON text in the existing `ToolResult`:
ordered text content, optional typed structured data and upstream `isError`.
The selected profile governs decoding. Preserve text and absent versus explicit null; validate against
the pinned supported output schema where supplied. The integration's encoded
result text must fit 16,384 UTF-16 units and UTF-8 bytes (or its lower configured
budget), and the complete provider result envelope must fit 32,768 UTF-16 units.
No coercion, truncation or implicit resource fetch is permitted.

The [authority manual](mcp-tool-authority.md) defines existing grants, hook checks
and retained evidence. Upstream hints never select credentials or waive authority.
The command approval gate does not provide per-call human approval for arbitrary
MCP tools; do not claim that unsupported workflow from an annotation or hook `ask`.

| Observation | Provider state and meaning |
| --- | --- |
| Valid final result, including `isError: true` | `COMPLETED`, with error flag/content retained: a response was received, not proof of effect success/rollback. |
| Proven refusal before upstream dispatch | `REJECTED`: no upstream call was sent. |
| Loss, invalid/oversized content or unsupported interaction after dispatch | `UNKNOWN`: effects may have happened; reconcile the original invocation. |

A post-dispatch auth/protocol error is retained as a diagnostic. Only proof that
execution was not admitted permits REJECTED; a status code, remote message or
read-only annotation alone is insufficient. A received `isError` result does not
become a host `E_NO_EXEC` refusal or a reason to repair JSON and repeat the effect.

Do not advertise sampling, elicitation, roots, tasks or other extensions. Prompts,
resources, icons, embedded media/resource results and task-required tools are
outside the first release. Refuse known requirements at discovery. An unexpected
`input_required`/extension result after dispatch remains UNKNOWN; disable automatic
continuation. List-change notifications may request bounded metadata refresh in
the selected profile. Missed notifications cannot renew authority indefinitely.

The existing server cancellation path stops waiting and reports UNKNOWN after
submission; it does not abort the SDK handler. Enforce deadlines in the host and
scope immediate propagation as an exact-invocation provider advisory separately.
Upstream cancellation and shutdown are best effort and cannot establish no effect.
Stop intake/renewal, withdraw when possible and preserve the journal even if
withdrawal delivery is unknown.

## Reproducible design probe and acceptance

The [manual probe](../test-support/mcp/profile-probe.mjs) uses independent synthetic
wire fixtures and the exact official client pin, without Plowshare or upstream accounts:

```sh
npm install --prefix build/mcp-design-probe --save-exact \
  @modelcontextprotocol/client@2.3.1 @modelcontextprotocol/core@2.3.1
node test-support/mcp/profile-probe.mjs --modules build/mcp-design-probe
```

It passed nested HTTP/STDIO calls for both revisions, and one-send HTTP loss,
static-token 401 and disabled `input_required` continuation checks. It proves
SDK/profile mechanics only: integrated provider admission, live org OAuth,
cross-user/service isolation, SSE cancellation and native distribution/process
behavior remain implementation acceptance gates.

The probe adds no runtime dependency or database schema. Later platform services
use mocked interfaces by default, with focused PostgreSQL opt-in only for introduced
persistence invariants. Adapter acceptance must use public SDKs against a configured
server, including separate host/container deployments.

Current independent integration fixtures are run through
`./gradlew :plowshare-mcp-integration:check`; the installed-consumer check repeats
them against packed public SDKs. HTTPS fixtures retain hostname/certificate
verification, with a public test certificate trusted only by the spawned test
process. No runtime TLS bypass is added. The [acceptance runbook](mcp-interoperability.md)
documents configured server setup and evidence that still needs a real organization
endpoint. The design probe above is historical profile evidence, not a substitute
for that acceptance.
