# OUT-PROFILE-01 implementation

Implementer: `/root/ha_outbound_profiles_implementer`.
Baseline: main `6de685f6`; approved OUT-PLAN-01 v2 host contract.
Status: implementation verified and ready for independent review. This report
is not an approval or deployment. Integrated outbound packaging validation remains
a separate successor deliverable.

## Behavior

Two complete outbound-only Application roots have different project and local
agent names: `home-assistant-readonly` / `house_reader`, and
`home-assistant-control` / `house_controller`. Each includes root manifest,
dynamic exported nondelegable agent and static provider authority in
`server/tools.json`. Neither agent has built-in tools or workspace scopes.

The readonly profile grants exactly the four named read tools and has no native
actions or control provider. The control profile additionally grants exactly the
two named control tools to its local controller; `lamp_on` is its only selected
fixed HA action. Read/control providers inside that Application share the one
explicit execution principal and SDK credential reference required by current
public policy. Logical providers, prefixes and private journals remain separate.
The two Applications use different inert principal placeholders and credential
references from one another.

All membership access lists are empty. Exact manifest tool policy, static
catalogue authority and dynamic opt-in issue no live grants. Example origins do
not connect to a deployment. Operator guidance distinguishes execution principals,
membership handles and credentials, requires private configuration, and preserves
retained unknown work during changes. The configured host starts no workflows;
the examples neither introduce nor assume cross-host feedback suppression.
Acknowledgement, observed state and causality remain separate evidence.

No Assist/Conversation/AI Task implementation, HA prompt handoff, prompt rewriting,
core/shared SDK change, provider connection, device action or model invocation is
part of this node. The original prompt and operational gates remain unchanged.

## Authored files

- `integrations/home-assistant/examples/outbound/README.md`
- `integrations/home-assistant/examples/outbound/readonly/provider.json`
- `integrations/home-assistant/examples/outbound/readonly/application/plowshare.json`
- `integrations/home-assistant/examples/outbound/readonly/application/server/tools.json`
- `integrations/home-assistant/examples/outbound/readonly/application/agents/house_reader.md`
- `integrations/home-assistant/examples/outbound/control/provider.json`
- `integrations/home-assistant/examples/outbound/control/application/plowshare.json`
- `integrations/home-assistant/examples/outbound/control/application/server/tools.json`
- `integrations/home-assistant/examples/outbound/control/application/agents/house_controller.md`
- `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/OutboundProfilesTest.java`

The packaging implementer owns the host parser/lifecycle, build and parent
documentation; the coordinator owns the plan and ledger. This node did not edit
those files or historical frozen artifacts.

## Verification

A static check parsed all six example JSON files and confirmed project identities,
matching execution/provider principals, prefixes and leases, empty access and
subscriptions, and exact nonwildcard grant structure.

`OutboundProfilesTest` contains twelve parameterized cases. It uses current real
host, manifest, provider, agent and package parsers without starting a server,
database, device or inference model. It checks complete-profile consistency with
actual named tool declarations, exact positive/negative policy matrices, missing
assignments, missing/nondynamic local agents and wrong-project loading. The
package validator explicitly uses no fleet checks; passing parser tests would not
prove a model is served or that live authority/devices exist.

The coordinator formatted the Java source and ran the actual focused Gradle
checks against the typed host configuration dependency. All **12 profile cases
passed**, together with 24 host configuration cases, with zero failures. The
combined invocation completed successfully in **8 seconds** (67 tasks). The
coordinator retained the command log at
`/private/tmp/ha-outbound-early-check.log`; its SHA-256 is
`446d2160b3ea97e74742bffc099271e80d5a8e3fd560887f99c37bb93c502490`.

The checks establish these profile/parser/policy contracts on the compiled source;
they do not establish installed-artifact, real-server, model, device or deployment
acceptance. OUT-VERIFY-01 will validate the integrated accepted host and profile
artifacts separately. Independent review of this exact source is still required.
The implementer ran no Gradle, formatter, PostgreSQL or Git mutation.

## Checked source hashes

These hashes bind the final ten source/example files after coordinator formatting.
The typed host dependency is owned and reviewed by OUT-PACK-01, not this node.


```json
{
  "integrations/home-assistant/examples/outbound/README.md": "2225594dd9ec051072cdad30535b18feb700510615354a2122fba0ed8638bf9d",
  "integrations/home-assistant/examples/outbound/control/application/agents/house_controller.md": "bff6da8a458305319bc4dd492baefd06cc80f0060456289627a72513882ea72d",
  "integrations/home-assistant/examples/outbound/control/application/plowshare.json": "2dffe9ec1a887b1247b41305184cef3f711987ab3adf6005acd1b99935468986",
  "integrations/home-assistant/examples/outbound/control/application/server/tools.json": "d6cae5e5a4ea7ae1681bbcf6dd75bebcfe04e3c54b21f0316a228b9c7104763f",
  "integrations/home-assistant/examples/outbound/control/provider.json": "a64433e52b85e5254848723e0ae99f7e0da32faceb58295c469e4caf58591e96",
  "integrations/home-assistant/examples/outbound/readonly/application/agents/house_reader.md": "cbac98269c70febff18164898616c9a8147634c1131ac4a444593cd88ba532ec",
  "integrations/home-assistant/examples/outbound/readonly/application/plowshare.json": "0be8df301b6db5298135a5575b6f4e23e0186646f08927f7020f8801f89f3785",
  "integrations/home-assistant/examples/outbound/readonly/application/server/tools.json": "9418cd32d5f64a8c3e34bab9027a23e4c26b3e8221d9092eae19e1a4e02cdb0c",
  "integrations/home-assistant/examples/outbound/readonly/provider.json": "95a7fcd5308e56db9d42772d64fb4db45120ad611abac6a40a69a650cbf5aa2b",
  "integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/OutboundProfilesTest.java": "a2d999582ae59358f5ae33a51774cc8f15ac3234d381edbe47d8e03d5379d216"
}
```
