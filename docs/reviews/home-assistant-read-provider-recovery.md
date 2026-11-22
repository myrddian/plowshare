# RECOVER-01 — Automatic read-provider recovery

Implementer/coordinator: `/root`. Recovery test implementer:
`/root/ha_recovery_implementer`. Independent reviewer:
`/root/ha_deployment_reviewer`. The user authorized provider-only automatic
recovery while preserving journals, the existing SDK and disabled control.

## Deployment and safety contract

The existing Production read-provider container now has Docker's
[`unless-stopped` restart policy](https://docs.docker.com/engine/containers/start-containers-automatically/).
Docker is enabled at host boot. This restarts the same container after an ordinary
process failure or Docker/host start, respecting an operator's deliberate stop.
No second provider, process manager, container recreation, image/config/credential
change or journal reset was introduced. Docker supplies bounded restart backoff;
each SDK connection, request and cleanup keeps its existing finite budget.

All 30 HA production source files, 4,042 protected core/protocol/SDK/shared-runtime
source files and 28 deployed launchers/JARs are unchanged. The policy update itself
did not restart the provider. Its original persistent journal mount, owner,
configuration identity and replay fences remain intact. The same fixed 68-entity
state/unit allowlist, empty actions/subscriptions and absent control block remain.
No HA, main Plowshare server, Docker daemon or shared host restart was performed.
The actual main-server container identity/start time/image were checked unchanged.

HA already maintains its native connection. A lost Plowshare connection stops the
provider; Docker starts a fresh process only after the previous process exits.
That process exclusively opens the original journal and reconciles through the
existing SDK. EXECUTING becomes UNKNOWN rather than another native invocation;
READY reuses its retained result; uncertain catalogue/results are inspected under
their original event identity. Missing receipts do not authorize republishing or
handler replay. Corrupt/mismatched state, expired credentials, retained gaps and
unconfirmed publication uncertainty still fail closed, potentially leaving the
provider unavailable while restarts back off. Recovery is not automatic repair.

## Verification

Seven focused real-SDK recovery cases passed, including fresh SDK connections and
the same disk journal for catalogue/result acknowledgement loss, positive retained
receipt reconciliation, EXECUTING-to-UNKNOWN and READY-result publication once.
The existing missing-receipt cases remain fenced. The genuine HA read test counts
connection bootstrap snapshots separately from native invocation: a new HA owner
adds its necessary initial snapshot, without repeating the accepted handler or
its metadata query. See the [test implementation evidence](home-assistant-recover-01-test-implementation.md).

Formatting, all 189 HA Java cases, the default repository check and installDist
passed (81 seconds, 165 tasks). No database tests were enabled. The rebuilt runtime
binaries exactly match the already deployed ones; only tests/docs changed.

The independent [ops-v3 preflight](home-assistant-recover-01-ops-v3-preflight.md)
binds the exact policy/fault scripts. An earlier precondition refused a mount-list
ordering difference before any mutation; its receipt confirms no attempt or policy
change. The corrected comparison requires unique destinations and exact source/RW
values. Original candidates and reports remain immutable.

Actual provider-only checks then passed:

- The idle provider received its own normal SIGTERM inside its container. Docker
  restarted the same container automatically; no Docker stop/force kill was used.
- Only that container's bridge attachment was temporarily disconnected. It exited
  during the outage and restarted automatically. A finally step restored the same
  bridge attachment, and the new process reconnected successfully.
- Each recovery required a changed catalogue request ID, a confirmation published
  no earlier than the new process start and an unexpired lease. An old confirmed
  lease could not satisfy readiness. Original journal identity/owner and all
  original replay fences were preserved; no unresolved receipts remained.
- The three original Production jobs remained byte-for-byte unchanged across the
  faults, with no new model submission. One separately identified fresh acceptance
  request through HA Conversation then completed DONE/ANSWERED with three model
  calls and two tools (search/read). Its actual permitted HA result was AVAILABLE,
  nonstale and unit-only. No control tool ran. Raw identities, readings, endpoint
  and credential details remain private.

Final runtime evidence confirms policy unless-stopped, two automatic restarts,
restored network, fresh active catalogue, settled receipts, original mounts and
unchanged binaries/main server. Actual host reboot was not performed: boot recovery
is established as configured behavior from the enabled Docker service and
persisted policy, not claimed as a live reboot test. The broader LIVE-01 voice,
AI Task, follow-up/isolation and physical-control matrix remains open.
