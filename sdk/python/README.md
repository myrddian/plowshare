# Plowshare Python SDK

Python 3.11+ asynchronous client for `plowshare-v1`. Build a wheel with
`python -m build`; install it with pip. No package has been published remotely.

For a complete external application, see the [Python Network Privacy example](../../integrations/network-privacy/README.md).
It combines a Python web interface and collection scripts with SDK Relay ingress/egress,
project evidence uploads, scheduled work and Plowshare agent investigations.

```python
import os
from plowshare import Client
from plowshare.contracts import ProjectListRequest

async with await Client.connect(origin, os.environ["PLOWSHARE_TOKEN"]) as client:
    reply = await client.request(ProjectListRequest())
    projects = reply.require_payload()
```

Every registered operation has a generated request DTO and a paired result DTO.
`request()` accepts generated requests and returns `Reply[T]`; `require_payload()`
returns the checked result or raises `Refusal` with its code and text. Frozen
nested dataclasses, tuples and closed unions expose declared fields. Unknown
output fields are omitted. Optional `UNSET` means omitted; explicit `None` means
JSON null when permitted. Malformed inputs fail before submission. `ACCEPTED`
means accepted work, not completion.

Bearer authentication travels in the WS upgrade header. `Client.connect` accepts
a caller-supplied bearer; it never refreshes, reconnects or retries automatically.
Requests have a positive deadline (30 seconds by default) and at most 64 are outstanding.
`TransportError.delivery` distinguishes not submitted, unknown and invalid response.
Cancelling a submitted request raises `CancelledRequest`, an `asyncio.CancelledError`
with unknown delivery. Neither timeout nor cancellation repeats a mutation.

`next_push()` receives validated `ServerPush` DTOs from bare or enveloped notifications through a 256-entry queue;
`dropped_pushes` counts overflow. Reconcile dropped events through durable status reads.
Retain outgoing request UUIDs before submission; identical UUID/payload recovery is an
explicit caller action. Filesystem/process presence and binary uploads are not provided
by this core SDK. See [the SDK contract and roadmap](../../docs/sdks.md).

The mandatory native checks and design rules are in [the native SDK standard](../../docs/native-sdk-standards.md).

Named external tools use the same declaration, handler, durable journal and
read-only reconciliation façade in every language. See [Relay tools](../../docs/relay-tools.md)
for configuration, grants, examples and recovery semantics.

## Shared user login

`Credentials` owns optional native user authentication without a Node runtime.
`HttpAuthentication` uses the public HTTP auth endpoints only; application reads
and writes still use `Client`. It never follows redirects or retries auth requests.

```python
from plowshare import Client, Credentials

# origin is explicit application configuration. Account/connection are optional
# selectors; ambiguous saved identities require an explicit choice.
credentials = Credentials.resolve(origin, account="alice")
session = await credentials.authenticated()
async with await Client.connect(session.origin, session.tokens.access) as client:
    reply = await client.request(ProjectListRequest())
```

Use `await credentials.login(handle, password)` for explicit login and
`await credentials.logout()` for server revocation and local removal. Passwords
are never stored. Restricted sign-ins return `must_change_password` and are not
saved. `session()` reads local state; `authenticated()` checks server standing and
renews only after an explicit 401. Applications must handle connection failure
without replaying mutations. Exact retained request recovery remains explicit.

Configuration follows `PLOWSHARE_CONFIG_DIR`, `PLOWSHARE_DESKTOP_CONFIG`, then
`~/.plowshare`. Pass `directory=Path(...)` for an isolated absolute root.
`resolve` reads the shared named-connection registry; explicit origin/account and
named selections must agree. `PLOWSHARE_CONNECTION`, `PLOWSHARE_URL` and
`PLOWSHARE_ACCOUNT` (or `PLOWSHARE_HANDLE`) supply omitted selectors. Neither
ambient service tokens nor passwords are used as fallbacks.

Version-1 credential files, identity keys, migration/logout markers and mkdir
locks interoperate with Node. Renewal reloads under that lock and persists the
new pair before returning it. `renew(rejected_access=old_access)` reuses a pair
another client already renewed. Cancellation, uncertain refresh delivery or
failed persistence leave a durable fence requiring explicit login; refresh is
never replayed. Only native DNS/connect-refused failures prove non-submission
and preserve the previous usable pair. Stale locks require operator inspection.
POSIX files/directories are private (0600/0700); Windows requires a private user
profile/ACLs. Unicode DNS input must use its explicit ASCII IDNA spelling.
See [client login](../../docs/client-login.md) and
[the design decision](../../docs/decisions/0035-python-sdk-shared-login.md).
