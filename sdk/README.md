# SDKs

All public SDK variants live here. SDKs expose the persistent server runtime;
they do not own another durable agent runtime.

| Directory | Responsibility | Package or Gradle identity |
| --- | --- | --- |
| [java](java/README.md) | Java WebSocket SDK | `:plowshare-sdk` |
| [typescript](typescript/README.md) | Neutral TypeScript transport and typed operations | `plowshare-client-ts`, `:plowshare-client-ts` |
| [node](node/README.md) | Node transport, credentials and filesystem support | `plowshare-client-node`, `:plowshare-client-node` |
| [python](python/README.md) | Python SDK | `plowshare-sdk` |
| [go](go/README.md) | Go SDK | `io.aeyer/plowshare/sdk` |
| [dotnet](dotnet/README.md) | .NET SDK | `Plowshare.Sdk` |

Directory moves do not change published coordinates or Gradle task names.
See the [SDK guide](../docs/sdks.md) for build, conformance and package checks.
All variants follow the [coding standards](../docs/coding-standards.md), with
[TypeScript](../docs/typescript-standards.md) and
[native SDK](../docs/native-sdk-standards.md) requirements for their languages.
External SDK consumers belong in [integrations](../integrations/README.md).
