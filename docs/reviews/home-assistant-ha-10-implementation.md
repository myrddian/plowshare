# HA-10 implementation: standalone Application profiles

Implementer: `/root/ha_application_profiles_implementer`.
Status: coordinator formatting and selected loader validation passed; source
ready for the HA-10 v1 review candidate. Not independently approved or accepted.

## Delivered source

- `integrations/home-assistant/examples/application/README.md` distinguishes the
  unchanged legacy parent Application/Java `config.json` from three standalone
  deployable roots, documents explicit HA route selection and credential/project
  ownership, and uses artifact-local or canonical-source documentation links.
- `integrations/home-assistant/examples/application/profiles/readonly/` contains
  `house_reader` with four exact read-tool assignments and `house_task` with no
  tool assignments. Its provider authority matches the existing readonly host
  template's project, principal placeholder, namespaces, prefixes and leases.
- `integrations/home-assistant/examples/application/profiles/control/` contains
  separate reader, controller and generation agents in one project/principal.
  Reader gets only read tools; controller gets the four reads plus two explicit
  control tools; generation gets none. Both logical provider declarations match
  the existing control host template and its single authenticated principal.
- `integrations/home-assistant/examples/application/profiles/task/` contains only
  `house_task`, no provider declaration/server directory and no dynamic grants.
- `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/ApplicationProfilesTest.java`
  exercises the actual current manifest, Application server settings, tool-scope
  and agent-definition loaders through integration test-only dependencies.

All agents expose no built-in tools, workspace scopes, agent-call edges or
orchestrations, and are exported but nondelegable. Generation explicitly disables
dynamic opt-in. Local/server commands are off. Agent and manifest caps are 20
steps and 40 model calls, without automatic increases. Empty access ceilings and
execution-principal placeholders are deliberately inert pending operator setup.
There is no default agent. Read-only HA routing names `house_reader`, explicit
control routing names `house_controller`, and AI Task names `house_task`; one HA
entry uses one project and credential, with no route fallback.

The parent legacy Application, Java `examples/config.json`, and independently
accepted outbound templates are unchanged. No runtime, core, shared SDK/protocol,
SQL or schema source is changed by HA-10.

## Validation contract and actual status

The new parameterized loader suite has 18 cases. It verifies three standalone
roots load; inert access boundaries and finite capability ceilings; exact
read/control/generation assignments; denial for other principals, providers,
missing agents and future tools; provider-host identity/configuration agreement;
provider-free generation; missing assignment denial; loader rejection of
assignment to a missing or non-dynamic reader and of assignment to the
non-dynamic generator; and refusal under another project identity.

The implementer ran no Gradle, tests, formatters, build, install or live calls.
Coordinator evidence: `/private/tmp/ha-10-profile-check-v1.log`. Repository Google
Java formatting passed; the selected loader run, `formatCheck` and
`integrationBoundaryCheck` passed in 7 seconds across 68 tasks. Actual test XML:
`ApplicationProfilesTest` 18 cases, `ProjectExamplesTest` 1 case and
`OutboundProfilesTest` 12 cases, all with zero failures/errors (31 total).
The legacy and accepted outbound loader cases therefore remain compatible with
the new standalone roots. The coordinator also verified all 4,042 protected
core/protocol/SDK/shared-runtime file hashes were unchanged after formatting.
Independent review remains required; these checks do not establish live grant
issuance, deployment or device acceptance. Separate SDK package consumer checks
are not counted as profile-loader evidence.

## Supported limits

Offline parsers prove source/manifest consistency and owner-policy ceilings;
they do not issue membership/grants or prove live authentication, model alias
availability, provider publication or physical effects. HA text uses the existing
SDK task input with ordinary task/user semantics, not native system-message
priority. Prompt/cache rewriting is not performed. The task agent uses supplied
data only and cannot acquire house grants from a prompt. Named-control effects
remain separate from legacy workflow causal-feedback ownership. UNKNOWN actions
must retain original work and must not be automatically repeated.

Deployment, publication, HA restarts and live model/device work were not performed.

## Frozen authored source inventory

There are 11 profile files, one parent README and one new Java loader test.
SHA-256 values below describe the post-format review source; this implementation
report is excluded from its own hash inventory.

| File | SHA-256 |
| --- | --- |
| `integrations/home-assistant/examples/application/profiles/control/agents/house_controller.md` | `7d13c6b4dac465bdf4cbe25a27734022d5f1b8debea33e481161a512f592c896` |
| `integrations/home-assistant/examples/application/profiles/control/agents/house_reader.md` | `612adbc35d7f9c334217fe892d98ed6b628c67baad57c88bec421410cc4e1d11` |
| `integrations/home-assistant/examples/application/profiles/control/agents/house_task.md` | `e7c57ace2b999b93b1159b2af03f2d8e22f1a49a84af818f35c0891630dc82f3` |
| `integrations/home-assistant/examples/application/profiles/control/plowshare.json` | `3cb54dc74300883f515aed83f606b7ebacc4ca001e91e938be0625e8749c921a` |
| `integrations/home-assistant/examples/application/profiles/control/server/tools.json` | `d6cae5e5a4ea7ae1681bbcf6dd75bebcfe04e3c54b21f0316a228b9c7104763f` |
| `integrations/home-assistant/examples/application/profiles/readonly/agents/house_reader.md` | `612adbc35d7f9c334217fe892d98ed6b628c67baad57c88bec421410cc4e1d11` |
| `integrations/home-assistant/examples/application/profiles/readonly/agents/house_task.md` | `e7c57ace2b999b93b1159b2af03f2d8e22f1a49a84af818f35c0891630dc82f3` |
| `integrations/home-assistant/examples/application/profiles/readonly/plowshare.json` | `1066830364dbdadf9e05d8c52f3fd698ed82150e21bf1bc327b7ef88dc379803` |
| `integrations/home-assistant/examples/application/profiles/readonly/server/tools.json` | `9418cd32d5f64a8c3e34bab9027a23e4c26b3e8221d9092eae19e1a4e02cdb0c` |
| `integrations/home-assistant/examples/application/profiles/task/agents/house_task.md` | `e7c57ace2b999b93b1159b2af03f2d8e22f1a49a84af818f35c0891630dc82f3` |
| `integrations/home-assistant/examples/application/profiles/task/plowshare.json` | `eb30e577c8057fc4f1a0310e1a6e1ead302170bf2d67031abb23150ae7ab3fb1` |
| `integrations/home-assistant/examples/application/README.md` | `d3b142dfaeada6683c44d3dd13bd1c523b080936abe6f4c48eccf7ec1cb1072b` |
| `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/ApplicationProfilesTest.java` | `8e33303b62bb78c5f31ebdc0243bbc7d3fa1dd53429d9b855f40ab49f042625e` |
