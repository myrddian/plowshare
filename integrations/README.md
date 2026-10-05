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

`extensions/` is reserved for services that extend Plowshare capabilities, such
as its search providers. External-system adapters belong in `integrations/`.

An integration that requires core changes is an unsuccessful integration task.
See [ADR 0001](../docs/decisions/0001-integration-boundary.md) for capability gaps,
separate platform authorization and build enforcement.
