# Plowshare Assist

Plowshare provides a Home Assistant Conversation agent for Assist and an optional
AI Task entity through the existing public Python SDK. HA owns speech-to-text,
speech recognition language, voice pipelines and text-to-speech. Select the
Plowshare conversation entity in an Assist pipeline after installation. This
component does not install a speech engine or run an HA-owned background agent.

The verified test baseline is **Home Assistant 2026.9.4 with Python 3.14**. Your
running HA version has not been inspected. Check that version before installation;
older/newer versions have not been validated by this delivery. No live HA, model
or device acceptance is implied by offline checks.

## Build and install

From the Plowshare source checkout:

```sh
python3 integrations/home-assistant/assist/scripts/package.py --output /path/to/plowshare-assist.zip
```

The archive contains `custom_components/plowshare`, this `INSTALL.md`, the
[Application profile instructions](profiles/README.md), three independent profile
roots under `profiles/`, and a SHA-256 `package-manifest.json`. In the source
checkout those profile instructions are [PACKAGE-PROFILES.md](https://github.com/myrddian/plowshare/blob/main/integrations/home-assistant/assist/PACKAGE-PROFILES.md).
The unchanged public SDK, its generated contracts/resources and LICENSE are
bundled privately under `custom_components/plowshare/_vendor`. HA installs the
manifest's `websockets>=17,<18` dependency. Do not manually install a different
SDK into HA or copy the source component without its generated bundle.

Extract the ZIP into a private staging directory. Copy only
`custom_components/plowshare` into your HA configuration directory's
`custom_components` directory, giving the final path
`<HA config>/custom_components/plowshare/manifest.json`. Keep profile templates
outside the HA custom-component directory. Preserve any existing private HA
`.storage/plowshare.<entry_id>` journals and HA config entries. Take a backup and
stop HA cleanly for an operator-authorized replacement; restart it afterward.
Do not restore an older journal over newer pending/uncertain receipts on rollback.
This delivery does not perform that restart or deploy to your instance.

## Configure explicit routes

Provision a chosen Application profile and, for house reads/control, the separate
[named provider host](https://github.com/myrddian/plowshare/blob/main/integrations/home-assistant/PROVIDER-HOST.md).
Generation-only tasks require no device provider or HA service token. In HA,
choose **Settings → Devices & services → Add integration → Plowshare**. Supply an
explicit HTTP(S) Plowshare origin, private project-scoped service `api_key`, and
project. The one entry uses that single credential/project for both platforms.
The credential authenticates all requests; HA user/device/satellite identifiers
partition histories and grant no Plowshare authority.

| Mode | Configuration fields |
| --- | --- |
| Read-only Assist (default mode) | `conversation_enabled: true`, `control_enabled: false`, explicit `read_only_agent` |
| Control Assist | `conversation_enabled: true`, `control_enabled: true`, explicit `agent` |
| Optional AI Task | `ai_task_enabled: true`, explicit `task_agent` |
| AI Task only | `conversation_enabled: false`, `control_enabled: false`, `ai_task_enabled: true`, explicit `task_agent` |

At least one platform must be enabled. There are no fallback agents. Control and
AI Task default off; Conversation defaults on. An optional `prompt` is an HA
prompt template. `request_topic` must match any Relay path used by the selected
agent. Anonymous voice access defaults off; opting in still requires device
identity. The control feature flag advertises HA capability; actual read/control
permissions come from server membership, Application policy and exact agent/tool
grants. Select a real reader agent for read-only operation. Setting a UI flag
cannot remove a controller's existing grants.

Initial setup/reconfigure validates all enabled agent names with one bounded SDK
catalogue read and retained cleanup ownership. Reconfigure supports credential,
prompt and timeout updates on the same route. Changing origin, project, topic,
agent or control mode requires a separate entry. Enabling another profile changes
the route and likewise requires a new entry. The old entry and its receipts must
remain available for inspection until outstanding work is resolved.

The optional AI Task entity supports text `generate_data`, including a closed,
flat output schema using text, finite number, boolean and select fields. Optional
fields are supported; additional keys, type coercion and schema defaults are not.
Attachments, images, image generation, evaluation, nested/custom schemas and HA
LLM tool APIs are unsupported and rejected before SDK submission. Invalid
completed structured data returns an HA error without repeating model work.
A repeated HA task action is a **new invocation**, not a durable retry identity.
Inspect retained work before repeating an action after an uncertain response.

## Prompt semantics and caching

HA prepares the effective system text and extra context through its own chat-log
APIs. The adapter preserves that text and each new utterance/instruction in the
existing SDK task envelope. The effective Plowshare role is **ordinary task/user
input**, not a native per-run system role. Plowshare's selected agent/system rules,
permissions, approvals, limits and retention remain authoritative. HA identity
fields are used for partitioning, not inserted as model prose.

The adapter does not normalize, reorder, repair or rewrite HA prompts to improve
prefix caching. HA template variation and changing supplied context can reduce
prefix reuse; actual cache impact depends on the provider and model projection.
No cache guarantee or transparent prompt correction is claimed. Plowshare owns
conversation history; the adapter sends the new utterance/context instead of
resubmitting HA's full transcript. Ordinary platform retention/compaction applies.
For spoken answers, configure the chosen agent to aim for roughly 40–80 words,
allowing modest overruns and preserving important qualifications.

## Sessions, receipts and recovery

New Assist sessions create a separate external conversation alias through
`conversation.open.ex`; `agent.run.ex` resolves that alias on the server. The
server owns external-to-internal ID mapping. The local journal owns submission
fences, job IDs and reconciliation cursors, not a competing alias map. Caller,
entry, selected profile and HA chat identity partition work. Direct Conversation
API calls begin without a conversation ID; arbitrary unowned IDs are refused
before the adapter opens a log or submits SDK work. Assist instead allocates its
session and opens a log before calling the entity. The adapter accepts that
first-turn ID only when HA's active session and log contexts match the request,
with an empty system message and only the current user text: no prior history,
extra context, attachments or prepared LLM state can be inherited. It claims that
log synchronously before its own context preparation or SDK work. A foreign
source cannot reuse an owned log. HA's outer pipeline can already have appended
input before the entity runs; the adapter cannot undo that upstream behavior.

Entity reloads preserve in-process ownership. After a process restart, direct
requests cannot import an unowned retained log. For a direct owned ID that expires,
the adapter reports the replacement ID as fenced without submitting replacement
work. HA's native pipeline can expire/remap an old ULID before the entity sees it;
that is a new visible HA session and uses a new external alias. The adapter has
no original-ID provenance at that boundary and does not promise native old-ID
continuity or detection of the earlier session. Existing SDK receipts remain
retained, without automatic cancellation or replay. Inspect outstanding work in
Plowshare before deliberately starting a fresh session after uncertainty.

Intent is durably saved before transmission. Disconnects, timeouts, cancellation
or ambiguous refusals never cause automatic replay. A completed answer is spoken;
reasoning, partial/failed output and tool results are not. A known accepted job
can outlast HA's response wait and continue on Plowshare. A follow-up in the same
owned chat first reconciles its existing job and returns that work's result;
that follow-up is not submitted as another instruction while work is pending.
There is no automatic later TTS announcement or streaming in this version.
A Relay publication/service acknowledgment is separate from a correlated answer
and from observed physical device success. Never repeat a physical command solely
to obtain cleaner evidence.

Private journals retain correlation phases/IDs/cursors and routing fingerprints,
not prompts, transcripts or raw service tokens. Changing the credential changes
its fingerprint: retained work from the earlier credential remains fenced rather
than assuming identical account authority. Legacy four-field local-map records
are quarantined even if idle. There is no automatic import of old local aliases
into the server mapping, deletion of uncertain records, or ordinary-operation
fallback. Corrupt/unsupported journal data prevents startup and is preserved.
Keep the prior journal plus exact work IDs for operator reconciliation; deleting
state, changing entries/credentials or restoring a stale backup is not recovery.

## Budgets and lifecycle

Each profile admits at most four physical owners; one key has one foreground
owner. Stalled/failed cleanup retains its slot and blocks unsafe replacement.
The shared journal is bounded to 128 session/receipt records and 1 MiB; pending
or uncertain records cannot be evicted to make space. Up to eight physical writes
can be outstanding. A full journal/refused admission requires inspection rather
than discarding work. Control/read/acceptance SDK requests default to two seconds
(configurable up to 30); the foreground response wait defaults to 30 seconds
(configurable up to 120), including connection/submission/polling/storage.
Nested requests consume the remaining wait budget. Physical journal/transport
cleanup has its own two-second observation wait and remains owned afterward if
unsettled. These are bounded waits, not an assertion of immediate physical abort.
Unload/reload refuses disposal/replacement while resources remain unsettled.
Closing the connection or stopping HA does not cancel an accepted server job.

New conversations request finite limits of 40 model calls and 40 steps, intersected
with the selected Application/server limits (the packaged profiles cap steps at
20). The component does not approve tools, increase allowances or cancel jobs
automatically. Input text is bounded to 16,000 characters, HA prepared context to
256 KiB UTF-8, and the serialized task envelope to 320 KiB. Structured parsing has
separate finite byte/field/list limits. These protocol/resource limits are not
exact prose targets. Approvals, exhausted allowances and retained work are
resolved through Plowshare's existing operator controls.

## Verification

From a checkout, create an isolated Python 3.14 environment and install this
directory's check extras:

```sh
python3 -m venv /path/to/ha-check-env
/path/to/ha-check-env/bin/pip install './integrations/home-assistant/assist[check]'
/path/to/ha-check-env/bin/python integrations/home-assistant/assist/scripts/check.py
```

Checks use real HA APIs plus typed fakes/loopback SDK peers; no live HA, provider,
model or PostgreSQL is required. They cover configuration, text/structured tasks,
session isolation, setup/unload, receipt preservation, token fencing, quarantine,
SDK byte/resource integrity and a fresh extracted artifact consumer outside the
checkout. Repository-wide `./gradlew check` and SDK distribution checks are
separate. Offline checks establish neither deployed identity nor live voice,
authority, device state or notification delivery. Follow the
[release and live acceptance plan](https://github.com/myrddian/plowshare/blob/main/docs/home-assistant-delivery-plan.md)
for separately authorized operational work.
