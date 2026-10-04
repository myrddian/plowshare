# Plowshare Python SDK

Python 3.11+ asynchronous client for `plowshare-v1`. Build a wheel with
`python -m build`; install it with pip. No package has been published remotely.

```python
import os
from plowshare import Client

async with await Client.connect(origin, os.environ["PLOWSHARE_TOKEN"]) as client:
    reply = await client.request("project.list", {})
    projects = reply.require_payload()
```

Every registered operation is available through `request`; `OPERATIONS` is generated
from the shared protocol catalog. Convenience methods cover conversations, agent runs,
jobs and outgoing messages. Replies retain the complete envelope, opaque result fields,
nullable limits and the original outcome code. Refusals remain replies until callers
explicitly call `require_payload()`. `ACCEPTED` means accepted work, not completion.

Bearer authentication travels in the WS upgrade header. Tokens are caller-managed;
there is no credential store, refresh, application HTTP fallback or automatic reconnect.
Requests have a positive deadline (30 seconds by default) and at most 64 are outstanding.
`TransportError.delivery` distinguishes not submitted, unknown and invalid response.
Cancelling a submitted request raises `CancelledRequest`, an `asyncio.CancelledError`
with unknown delivery. Neither timeout nor cancellation repeats a mutation.

`next_push()` receives bare or enveloped notifications through a 256-entry queue;
`dropped_pushes` counts overflow. Reconcile dropped events through durable status reads.
Retain outgoing request UUIDs before submission; identical UUID/payload recovery is an
explicit caller action. Filesystem/process presence and binary uploads are not provided
by this core SDK. See [the SDK contract and roadmap](../docs/sdks.md).
