# ADR 0016: Application declarations inherit the execution account

Status: accepted.

## Platform need and authorization

Applications currently repeat the same execution principal in the manifest,
provider declarations, Relay ports and worker enrollment. Repeated authority can
become inconsistent during provisioning or rotation. Any Application with an
external provider has this problem; it is independent of Network Privacy Watch.
The user authorized reducing setup duplication and addressing the supplied review
findings. This is a separately scoped configuration-loader change, alongside
independent integration changes, under ADR 0001.

## Decision

Root `plowshare.json` `executionAccount` is the default for omitted `account`
fields in `server/tools.json` bindings/providers, `server/ports.json` bindings,
and `server/relay-workers.json`. Resolve omission at the validated configuration
boundary, before constructing concrete authority records. Never infer authority
from the deploying manager, connected user, provider name or project membership.

Explicit accounts remain valid and retain precedence, including identities equal
to the default and distinct identities in applications with multiple providers.
Explicit null, empty, wrong-type and malformed accounts are refused. Omission
without a valid manifest execution account is refused. A missing worker file still
means no worker enrollment; `{"version":1}` explicitly requests enrollment under
the default. Changing the manifest changes inherited declarations on the existing
runtime refresh path. Invalid replacements fail closed instead of retaining grants.

Inheritance does not create accounts, issue credentials, add project membership,
change roles, widen provider scopes, grant agents tools or transfer schedule
ownership. Existing authorization/lifecycle checks consume concrete identities as
before. All existing SDK languages retain their current operation contracts.

## Alternatives and consequences

The SDK-only alternative is patching the same principal into multiple private
files. It works but cannot remove the underlying redundant configuration contract.
A symbolic self identity or implicit manager default would obscure authority;
neither is accepted. Manifest-owned provisioning is a separate lifecycle design.

Existing packages with explicit accounts need no conversion or migration. New
packages that omit accounts require a server with this loader support. Rolling back
the server requires restoring explicit accounts before redeploying those packages.
No database or public protocol change is needed. Mock-free file/parser tests cover
inheritance across each declaration family, replacement, missing defaults,
invalid inputs, explicit overrides and compatibility. Integration tests exercise
the actual Application validator and runtime script sandboxes without PostgreSQL.
