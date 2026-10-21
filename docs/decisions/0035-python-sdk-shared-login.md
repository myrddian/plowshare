# ADR 0035: Native Python shared user-login support

Status: accepted for implementation. The user separately authorized this platform
SDK change, followed by a distinct Excalibur consumer PR.

## General need and boundary

Python SDK consumers currently have to manage their own bearer. Native Python
CLIs and desktop/integration utilities need the same saved account selection and
session lifecycle as Node clients. Requiring a second runtime or copying the
credential file format into each consumer duplicates security-sensitive code.

Add a generic Python SDK authentication capability and credential store using
existing public `/v1/auth/login`, `/session`, `/refresh` and `/logout` boundaries.
HTTP here bootstraps authentication only. Application operations continue over
the existing public WebSocket client. There are no server, API, permission,
database, generated DTO or ticket-specific changes.

The consumer-owned alternatives considered were an explicit service credential
(appropriate for unattended agents, but not a user's shared login), a Node SDK
helper (reuses the correct lifecycle but adds a runtime/IPC dependency), and
consumer parsing/renewal (duplicates ownership, concurrency and recovery logic).

## Contracts and invariants

- Expose immutable typed tokens, sign-in/status/session results, a narrow
  `Authentication` capability, native `HttpAuthentication`, and `Credentials`.
- Resolve explicit/named origin and account using the shared native registry.
  Never fall back to another server/account or an ambient service token. No
  password prompts, authority changes, or application-operation replay.
- Preserve Node's version-1 JSON, origin/account SHA-256 keys, private files,
  atomic mkdir `.lock` directories and migration/logout markers. Moving legacy
  credentials holds both source and destination locks; never copy refresh tokens.
- Hold the selected credential lock across reload, durable `renewing:true`, HTTP
  refresh and atomic persistence. A concurrent client whose rejected access is
  older than the stored pair reuses that pair. Publish access only after saving.
- An explicit 401 permits renewal; outages, redirects, malformed replies and
  other statuses do not. Only a native pre-connect DNS/refused-connection proof
  may clear a renewal intent without a returned pair. All ambiguous delivery,
  cancellation, interrupted writes and refused refreshes remain fenced until
  explicit login. Stale locks are never stolen automatically.
- A local saved handle identifies the account originally established by login,
  not additional authority. Every server operation still enforces current
  authentication, project membership, scopes and document ownership.
- POSIX directories/files use 0700/0600; boundaries reject symlinks and foreign
  OS ownership. Windows relies on the user's private profile/ACLs. No keychain
  encryption is added. Secret fields are excluded from diagnostics and reprs.

## Compatibility and verification

`Client.connect(origin, bearer)` remains unchanged. Shared protocol consumers in
other languages are unaffected. Python user-login is opt-in; service consumers
continue using explicit scoped credentials. Rollback can keep using the shared
Node store: files and lock names remain compatible, including interrupted renewal
fences and logout markers. Unicode DNS inputs must use explicit ASCII IDNA origins
to avoid stdlib/browser normalization selecting different servers.

Tests use mocked capabilities and isolated filesystem/HTTP/WebSocket fixtures,
not PostgreSQL. Verify Node/Python shared-store compatibility, lock contention,
concurrent renewal, exact account selection, migration/logout fences, malformed
and redirected HTTP, failed persistence, cancellation and secret-free diagnostics.
Run `check`, `sdkCheck` and installed-package coverage. The separate Excalibur
change verifies exact request recovery under renewed user access without moving
ticket logic into this platform capability.
