# Native resource discovery and reads

`HomeAssistantResourceCatalog` implements the adapter-local `ResourceDiscovery`
and `ResourceReader` interfaces. Its production source comes from
`HomeAssistantAdapter.resourceSource(bindingName)` and shares that adapter's
configured native WebSocket, origin and credential. It creates no second durable
runtime or connection and does not bypass the public Plowshare SDK. Publishing
these capabilities as named SDK tools is the next delivery slice; this change
alone does not add tools to the existing manual Relay executable.

A host composes the catalogue with an explicit immutable
`HomeAssistantDiscoveryPolicy` and positive finite metadata maximum age (at most
five minutes). Each operation requires the original caller-owned
`OperationBudget`. The provider entry point owns its configurable two-second
interactive default and passes that budget through every wait/probe/read. A
nested call never creates another budget. Existing manual Relay commands retain
the adapter's separately configured timeout (the current factory supplies 20
seconds); their existence is not evidence of universal two-second enforcement.
Connection establishment now shares one configured budget across authentication,
subscription and initial snapshot instead of receiving a fresh timer per step.

The resource policy is trusted host/operator configuration, never input supplied
by a tool request or model. `fromSettings` preserves the existing selected aliases,
attribute ceiling and alias-only metadata. An explicit resource policy may opt in
to additional bounded attribute names or name/device/area metadata for the same
configured entity targets. This is a separate disclosure decision, not an
implicit broadening of manual alias configuration. Legacy manual readings still
reject selected object/array attributes. Resource reads report selected unsupported
attribute names without retaining their nested payloads. Rebinding entity targets
requires explicit adapter reconfiguration and a new source generation.

References include the configured binding and an opaque credential-generation
identity assigned by the adapter, never a token/hash. A new adapter after
credential rotation cannot reuse old snapshots. Each binding source is intended
for one catalogue/policy owner; policy replacement requires a new policy revision,
invalidates the cache and reconfigures selected state-event tracking. Up to 128
binding sources share a connection; each catalogue retains one projected snapshot
of at most 256 configured resources. No denied resource is included in paging,
search or counts. A profile with undisclosed fixed action targets remains omitted
by the existing discovery policy; reads/discovery grant no execution rights.

Metadata collection makes bounded collection-level registry/service probes.
Names come from the permitted entity registry or remain configured aliases when
registry metadata is absent. Metadata collection does not fetch/discard state values. A read
always issues one fresh `get_states` batch for all selected references, preserving
request order and explicit missing/unavailable/invalid results. It never issues
one request per entity. Metadata and state values are distinct projections, and
state values are not served from the metadata cache.

The connection retains its existing permission-filtered `state_changed`
subscription. Metadata collection probes explicit registry/service event
subscriptions, never a wildcard subscription (which would require HA admin).
Only known unsupported/denied probe errors become explicit capability states;
other failures stay unavailable. Registry denial never implies an empty house.
Area filtering fails explicitly when its relationship disclosure/capability is
not available. Device/area invalidations may affect selected resources indirectly;
they are internal hints, not published event counters.

The outward metadata revision advances only when the permitted projected
metadata/capabilities change. A private event can force bounded revalidation but
cannot increment the public version or reset a page by itself. After maximum age,
a refresh may prove the same public revision still current; a changed revision,
policy or connection epoch refuses the old cursor instead of restarting it.
State revisions track events only for the source's selected resource set and do
not force metadata refetch unless descriptive metadata changes. Changes during
collection and disconnection fence results rather than publishing stale values as
fresh. This does not claim elimination of every latency/availability side channel:
a private invalidation can still require work or coincide with unavailable input.

Command-slot waiting, transmission, reply waiting and processing share the same
remaining monotonic budget. HA has no per-command cancellation operation; expiry
or interruption aborts the captured WebSocket attempt. Other possibly submitted
commands on that socket become unconfirmed, including actions; no action or read
is automatically resent. A stale attempt cannot select and close a replacement
connection. Local pending slots are released after their futures settle and
logical entries are removed; socket cancellation does not prove a remote physical
action stopped. Existing journals/receipts remain the recovery owner for actions.

The focused fixtures exercise the actual native protocol with a local WebSocket
peer, plus fake-source policy/freshness/concurrency cases. They do not establish
live installation, provider/SDK tool grants or physical device behavior.

Observation delivery has its own bounded ephemeral FIFO owner. It accepts at most
256 pending state observations plus one reserved Gap; callbacks/journal writes
run on one dedicated daemon thread outside the adapter monitor and never on an
expired read's abort path. Startup waits for its initial resync acknowledgments
outside that monitor. Overflow fences intake/connection continuity and retains
the accepted FIFO followed by its Gap, rather than silently dropping accepted
transitions. Reconnect waits for prior physical delivery and Gap settlement.

Observer callback and cleanup budgets default to two seconds independently of
manual command timeouts; the explicit constructor permits host configuration.
On callback expiry, logical acknowledgment fails and its captured connection
generation is fenced. The physical callback remains accounted until it returns,
its original identity is invoked once, and later callbacks cannot run around an
uncertain journal position. A late normal return may drain the retained FIFO/Gap;
an exception instead requires reconciliation. Cleanup first drains within its
remaining budget, then interrupts without claiming an uncooperative callback
stopped. An incomplete cleanup emits a fixed diagnostic; do not replace its
journal owner while that physical callback remains unsettled. This is an
in-memory delivery queue, not a second durable integration runtime.

State and authentication callbacks recheck their captured epoch under the same
monitor as the mutation. Socket assignment and failed-start cleanup also retain
that generation fence; an old callback cannot update the replacement snapshot,
state revision or authentication state merely because it passed an earlier
unlocked check.
A loss also retires its current attempt even before a replacement epoch exists.
Authentication, result/state processing and socket assignment refuse that retired
attempt; late authentication cannot turn an expired socket back into a connected
one. Only a new explicitly admitted connection resets retirement. Valid result
futures remain tied to their original request identities, not a replacement job.
