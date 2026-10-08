# Application-scoped live tool registry

Status: accepted for implementation following explicit platform authorization.

## Need and boundary

Application deployment previously checked agent grants against startup-only tool
names. Adding an external capability required editing global server configuration
and restarting before deploying its Application. Dynamic providers, including MCP
catalogue adapters, need independent project lifecycle and revocation.

An SDK-only adapter can expose HTTP/A2A/outgoing capabilities, but cannot register
normal model tool names in a server registry that has already started. The general
platform change is a live scoped tool registry and declarative Application authority.
External adapters still consume public SDK operations; no adapter imports server
internals or starts an agent runtime. Python processes remain external.

## Design

Built-ins stay registered at startup. `ScopedTools` resolves additional schemas
from Application-root `server/tools.json`. `server/ports.json` grants exact project
Relay ingress/egress. Source deployment validates these typed bounded declarations
before checking agent grants and commits them with its existing durable revision.
Only server administrators deploy authority; ordinary file edits cannot change it.
This is not a Spring configuration overlay.

Explicit provider authority binds authenticated account, provider and tool-name
prefix. Public SDK facades publish/withdraw a typed declaration snapshot on the
existing project-local Relay catalogue topic. Authentication determines publisher
identity. The broker owns ordering, publication time, idempotency and retention;
no new lifecycle or transport is introduced. Latest retained position wins. Leases
expire availability; withdrawal and expired broker history never revive bootstrap
schemas. Discovery alone cannot grant an agent or widen membership. Two projects
can have different schemas for the same dynamic name.

Every declared tool call rechecks account membership and current agent grants.
Dynamic tools also recheck provider identity, schema and lease. The offered schema
is pinned so changed declarations cannot reinterpret old arguments. Revocation
prevents the next admitted call, not an effect already admitted. Existing run
extras retain their own lifecycle authority. Failures carry stable `E_NO_ACCESS`,
`E_NO_CONNECTION`, `E_NO_EXEC` or `E_GENERAL_TOOL_FAILURE` codes and safe diagnostics.
UNKNOWN external effects retain invocation identity and require read-only
reconciliation; no error code authorizes automatic mutation replay.

Authority follows activation/rollback of Application source, while catalogue
metadata is still the provider's newest durable publication within that authority.
Catalogues refresh definition caches without rebooting. Lease renewals publish fresh
metadata with caller-retained UUIDs. All SDK languages provide equivalent publish
and withdraw methods; Node reuses the TypeScript facade.

## Compatibility and limits

Legacy global Relay bindings remain startup configured and keep their same-schema
restriction. Application providers cannot shadow built-ins or those bindings.
Applications without `server/` add no authority. Existing WS operations, tool
invocation receipts and database schema remain unchanged. A repository-owned latest
Relay head read supplies bounded catalogue reconciliation without subscriber offsets.

This prepares dynamic discovery for future MCP adapters; it does not implement MCP
transport or arbitrary JSON Schema. The existing four scalar parameter types remain
supported. Integration-specific scanners and HTTP UI stay outside core.

## Verification

Mocked service tests cover per-call revocation, project/account isolation, changed
schemas, leases, withdrawal, expired history, stable failures and no uncertain-effect
replay. Strict configuration tests reject malformed/coerced values and links. SDK
provider tests cover scoped catalogue payloads, withdrawal, receipt validation and
lost-response behavior. Application parser tests cover the packaged Network Privacy
Watch declarations. Native SDK/compiler/transport checks and repository `check`
are required; no live collector deployment is claimed by these tests.
