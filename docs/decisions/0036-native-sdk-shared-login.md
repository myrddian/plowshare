# ADR 0036: Shared user login in Go, .NET and Java SDKs

Status: accepted for implementation, following the separately authorized SDK-only
scope of [ADR 0035](0035-python-sdk-shared-login.md).

## Capability and ownership

Go, .NET and Java consumers need typed access to the same user session lifecycle
as Node and Python without parsing credential files, spawning another runtime or
implementing single-use refresh coordination in each application. Add optional
`Authentication` (Go), `IAuthentication` (.NET), `UserAuthentication` (Java), native
`HttpAuthentication`, and `Credentials` capabilities using existing public login,
session-status, refresh and logout HTTP endpoints. Java's existing access-only
`AuthClient` remains compatible. No server, protocol, database or application
behavior changes are required.

WebSocket clients still accept caller-managed bearers. Applications explicitly
choose a saved user session or a service credential. Service credentials never
fall back to a human login; `pss_` credentials cannot be stored as human sessions.
Authentication does not confer project membership, grants or workspace authority.

## Shared storage and renewal

Read the native connection registry and its explicit/environment selector
precedence. Share `PLOWSHARE_CONFIG_DIR`, then `PLOWSHARE_DESKTOP_CONFIG`, then
`~/.plowshare`; XDG is a legacy migration source only. Names never form paths.
Connection keys hash compact JSON `[canonicalOrigin,account]`, and credential
filenames hash the canonical origin. Require ASCII DNS/explicit IDNA and canonical
IPv4 input to prevent parsers selecting different servers. IPv6 is compressed.

The store uses Node/Python version-1 credential files, private POSIX 0700/0600
state, atomic replacement, shared `.json.lock` directories and migration/logout
markers. Windows uses the caller's private profile ACLs. Legacy migration holds
source and destination locks and moves a proven matching identity. Foreign or
malformed state is never a fallback, and logout markers prevent resurrection.
Passwords are never persisted; secret-bearing values redact diagnostics.

A shared lock spans reload, validation, durable `renewing:true`, HTTP refresh and
saving the returned pair. Validate the renewal fence before comparing access:
a newer saved pair may be reused only when no rotation is interrupted. Explicit
renewal rotates once. `authenticated` checks status and renews only after explicit
HTTP 401; unavailable, restricted, redirected or malformed status never renews.
Logout explicitly rotates/persists before revocation, retaining the pair if
revocation fails and marking migration before deletion on success.

All refresh failures in these new stores conservatively retain the renewal fence,
including known connection failures; no custom exception can attest non-submission.
Cancellation/interruption does not allow a late response to publish access. Failed
persistence exposes no pair. If the final atomic replace may have succeeded but
its durability cannot be proven, retain the shared lock as a second fence. Stale
locks are never stolen. Stop affected clients, inspect the exact retained lock,
and sign in explicitly after resolving it. There is no blind refresh replay.

## Application recovery and verification

Auth helpers neither open application sockets nor resubmit operations. After
renewal the caller explicitly opens a connection, reconciles durable receipts and
chooses any recovery using the original UUID and identical payload. An unknown
mutation outcome never authorizes creating a new request ID or automatic replay.

Test bounded typed HTTP responses, exact account/origin selection, private state,
concurrent renewal, failed persistence, cancellation, stale locks, legacy moves
and logout fences. One isolated HTTP fixture exercises all five SDKs against the
same store, including installed npm/wheel/NuGet/Go artifacts and Java jars.
`check`, `sdkCheck` and `sdkPackageCheck` are the required gates. These fixtures
prove local interoperability, not deployed authentication or platform-wide OS QA.
