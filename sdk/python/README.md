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

Bearer authentication travels in the WS upgrade header. Tokens are caller-managed;
there is no credential store, refresh, application HTTP fallback or automatic reconnect.
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
