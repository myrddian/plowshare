# Model usage, counting and cloud pools

Durable accounting is opt-in. Apply Flyway migrations through V87, retain the configured server data directory, and set `plowshare.llm.accounting.enabled: true`. Data layout 6 reserves `<data.dir>/accounting`. The database stores call and attempt metadata; the durable journal bridges temporary projection outages. Neither stores prompts, model output, images, credentials or provider error bodies.

Every enabled inference request needs explicit server-resolved ownership. New legacy-unattributed requests fail before contacting the model. Runtime calls retain their account, stable project ID, conversation and run ancestry, actual agent, turn and step. Folds, validators, advisors, learning, digestion, navigation, scheduling, corpus summaries and embedding write/query/repair paths carry explicit operations. Background maintenance without a human requester uses a system bucket. Shared corpus work has global scope; a document query made by an agent keeps the requesting execution's project scope.

Source deletion does not cascade into accounting. The original account can inspect retained conversation/run history after source deletion. Current source ownership and project membership continue to control access while those rows exist. Project members can read project totals, including other members' work in that project. Conversation/run/orchestration detail additionally checks account ownership. The current account model gives every login account administrator privileges; `usage.pools` is the explicit fleet report. Other report types filter to accessible work even if the payload asserts `admin` or a different `account`.

## Accounting and prices

Calls and upstream attempts are different units. A retry adds an attempt to the same call; an earlier attempt with missing usage remains unknown after a successful retry. Project totals sum atomic attempts once. A conversation/run/orchestration subtree uses captured ancestor IDs rather than adding already inclusive reports.

Input, output, provider total, cache read/write and reasoning observations remain independently nullable. Cache and reasoning are subsets, not extra tokens to add to the provider total. Reports return known subtotals, known-field counts and incomplete-attempt counts. All cumulative quantities and monetary amounts are decimal strings. Money is separated by currency, and estimates are not invoices.

Configure prices under the top-level `plowshare.llm.pricing` map. `billing-route` defaults to the pool name; price lookup uses that route and the exact wire model, including Azure deployment aliases. `model-families` controls capabilities independently. Supported modes are `token`, `included`, `zero_rate` and `unpriced`; missing rates stay unknown. Token rates are per million. Cache read/write rates, input-size tiers and optional per-attempt request fees are supported. Prices are immutable admission snapshots. Editing boot configuration affects a rebuilt accounting catalog/new admissions, never booked history; automatic hot reload/repricing is not implemented.

```yaml
plowshare:
  llm:
    accounting:
      enabled: true
    pools:
      - name: local
        provider: openai
        base-url: ${VLLM_BASE_URL}
        api-key: ${VLLM_API_KEY:}
        models: [local-model]
        billing-route: local
        counting:
          url: ${VLLM_TOKENIZE_URL}
          revision: ${VLLM_TOKENIZER_REVISION:}
          timeout: 2s
          cache-ttl: 60s
          cache-entries: 1000
          automatic: false
          embeddings: false
    pricing:
      local-included:
        revision: local-contract-v1
        billing-route: local
        model: local-model
        mode: included
        currency: USD
        source: operator
```

Values here illustrate configuration, not commercial rates. An overlay replaces the entire pool list: include every pool you intend to keep. The journal defaults to 256MiB capacity, 4MiB segment target, 128 buffered terminal events and 1s projection interval. Journal pressure refuses new inference admission; terminal recording failure preserves already obtained content and reports degraded/incomplete capture. Keep journal and database backups consistent. Reclaimed, acknowledged journal segments cannot reconstruct a database restored to an earlier watermark. Recovery replays metadata only and never reissues inference. This first-release journal format remains version 1: new readers accept earlier events without the optional preflight observation; older binaries may reject events containing it. Use matching code/database/journal backups for a rollback.

## Explicit vLLM counting

`counting.url` is the full tokenization URL, including any proxy prefix. It must share the inference URL's scheme, host and port; redirects, embedded credentials and URL queries are refused. It is never derived by appending a path to `/v1`. No tokenizer request runs at boot or when reading saved usage/context reports.

The adapter follows the [vLLM 0.15.1 tokenization protocol](https://docs.vllm.ai/en/v0.15.1/api/vllm/entrypoints/serve/tokenize/protocol/). It sends the same serialized messages, tools and configured `chat-template-kwargs` as generation, with the contract's generation-prompt flags. Images, explicit tool-choice constraints and `reasoning_effort` are currently unsupported full-template coverage and return `UNKNOWN`. A failed/unsupported count never blocks inference or becomes consumed usage. Existing cheap component estimates and compaction thresholds keep their existing behavior.

Counting has a two-request concurrency bound per pool, an overall deadline, request/response size bounds and an ephemeral cache. Cache identity includes owner, conversation/project, exact model, full count input and configuration fingerprint. Configuration/template/auth changes invalidate it. TTL is at most 60s; a missing declared revision is exposed as an unverified template revision. Measured means the tokenization endpoint returned a count for the supported request shape; deterministic fixtures compare it with generation input usage. No deployed vLLM version/template has been verified in this implementation.

Embedding counting is separately opt-in. The pinned protocol takes a scalar `prompt`; batches are counted as individual scalar requests within one overall deadline, then summed. Enable it only when the deployed pooling/tokenizer contract matches `embedding-special-tokens` (default true). It is not an inference batch or a booked attempt.

`automatic: true` enables preflight for inference through that pool, including pinned streams. Its observation is saved separately on the call's terminal event. Consumed totals use provider attempt observations only.

## WebSocket reads and subscriptions

Use the existing authenticated `/v1/events?session=<session>` socket. All requests use the existing `plowshare-v1` envelope. Replies echo `id` and `type` and contain the standard `Outcome`. No metrics/count REST routes or HTTP fallback are provided.

```json
{"id":"usage-1","type":"usage.conversation","protocol_version":"plowshare-v1","payload":{"conversation":"conversation-id","scope":"subtree","from":"2026-10-01T00:00:00Z","to":"2026-10-02T00:00:00Z","group_by":["agent","operation"]}}
```

Aggregate types are `usage.conversation`, `usage.project`, `usage.agent`, `usage.run`, `usage.orchestration`, `usage.models` and `usage.pools`. The first five require the matching named target. Optional selectors include conversation, project name, agent, run, orchestration, exact wire model (`model`), pool and billing route (`route`). `scope` is `direct` (default) or `subtree` for lineage selectors. Global/system work is visible through the fleet report's global/system counters and project/operation grouping.

Ranges are UTC `[from,to)`, default last 30 days, maximum 366 days. Supply explicit bounded ranges when comparing longer history; records before tracking began are not reconstructed. `group_by` allows at most two distinct dimensions: `day`, `model`, `pool`, `agent`, `run`, `operation`, `project`. Run groups also include nearest-parent-first `ancestor_runs` captured from authorized calls. Groups default to model for `usage.models` and pool for `usage.pools`. Model reports can filter `route`; groups never combine currencies into one amount.

`limit` is 1–200. `usage.calls` defaults to 50 calls and returns atomic attempt records; aggregate group pages default to 200. Header totals cover the entire authorized filtered result. Use the returned cursor with the returned resolved filters, including the same time range, grouping and limit. Cursors are signed and bound to account/filter; they are not durable replay cursors. Call pages include usage source/coverage, price identity, lifecycle, operation and capture timing, with no content. Per-call audit lists include the first 50 attempts and explicitly mark truncation. To continue, send `usage.calls` with `call`, the returned `attempt_cursor`, and the same resolved filters; an attempt page returns its continuation as `cursor` (resubmit that value as `attempt_cursor`). Every continuation rechecks access. Group, call and attempt pages have a 384KiB row budget in addition to their row limits; header totals still cover the whole result.

Reports include tracking start even before the first inference, projection watermark, pending events and lag, journal capacity/problems, buffered/lost terminal metadata and projection problems when capture is enabled. `historical_usage: not_imported` explains the pre-feature gap. Capture disabled is explicit; existing history remains readable. An unreachable database is an unavailable read, never an empty successful total.

```json
{"id":"count-1","type":"conversation.context.count","protocol_version":"plowshare-v1","payload":{"conversation":"conversation-id","agent":"agent-name"}}
{"id":"subscribe-1","type":"usage.subscribe","protocol_version":"plowshare-v1","payload":{"report_type":"usage.project","project":"project-name","group_by":["model","operation"]}}
{"id":"unsubscribe-1","type":"usage.unsubscribe","protocol_version":"plowshare-v1","payload":{"subscription":"server-issued-id"}}
```

Count refresh authorizes the conversation, resolves the named agent and its actual offered tool schemas, then counts the next projection. It accepts no supplied messages, URL or billing identity. It is a projection of the next ordinary request, not a recording of an in-flight prompt or its transient forced-tool/hook instructions.

Subscribe supports aggregate reports without pagination cursors, with at most eight per physical socket. The initial correlated snapshot has revision 0. `usage.updated` envelopes have no `id`, increasing revisions, and complete replacement reports. Replace displayed quantities instead of adding them. Updates are checked at most once per second, stay quiet while unchanged, and revalidate access. Revocation produces `usage.closed` with a safe failure code. Disconnect/replacement removes subscriptions; reconnect requires fresh reads/subscriptions. The sender keeps only the latest pending snapshot per subscription, separately from correlated replies and token deltas. Unsubscribe is idempotent on the owning socket.

## OpenAI and Azure v1 pools

Azure v1 uses the same OpenAI-compatible transport. [Microsoft documents the `/openai/v1` base path and deployment-name model field](https://learn.microsoft.com/en-us/azure/foundry/openai/api-version-lifecycle?tabs=key); the [v1 chat contract](https://learn.microsoft.com/en-us/rest/api/microsoft-foundry/azureopenai/chat) supports API-key headers and the existing chat/tool/SSE usage shapes.

Choose `request-style: cloud` to remove local extensions. Declare request capabilities for each family; do not infer them from deployment names. Cloud tool support defaults to false and must be enabled for a compatible Chat Completions family. Output-limit spelling, supported reasoning efforts, temperature/top-p restrictions, structured output and Chat Completions tool support depend on the model. [OpenAI's reasoning guide](https://developers.openai.com/api/docs/guides/reasoning) explains why some current models require Responses for tool calling. This implementation supports Chat Completions deployments and does not add the Responses API.

```yaml
plowshare:
  llm:
    accounting:
      enabled: true
    pools:
      - name: azure
        provider: openai
        request-style: cloud
        base-url: ${AZURE_OPENAI_BASE_URL} # https://<resource>.openai.azure.com/openai/v1/
        api-key: ${AZURE_OPENAI_API_KEY}
        api-auth: azure_api_key
        models: [my-chat-deployment]
        billing-route: azure-production
        model-families:
          my-chat-deployment: deployed-chat-family
        request-capabilities:
          deployed-chat-family:
            output-limit: max_completion_tokens
            default-output-tokens: 4096
            temperature: true
            top-p: true
            tools: true
            structured-output: true
            reasoning-efforts: []
    pricing:
      azure-contract:
        revision: operator-contract-v1
        billing-route: azure-production
        model: my-chat-deployment
        mode: token
        currency: USD
        source: operator-contract
        rates-per-million:
          input: 1.00
          output: 4.00
          cache-read: 0.25
```

The example rates are placeholders: supply your deployment's actual contract. OpenAI pools use `api-auth: bearer` (default) and their normal `/v1` base URL. Static Entra tokens may use bearer authentication; automatic acquisition/refresh and legacy Azure versioned deployment routes are not implemented. Neither Azure nor vLLM deployment smoke testing has been performed.

For a reasoning family, set its `reasoning-efforts`, `temperature: false`/`top-p: false` where required, and the documented output-limit spelling. `sampling-only-without-reasoning` and `tools-only-without-reasoning` require an explicit `none` in the declared effort set. Families without Chat Completions tools must declare `tools: false`. Unsupported offered tools/effort combinations are refused rather than silently removing the tool contract. Local pools default to `request-style: local` and preserve their existing request fields.

Client/UI presentation is implemented in item 8 below. Deployment probes, administrative retention controls and measured release performance (item 9) remain separate follow-ups.

## Client usage views and reference comparison

Open **Usage** in the console or the desktop chat inspector. Select a conversation, project, agent, run, orchestration, accessible model report or fleet pool report; fleet visibility retains the server's administrator boundary. The default window is 30 UTC days through today, with an exclusive end at the next UTC midnight so a live subscription includes new attempts today. Direct and descendant scopes are separate selections. **Agent runs** groups direct atomic contributions by immutable run/agent, shows captured ancestors including parents with no calls on the page, and opens each run's authorized inclusive subtree. A partial page is marked; never add inclusive parent and child totals. Model/operation/pool/project/day breakdowns and signed group/call/attempt pagination use the same bounded reads.

Context counting is an explicit read of a conversation and agent's **next** ordinary projection, in a separate disclosure. It does not change cumulative usage or book charges. Call details retain lifecycle, actual wire model, route, price version, timings, per-attempt nullable usage/source/coverage and `ESTIMATED`, `REPORTED`, `INCLUDED`, `ZERO_RATE`, `PARTIAL` or `UNKNOWN` cost modes. No prompts, credentials or provider error bodies are fetched into this audit.

**Reference provider / model** is a quick comparison, independent of those booked prices. It multiplies the selected report's known input/output token totals by a selected reference's input/output USD rates per million. All attempts and operation types in the selected authorized window are included, including retries and embedding input; cache/reasoning subsets are not counted again. It uses the recorded tokens without re-tokenizing or predicting another model's answer. Missing usage yields a partial known subtotal or unavailable amount. Decimal arithmetic preserves sub-cent amounts and 64-bit token quantities.

The dated presets, checked **2026-10-02**, are:

| Reference | Input / million | Output / million | Official source |
| --- | ---: | ---: | --- |
| OpenAI GPT-4.1 mini (default) | $0.40 | $1.60 | [Model pricing](https://developers.openai.com/api/docs/models/gpt-4.1-mini) |
| OpenAI GPT-4.1 | $2 | $8 | [Model pricing](https://developers.openai.com/api/docs/models/gpt-4.1) |
| Anthropic Claude Sonnet 5.5 | $2 | $10 | [Model pricing](https://www.anthropic.com/claude-sonnet-5-5) |
| Anthropic Claude Opus 5.5 | $4 | $20 | [Model pricing](https://www.anthropic.com/claude-sonnet-5-5) |
| Google Gemini 2.5 Flash, standard text | $0.30 | $2.50 | [API pricing](https://ai.google.dev/gemini-api/docs/pricing) |

Select **Custom rates / Azure deployment** for regional, negotiated or other reference rates. Only input and output rates are used: no cache discounts, request tiers, batch discounts, tool fees, taxes, currency conversion or automatic future repricing. The selected default/custom reference is saved in this client's local preferences, separately from server configuration and usage. Changing it sends no model call, price mutation or usage read. Presets are dated comparisons, not a billing quote.

The TypeScript CLI accepts `usage models {"scope":"subtree"}` and other typed one-shot usage frames; subscriptions require a persistent view. The lightweight TUI supports `/usage` for the current conversation and `/usage models --days 30 --reference openai-gpt-4.1`; `bin/plowshare-talk usage project research --direct --json` provides a one-shot report outside the chat. `usage count CONVERSATION AGENT` is separate context counting. Java callers use `HttpServerClient.usage(type, filter)` or hold `usageSocket()` and `watch(type, filter, callback)`; close views to unsubscribe and call `reconnect()` to reissue reads/subscriptions after transport loss. Java callbacks are coalesced away from the socket reader so callbacks may issue further reads.

Views replace complete durable snapshots, ignore old revisions/other subscriptions and check selected filters. They retain marked stale measurements on same-scope outages, clear data when changing account/scope, and create new socket-owned subscriptions after reconnect. Audit pages expose active IDs and snapshot watermark rather than adding speculative streaming counters. Every usage/count read and push uses the authenticated WebSocket; there is no metrics REST route or fallback, and no new fleet-access model tool.
