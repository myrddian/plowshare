# Home Assistant Application examples

The parent `plowshare.json`, `agents/house_coordinator.md`, skills and orchestration
are the **legacy selected-observation and Relay workflow** example. Its matching
Java integration configuration is [../config.json](../config.json). That file is
not a Home Assistant config entry and does not enable Assist or AI Task. Keep its
feedback-dependent event workflows separate from the named provider host.

The standalone [profiles/readonly](profiles/readonly),
[profiles/control](profiles/control) and [profiles/task](profiles/task) directories
are alternative Application source roots for the SDK-based HA entities. Copy and
deploy one chosen root through the supported Application workflow; do not deploy
the parent directory expecting nested profiles to become separate Applications.
They are templates, not installed Applications or live acceptance evidence.

| Source root | Project | Agents and exact dynamic tool assignments |
| --- | --- | --- |
| `profiles/readonly` | `home-assistant-readonly` | `house_reader`: four capability `ha-read` tools; `house_task`: none |
| `profiles/control` | `home-assistant-control` | `house_reader`: six `ha-read` tools; `house_controller`: those six plus two `ha-control` tools; `house_task`: none |
| `profiles/task` | `home-assistant-task` | `house_task` only; no provider or dynamic tool grants |

The read-only capability tools are `ha_read_health`, `ha_read_resolve`,
`ha_read_describe_fields` and `ha_read_read_fields`. Match them to the explicit
capability configuration in the read-only outbound template. Its selection and
field profiles govern discovery and preserve source-qualified measurement roles.
The optional control root retains the legacy read tools `ha_read_search`,
`ha_read_describe`, `ha_read_read`, `ha_read_list_actions`, `ha_read_health` and
`ha_read_diagnose`. Control adds only `ha_control_describe_action` and
`ha_control_execute_action`. Scope assignments name individual agents and tools;
future catalogue entries are not automatically granted. The task agent explicitly
sets `dynamic: false`. Every agent has empty built-in tools, workspace scopes,
agent-call edges and orchestrations; delegation and local/server commands are off.
Manifest caps are 20 steps and 40 model calls, with automatic increases disabled.
No default agent route is supplied.

## Configure a private Application copy

1. Choose the actual model alias and replace `reasoning` if the target server does
   not serve it. Replace the chosen manifest's `REPLACE_WITH_*_EXECUTION_PRINCIPAL`
   with the actual execution principal. Empty `access.accounts` is deliberate:
   provision intended work membership and Application access through the supported
   operator workflow. Manifest scope ceilings do not issue live membership/grants.
   Keep human management and service execution distinct; see the
   [Application authority documentation](https://github.com/myrddian/plowshare/blob/main/docs/tool-scopes.md).
2. For house access, privately copy the matching
   [outbound readonly](../outbound/readonly/provider.json) or
   [outbound control](../outbound/control/provider.json) configuration. Use this
   chosen profile as the Application root, rather than also deploying the
   outbound template's sibling Application. Preserve the matching project name,
   provider names, prefixes and lease settings. The Application execution
   principal, every named provider's `account`, and the authenticated HA entry
   caller must match **exactly**. Membership handles are not substitutes for
   service-token principals. No cross-account tool registry visibility is assumed.
3. Set explicit private Plowshare and HA origins, secret environment references,
   selected entities/attributes, fixed allowed actions and journal paths in the
   provider config. All read/control providers in one host use one execution
   principal and SDK credential, with distinct logical providers and journals.
   Start the separately authorized host using the
   [packaged host instructions](../../PROVIDER-HOST.md). Static
   `server/tools.json` authorizes provider publication; do not also call
   `tool.scope.connect` for the same prefixes. Checking a provider config is
   offline validation, not a model/device call or proof of live authority.
4. The task-only root needs no provider host, HA token, `server/tools.json` or
   device catalogue. The task agent included in the other roots is equally
   provider-free: generation cannot acquire the reader/controller assignments.

The control root intentionally keeps reader, controller and task agents in one
project and execution principal. Their permissions differ by exact agent
assignment. A single HA config entry has one Plowshare URL, `api_key` and `project`
for both enabled platforms. Separate projects/principals require separate entries;
credentials and project identity are never inferred from an agent name.

## Select explicit HA routes

After the independently verified HA component is available, configure the entry
through HA's Plowshare setup flow. These are configuration field choices, not a
JSON/YAML import format. Supply the explicit Plowshare `url`, `api_key` and chosen
`project`; keep credentials out of tracked files.

| Entry mode | Explicit fields |
| --- | --- |
| Read-only Assist | `conversation_enabled: true`, `control_enabled: false`, `read_only_agent: house_reader` |
| Control Assist, control root only | `conversation_enabled: true`, `control_enabled: true`, `agent: house_controller` |
| Add supplied-data AI Task to either house mode | `ai_task_enabled: true`, `task_agent: house_task` |
| Supplied-data AI Task only | `conversation_enabled: false`, `control_enabled: false`, `ai_task_enabled: true`, `task_agent: house_task` |

Defaults enable Conversation, disable control and disable AI Task. An enabled
route requires its corresponding explicit agent field; there is no fallback from
`read_only_agent` to `agent`, or from Conversation to `task_agent`. At least one
platform must be enabled. Route/mode changes require a new entry under the current
config-flow contract. The `control_enabled` flag advertises capability to HA;
actual permissions come from the selected Application and server grants. Merely
setting that flag false cannot remove a controller agent's grants. Use the named
reader route for read-only behavior and retain the manifest assignments.

AI Task generates from supplied instructions/data. It does not query current
house state or perform device effects. Supported output/schema forms are those
validated by the HA adapter; these examples add no attachments, images, HA LLM
tool API or automatic tool translation. HA text is preserved in the existing SDK
task envelope with ordinary task/user semantics, not a native per-run system role.
Prompt text grants no capabilities and is not rewritten for prefix-cache reuse.

For control, a service acknowledgement differs from observed physical success.
Retain uncertain work and receipts; never resend UNKNOWN actions to obtain a
cleaner result. Named-control actions do not populate the legacy observation
runtime's causal-feedback index. See the
[supported composition limit](https://github.com/myrddian/plowshare/blob/main/integrations/home-assistant/OBSERVATION-ROUTES.md#retention-and-causal-feedback).

These templates create no conversation mappings, grants or credentials merely by
being copied. Offline loader validation proves source/ceiling consistency, not
live model availability, registry publication, authentication or device behavior.
Installation, restarts and live model/device checks remain separate operator work.
