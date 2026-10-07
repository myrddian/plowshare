# Integrations

Integrations connect external systems to Plowshare through its public SDK and
server contracts. They run outside the core server, configure their deployment
addresses explicitly and retain only adapter-owned state.

| Directory | Responsibility | Gradle identity |
| --- | --- | --- |
| [runtime](runtime/README.md) | Shared integration lifecycle, policies and local journal | `:plowshare-integrations` |
| [a2a](a2a/README.md) | External A2A protocol adapter | `:plowshare-a2a` |
| [home-assistant](home-assistant/README.md) | Home Assistant binding | `:plowshare-integration-home-assistant` |

Source locations are grouped here; Gradle commands and distribution executable
names remain stable. See the [coding standards](../docs/coding-standards.md) for
the SDK boundary and [SDKs](../sdk/README.md) for the client libraries.

A2A and Home Assistant include deployable Application templates under
`examples/application/`, each identified by its root `plowshare.json`. Register
these roots as Applications so authorized clients list them under **Applications**.
The templates have empty `access.accounts` lists: add explicit deployment account
grants and matching server membership before use. Adapter credentials, endpoints
and journals stay in private deployment configuration. The adapter processes
continue to use the public SDK; a repository directory name or an adapter binding's
`project` field does not establish an Application boundary.

`extensions/` is reserved for services that extend Plowshare capabilities, such
as its search providers. External-system adapters belong in `integrations/`.

An integration that requires core changes is an unsuccessful integration task.
See [ADR 0001](../docs/decisions/0001-integration-boundary.md) for capability gaps,
separate platform authorization and build enforcement.
