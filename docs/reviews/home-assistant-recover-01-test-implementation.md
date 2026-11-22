# RECOVER-01 — Read-provider restart evidence

Test implementer: `/root/ha_recovery_implementer`. Independent review and final
operational acceptance are coordinated by `/root`; this report is not approval.
Only the existing `OutboundProviderSdkValidationTest` was changed. Its initial
working-tree bytes were preserved before editing; the owned diff is relative to
that snapshot because the class already belonged to earlier uncommitted work.
No runtime, core, SDK, protocol, shared-runtime or server implementation changed.

## Recovery contract exercised

The fixture uses the real Java SDK's default segmented concurrent WebSocket
transport, real native HA WebSockets, the actual provider/host/read handlers and
the same on-disk `HomeAssistantToolJournal` across fully closed and freshly opened
owners. Broker outcomes are scripted; this proves public-protocol and local
recovery behavior, not server authorization or a host reboot. Every new recovery
case removes the control provider, clears actions and selects state-only data.
No live endpoint or private credential was accessed.

Four new cases complement the existing three cases:

- A lost catalogue reply with a retained original event is confirmed through the
  original publication ID on a fresh SDK owner. The new owner renews its lease
  only after reconciliation; the original catalogue is never published again.
- Discovery obtains a real source revision, followed by `ha_read_read` fetching
  a native state. The result event is accepted but its application reply is lost.
  A fresh SDK owner reconciles that exact event/result and polls without invoking
  the original read again. The fixture observes one metadata collection and one
  native invocation read. Each HA connection still performs its legitimate
  startup snapshot: `get_states` counts are two before restart and three after.
  Settled receipts retire into the preserved broker-position fences.
- A durable EXECUTING receipt, including its original delivery/ack evidence,
  becomes UNKNOWN without running a resource handler. A second fresh SDK owner
  does not publish a replacement result or recollect state.
- A durable READY receipt with the original delivery/ack evidence publishes the
  retained COMPLETED result once. A second fresh SDK owner does not repeat it.

Both existing absence cases remain unchanged in meaning: an unconfirmed
catalogue/result stays fenced when read-only reconciliation finds no event.
Absence never authorizes another publication or native collection. The existing
normal two-catalogue test also remains green; it is not used to enable control
for this deployment.

## Actual validation

The final focused command passed all seven cases with zero failures/errors:

```sh
./gradlew :plowshare-integration-home-assistant:spotlessApply \
  :plowshare-integration-home-assistant:spotlessCheck \
  :plowshare-integration-home-assistant:test \
  --tests io.aeyer.plowshare.integrations.ha.OutboundProviderSdkValidationTest \
  --max-workers=2
```

Final log: `recovery-focused-v3.log`, 10 seconds, 28 actionable tasks, five
executed and 23 up to date. Earlier fixture-only failure and intermediate logs
are preserved privately. The failure was corrected at the fixture's public
`ToolValue` decoding boundary; no production coercion was added. Final source,
XML, log and owned-diff hashes are in the private test evidence manifest.

Repository-wide formatting/checks and live Docker restart/network recovery are
separate coordinator work. These tests do not justify replaying uncertain work,
changing credentials/policy/schema identity, resetting a journal, or forcing a
second owner around a still-running process or occupied journal lock.
