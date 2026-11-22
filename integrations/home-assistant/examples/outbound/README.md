# Outbound-only Home Assistant profiles

These templates let a Plowshare agent use named HA tools. They do not install a
Conversation Entity, Assist agent or AI Task provider. Install the separate
[Assist component](https://github.com/myrddian/plowshare/blob/main/integrations/home-assistant/assist/README.md) for those features. No external HA system prompt is imported or
rewritten by either profile.

| Template | Application and agent | Exact external tool grants |
| --- | --- | --- |
| `readonly/` | `home-assistant-readonly` / `house_reader` | `ha_read_search`, `ha_read_describe`, `ha_read_read`, `ha_read_list_actions` |
| `control/` | `home-assistant-control` / `house_controller` | The four read tools plus `ha_control_describe_action`, `ha_control_execute_action` |

Each directory has a complete `application/` source root and a matching sibling
`provider.json`. The Applications have different project and agent names; their
ordinary server-owned conversations and histories are separate. The templates do
not create or share conversation IDs. The agents expose no built-in tools,
workspace scopes or delegation. Dynamic tool opt-in and exact manifest scope
assignments remain separate from provider discovery.

## Configure private copies

Copy the chosen directory into private operator storage. Both profiles start with
empty `access.accounts`, inert principal placeholders and nonconnecting example
origins. They issue no live membership or tool grants, and are not deployed by
copying or validating them.

1. Choose the Application's execution service identity and actual served model.
   Replace its `REPLACE_WITH_*_EXECUTION_PRINCIPAL` consistently in
   `application/plowshare.json`, `application/server/tools.json` and the provider
   config. The principal is the authenticated service-token principal; account
   handles used for membership are a different identity. Replace the example
   `reasoning` model if that alias is not configured by the target server.
2. Provision the intended management/work membership and Application access
   entries through the supported operator workflow. Keep human management and
   service execution distinct. The checked-in empty access list is deliberate;
   tool scopes, provider declarations and `dynamic: true` do not provision it.
   See [Application provider scopes](https://github.com/myrddian/plowshare/blob/main/docs/tool-scopes.md).
3. Set explicit Plowshare and HA origins in private `provider.json`, select actual
   permitted entities/attributes and, for control, fixed supported action
   profiles. Keep `homeAssistant.subscriptions` empty. Do not add routes, scripts
   or feedback settings: the named provider host starts no workflows.
4. Supply the configured `PLOWSHARE_READONLY_TOKEN` or `PLOWSHARE_CONTROL_TOKEN`
   and separate `HA_TOKEN` through private secret management. The two providers
   inside the control Application must use the same execution principal and token
   environment reference on one SDK connection. Their logical provider names,
   prefixes, exact grants and private journals remain distinct. Prefer different
   execution service identities between the readonly and control Applications.
5. Set explicit private journal directories. Relative paths resolve beside the
   provider config; `private/read` and `private/control` are example choices,
   not a shared journal. Preserve the original configuration and uncertain
   receipts before rotating identities, credentials, policy or routing. Do not
   delete a journal or replay an action to make a changed configuration start.

The control template's `allowedActions` admits only its configured `lamp_on`
profile. It is an additional local ceiling, not a tool grant. The read-only
template has no control provider, control scope or configured native action.
Neither template grants a wildcard that could silently acquire future controls.

## Validate and run

Use the [packaged host](../../PROVIDER-HOST.md) to check a private configuration:

```sh
plowshare-integration-home-assistant --provider --check /path/to/private/provider.json
```

Checking requires no credentials, sockets, journal creation or server mutation.
It validates typed configuration, not actual target devices, live grants, model
availability or deployment. The Application source is separately validated and
registered/deployed through supported CLI or Desktop Application workflows. Its
static `server/tools.json` authorizes catalogue publication; do not also call
`tool.scope.connect` for these same provider prefixes.

Once the source, membership and explicit tool policy are installed and the host
is separately authorized to run, launch it with `--provider` and the same private
config, omitting `--check`. Normal and uncertain shutdown/recovery follow the
host's retained ownership contract. This template is not a live acceptance result.

## Action and event limits

A receipt is distinct from a physical observation. The model-facing control
profile can obtain a later fresh selected read, while the explicit Java action
verifier remains a separate API; no verification tool or receipt lookup is
invented by these examples. Missing observations stay unverified, and UNKNOWN
actions are never automatically resent.

The named-control journal does not populate the legacy runtime's causal-feedback
index. Do not combine these actions with event-driven investigations assuming
cross-host suppression. Keep those workflow sources independent or use the
legacy runtime-owned action path when its feedback protection is required. See
the [supported composition limit](https://github.com/myrddian/plowshare/blob/main/integrations/home-assistant/OBSERVATION-ROUTES.md#retention-and-causal-feedback).
