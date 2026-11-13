# Integration acceptance harness

This project has no production source or runtime artifact. Its test dependencies
compose the real server and independently packaged adapters to exercise public
SDK/WebSocket and external protocol boundaries. The server does not depend on an
adapter, and adapter production code does not depend on the server.

`RelayA2aAcceptanceTest` starts an isolated PostgreSQL container and a server with
closed inference endpoints. It drives A2A HTTP through the public SDK and registered
Relay receiver, preserving restart, ownership, service-token and live-grant tests.
It is excluded from routine builds by `@Tag("full-db")`.

Run only when a change needs the actual SQL/native admission boundary:

```sh
./gradlew :plowshare-integration-acceptance:test -PfullDb --tests '*RelayA2aAcceptanceTest'
```

A synthetic fixture is evidence of this local transport/persistence loop, not a
live deployment or a production-data upgrade rehearsal.

`RelayOutboundAcceptanceTest` replaces the four legacy outgoing queue checks with
real public Relay/SDK and independent A2A HTTP coverage. The four legacy tests were retired only after all five replacement cases passed
and the independent review barrier accepted the coverage and lifecycle changes.

| Legacy coverage | Relay replacement |
| --- | --- |
| Send once, poll after restart, preserve artifacts | Same durable journal and consumer recover an accepted-but-unacknowledged reply publication from actual broker evidence; a new explicit READ preserves remote task/context and artifact references without another SEND. |
| Uncertain send and cancellation | A disconnected first SEND retains UNKNOWN with no invented remote IDs; restart never repeats it. Unobserved CANCEL is rejected before peer I/O; an independently observed request can be canceled explicitly. |
| Peer discovery and ownership | Strict HTTP discovery fails finitely before POST, journals a rejection, and permits the next valid request to progress. Producer allowlists and configured peer aliases remain authority boundaries. |
| Exclusive claims, report fencing and account scope | Actual broker consumers race for one group lease, wrong consumer/fence acknowledgements fail, foreign principals cannot read/consume, and a second process cannot acquire the same journal. |

There is no equivalent public queued-send cancellation operation after retirement
of the old outgoing queue. Broker acknowledgement is not external cancellation.
The replacement tests document this deliberate lifecycle change instead of
claiming that cursor operations cancel remote work. Polling is a separately
identified READ request, rather than an autonomous server outgoing queue.
Discovery cache expiry belongs to the configured A2A client; the retired server
advertisement list and its TTL are not retained capabilities.

A fifth case starts an actual agent TURN with `relay_publish`, waits for that
source job to finish normally, processes the real A2A observation through the
configured `message.return` route, and verifies a distinct later paid job in the
original conversation. Native request/reply/requester identity is asserted from
committed records; the fixture never fabricates an export source in SQL. The
original source outcome remains unchanged and remote artifacts remain evidence.

The journal and broker lease tests establish the supported shared-journal single
owner arrangement. They make no distributed exactly-once claim for independent
journals. These fixtures require the explicit `-PfullDb` gate because their
assertions concern real persistence, transactional native returns and lease locks.
