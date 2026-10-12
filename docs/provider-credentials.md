# Server-held provider connections and credentials

This platform capability stores upstream grants for configured HTTP providers. It
is separate from Plowshare login, tool catalogues, Relay invocation receipts and
provider scopes. It does not yet host an MCP transport or release credentials to
external workers. The durable binding and invocation authority described in
[ADR 0019](decisions/0019-mcp-provider-hosting.md) must gate that release.

Connections belong to an ordinary registered project. A server administrator who
also has project manager access configures their endpoint and authentication
profile. Project readers can inspect shared settings. Each credential belongs to
the authenticated human or the exact `@service/<token-UUID>` principal. Credential
management requires that subject's current contributor access. An administrator
may explicitly select an active service principal; a service-owner handle is not
a credential subject. Rotation of the same Plowshare service token preserves its
principal, while a replacement token has a different identity. No user can select
another human's credentials, and administrator status does not replace the
subject's project membership or service-token ceiling.

All public operations use the authenticated WebSocket and shared typed SDKs:

| Operation | Input and result |
| --- | --- |
| `provider.connection.list` | Project; up to 256 retained shared settings |
| `provider.connection.status` | Project and connection UUID; current settings |
| `provider.connection.save` | Definition, connection/request UUIDs, expected revision or explicit null for creation; redacted receipt |
| `provider.connection.change` | Expected revision and ENABLE, DISABLE or DELETE; redacted receipt |
| `provider.connection.receipt` | Project and original request UUID; original redacted committed outcome |
| `provider.credential.status` | Project, connection, subject or explicit null for the authenticated owner; redacted state |
| `provider.credential.put` | Expected generation or null, request UUID and upstream secret; API key or machine client secret receipt |
| `provider.credential.revoke` | Expected generation and request UUID; remove retained secret material |
| `provider.credential.rekey` | Expected generation and request UUID; reseal a READY grant with the active encryption key |
| `provider.credential.authorize` | Expected generation and request UUID; one-time browser authorization URL and redacted receipt |
| `provider.credential.refresh` | Expected generation and request UUID; one token request and redacted status |

Nullable fields must be explicitly present. Invalid nested input, unsupported
headers, unknown input fields and coercion are refused before side effects. Java
uses `ProviderConnectionClient`; TypeScript/Node use the checked operation
transport; Python, Go and .NET expose generated typed requests and paired replies.
Dedicated client management screens remain separate work.

Retain a fresh mutation request UUID before sending. If delivery is unknown, read
its receipt and current status. A committed UUID always refuses resubmission;
absence of a receipt is not permission to retry. Receipts are immutable redacted
snapshots, so an authorization receipt remains PENDING and a refresh receipt
remains REFRESHING after completion. The once-only refresh reply and current
credential status carry the resulting generation. The authorization URL is not
retained in the receipt. A lost URL requires a deliberate new authorization after
inspection; the SDK never resends the old mutation.

Every configuration update, disable, enable or delete changes the connection
revision and revokes all prior grants, including pending OAuth states. A deleted
connection UUID cannot be adopted again. Project deletion atomically discards grants
and retains disabled connection UUID tombstones; recreating a project name cannot
adopt them. Original redacted receipts remain retained. A definition update enables the new
configuration and requires new upstream grants. Changing a grant uses a new
credential generation; changing only its encryption key preserves that generation.
Neither generation nor a request UUID grants permission by itself.

## Operator configuration

Hosting is explicitly disabled by default. Enabling it requires all of:

- `plowshare.providers.credentials.enabled=true`
- `plowshare.providers.credentials.active-key`: active version identifier
- `plowshare.providers.credentials.keys`: version-to-base64 AES-256 key mapping
- `plowshare.providers.credentials.allowed-origins`: explicit HTTPS origins for
  provider, protected-resource metadata, issuer metadata and OAuth endpoints
- `plowshare.providers.credentials.callback`: exact public HTTPS URL ending in
  `/provider-credentials/oauth/callback` (prefixed with the configured server
  servlet context path, when present)

Supply key material through the deployment's secret configuration, outside source
and exported manifests. Keys have no generated or local fallback. Keep old key
versions while retained ciphertext or backups require them. Add the new key,
select it as active, explicitly rekey each READY grant, and verify its status
before retiring an old version. Pending or refreshing grants must finish or be
revoked/reauthorized before rekeying. Revocation and uncertainty discard retained
material. Receipts, metadata and object diagnostics never contain bearer secrets,
refresh tokens, API keys, client secrets or PKCE verifiers. Native DTO diagnostics
also redact submitted secrets and authorization URLs; explicitly serializing a
secret-bearing request still produces its intended transport payload.

The URL profile accepts bounded ASCII HTTPS URLs with DNS/IPv4 hosts, optional
valid ports and unescaped paths, without query, fragment or userinfo. IPv6,
percent-encoded paths, HTTP and dynamic client registration are outside this
initial profile. Operators control allowed origins and their DNS/network trust;
remote metadata cannot add destinations or change configured endpoints. No model
tool is registered by these management operations.

## Authentication profiles and recovery

`API_KEY` supports the explicitly selected `Authorization`, `X-API-Key` or
`Api-Key` header. Store the complete intended header value. `CLIENT_CREDENTIALS`
supports an explicitly registered OAuth client with `client_secret_post`; `put`
stores that subject's encrypted client secret and `refresh` obtains its token.
READY means material is configured; inspect `expiresAt` to distinguish an obtained
OAuth access token from a configured machine secret or API key. `OAUTH_PKCE` accepts
no caller-supplied access or refresh token: authorization obtains its own grant.
Plowshare login credentials are never forwarded automatically upstream.

For OAuth, configure the resource, issuer, metadata URLs, authorization/token
endpoints, registered client ID, exact callback and at most 64 unique scopes.
Protected-resource metadata must confirm the resource and selected issuer. Issuer
metadata must confirm every endpoint, scope and authentication profile. Public
user OAuth additionally requires advertised S256, authorization-code support and
`authorization_response_iss_parameter_supported`. Machine OAuth requires
advertised client-credentials and `client_secret_post`. Missing or incompatible
metadata fails visibly. Reads and token replies are bounded to 64 KiB; redirects,
authentication retries, transport retries and endpoint adoption are disabled.
Tokens must be Bearer, with a supported bounded lifetime and no changed resource or
scope grant. Requested resources are carried on authorization and token requests;
opaque tokens are retained for that configured resource, not independently
verified as JWT claims.

User authorization returns a PKCE browser URL. The public callback landing page returns constant HTML and makes an authenticated
same-origin POST to `/v1/provider-credentials/oauth/complete`. This accommodates
Plowshare's Strict cookies without weakening them. The completion requires a
current Plowshare login as the actor who began authorization. A
native client user therefore signs into that account in the browser too. For a
selected service credential, the initiating administrator remains the callback
owner. State is random, hashed in persistence, bound to connection/subject/actor
and revision, expires after ten minutes and is consumed before the code exchange.
The PKCE verifier is encrypted. The landing script accepts exactly one `state`, `iss` and `code`, removes the
query from browser history, and sends only `state`, `issuer` and `code` to the
authenticated completion. Duplicate parameters, foreign users, mismatched issuers
and replay fail. The page has a restrictive CSP and emits no request values into HTML.
Callback replies are redacted with no-store/no-referrer headers. A browser may
transparently repeat a dropped completion POST; consuming the retained state
before the upstream exchange prevents a second exchange. An unconfirmed browser
reply always directs the user to retained credential status, even when the
original exchange succeeded. Deployments must
exclude callback query strings and secret-bearing request bodies from access logs,
proxies and diagnostic captures. OAuth errors without an authorization code do
not exchange a token; inspect status and begin a new authorization deliberately.

Refresh commits a REFRESHING intent before the single network POST and releases
its database transaction before network I/O. The result is installed only if the
connection, subject access and exact intent still match. Revocation or a settings
change wins over a late response. Failure, lost rotation replies or an expired
60-second intent become UNCERTAIN and discard material. Status reads reconcile
expired PENDING/REFRESHING intents. They never retry the old code or refresh token.
Reauthorize a user grant; explicitly replace the machine secret before a new
machine token request. Expiry, revoked membership and disabled accounts cannot
fall through to another subject or connection.

Encryption uses AES-256-GCM with fresh 96-bit nonces and associated data containing
the connection, subject, connection revision and credential generation. The
additive migration retains shared settings, sealed grants and redacted mutation
receipts. Disabling hosting refuses management without deleting records; rollback
must retain the migration, required keys and original uncertain-state evidence.
MCP discovery/session caches and executor credential release remain gated by the
future durable binding authority. No spending pool or external-effect ledger is
created here.

The OAuth profile follows [RFC 9700](https://www.rfc-editor.org/rfc/rfc9700) and the
[MCP authorization specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)
with the explicit compatibility restrictions above.

## Verification

Default tests use mocked authority/upstream APIs. The focused tagged
`ProviderCredentialPersistenceTest` checks the migration, sealed row mapping,
receipt rollback, project-deletion revocation and concurrent refresh against
PostgreSQL. It requires explicit `-PfullDb`. Shared SDK fixtures exercise nested
invalid input, redacted replies and stale/foreign coordinates without a database.

`ProviderOAuthControllerTest` exports the actual constant landing HTML and CSP to
`plowshare-server/build/provider-credentials/callback.json`. The manual Chromium
probe verifies Strict cookies across a provider-site link, servlet context paths,
missing login, refusal, dropped completion replies and duplicate parameters:

```sh
./gradlew :plowshare-server:test --tests '*ProviderOAuthControllerTest'
node test-support/provider-credentials/browser-probe.mjs \
  plowshare-server/build/provider-credentials/callback.json \
  <playwright-module-path> <chromium-executable-path>
```

This isolated probe supplies its own ephemeral listeners and test hosts. Its
browser dependency is caller-provided and is not a production dependency.
