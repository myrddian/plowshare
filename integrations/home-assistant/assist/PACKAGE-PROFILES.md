# Application profiles in the Assist archive

These are three independent Application source roots. Copy and deploy **one**
chosen root through the supported Plowshare Application workflow. Do not deploy
the enclosing `profiles` directory expecting nested roots to register themselves.
The archive ships their source bytes unchanged; they are templates, not live
Applications, grants, credentials or evidence that devices were tested.

| Directory | Project | Explicit agents |
| --- | --- | --- |
| [readonly](readonly/) | `home-assistant-readonly` | `house_reader` with four capability-mode read tools; `house_task` without tools |
| [control](control/) | `home-assistant-control` | `house_reader`, `house_controller` with read plus two control tools, `house_task` without tools |
| [task](task/) | `home-assistant-task` | `house_task` without providers/tools |

In a source checkout, the roots are under `examples/application/profiles` next to
the HA integration. These relative links describe the extracted archive layout.

Replace the chosen manifest's `REPLACE_WITH_*_EXECUTION_PRINCIPAL` with the exact
service execution principal and choose an available model alias (templates use
`reasoning`). Provision intended Application access and server membership through
the supported [Application authority workflow](https://github.com/myrddian/plowshare/blob/main/docs/tool-scopes.md).
Empty `access.accounts` is intentional. Manifest ceilings are not grants.
Commands, delegation, automatic allowance increases and workspace tools are off.
Each root caps work at 20 steps and 40 model calls.

Task-only use requires no provider host or HA token. Read/control use requires a
separately configured [named provider host](https://github.com/myrddian/plowshare/blob/main/integrations/home-assistant/PROVIDER-HOST.md)
and its [readonly/control provider configuration](https://github.com/myrddian/plowshare/tree/main/integrations/home-assistant/examples/outbound).
Those host binaries/configs are separate distribution contents, not hidden inside
this HA ZIP. The manifest execution principal, provider accounts and authenticated
HA entry caller must match exactly; a membership handle is not a substitute.
Match project names, prefixes and private endpoint/credential/journal settings.
Use the selected profile root rather than simultaneously deploying the outbound
example's sibling Application. Do not additionally connect the same static tool
prefixes through another registration path.

The control root intentionally supplies reader/controller/task agents in one
project/principal, permitting a single HA entry to select each route explicitly.
Their grants differ by exact agent/tool assignment. Merely setting an HA UI flag
cannot change server authority. Future provider tools are not automatically
granted. The read-only root grants capability-mode `ha_read_health`,
`ha_read_resolve`, `ha_read_describe_fields` and `ha_read_read_fields`. Configure
the matching provider discovery mode; old static data handlers are not published
in that mode, so they cannot bypass its field exclusions or exposure revocation.
The control root retains legacy `ha_read_search`, `ha_read_describe`,
`ha_read_read`, `ha_read_list_actions`, `ha_read_health` and `ha_read_diagnose`;
the controller adds `ha_control_describe_action` and
`ha_control_execute_action`. Generation agents disable dynamic tools and gain no
device access. See [INSTALL.md](../INSTALL.md) for HA field selection and lifecycle.

A control acknowledgment is distinct from observed physical success. Named
providers do not populate the legacy observation runtime's causal-feedback
index; follow the documented [composition limit](https://github.com/myrddian/plowshare/blob/main/integrations/home-assistant/OBSERVATION-ROUTES.md#retention-and-causal-feedback).
Keep uncertain receipts and never replay an UNKNOWN action. HA prompt/context
uses existing ordinary task/user input, supplies no authority and is not rewritten
for prefix caching. Images, attachments and HA LLM tool translation are unsupported.
