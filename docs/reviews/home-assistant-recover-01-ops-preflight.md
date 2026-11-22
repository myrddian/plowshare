# RECOVER-01 operator preflight — independent review

Verdict: **APPROVE**.

Scope: The exact same-container policy update and idle provider-only application
exit/network-fault checks below. This is not evidence of completed recovery,
successful post-fault house reads or an actual host reboot.

Implementer: `/root`.
Reviewer: `/root/ha_deployment_reviewer`.
Private candidate: `/private/tmp/ha-provider-recovery-20261123/ops-v2/ops-candidate.json`.
Candidate SHA-256:
`7a1ebb21d72c7624dc1c561446035e352c231c0e2baf734a2faba0409d681af3`.

I independently read the actual before-state and all three bound operator scripts.
All four file hashes match; each script embeds the same exact recorded before-state.
I read no credential file, performed no live call or mutation and changed no code,
configuration or ledger. This report is my only operator-preflight output.

## Finding and correction

The original readiness predicate could accept an old nonwithdrawn catalogue after
a failed network session and a new process start. The preserved v1 candidate was
not approved. The v2 scripts require a changed confirmed publication ID, publication
time no earlier than the recovered process start, an unexpired configured lease
and settled receipts. Receipts record the actual new publication identity/time.
This resolves the evidence defect; no blocking preflight finding remains.

## Exact policy boundary

The policy operation changes only the recorded running container's restart policy
from `no` to `unless-stopped`. It refuses an existing policy attempt, verifies the
exact container/image, nonroot user, read-only root filesystem, no published ports,
all mounts, configuration/launcher hashes and all 56 distribution hashes. Journal
identity/owner must match and every original replay fence must retain its generation
and at least its prior high-water position. Control, actions and subscriptions remain
disabled, with the same fixed 68-entity policy. It also verifies the main server's
original image/start identity.

The policy attempt is recorded before the bounded update. Its result must confirm
that neither provider start identity nor restart count changed immediately, and
that Docker is already enabled at boot. No container is rebuilt/replaced, no journal
is reset and no binary, credential or grant changes. Host-boot behavior is configured
and inspected, not represented as an actual reboot test. Docker's restart backoff
and operator-stop behavior are described in its
[official CLI reference](https://docs.docker.com/reference/cli/docker/container/run/).

## Bounded fault checks and restoration

Both fault scripts require a settled ready owner and the configured automatic
policy, record a unique attempt before action and never replay an unknown signal
or command. Application exit signals only the provider's PID 1 from inside that
same container; Docker stop/kill and forced termination are not used. The bounded
observation requires the same container with a greater restart count, new process
start and fresh confirmed catalogue, followed by full identity/hash verification.

The network check refuses any initial attachment other than the recorded bridge,
disconnects only that provider and observes its state for a finite interval. Its
finally block restores the original provider bridge attachment even after an
observation failure, using actual inspected attachment state. It does not alter
other containers/networks. After restoration, the bounded recovery observation
again requires new-process catalogue proof and preserves all journal/identity
fences. Expiry reports failure and leaves the original owner/state for inspection;
it never force kills or creates a replacement.

No Docker-daemon, main-server, HA or host restart is part of either check. The
scripts do not submit model/device work. A later deliberate post-recovery read
needs its own retained request/result, without resubmitting earlier jobs.

## Recovery semantics and limits

Source inspection supports same-journal restart: exclusive ownership prevents a
second physical process opening an occupied journal; EXECUTING becomes UNKNOWN
without handler replay, READY publishes its retained result, and PUBLISHING uses
read-only original-event reconciliation. Missing/conflicting receipt evidence,
corrupt state, changed identity or invalid credentials remain fail-closed even
while process restart attempts back off. This policy is not a guarantee that an
ambiguous publication can recover automatically without operator attention.

The new fresh-SDK/same-disk-journal tests are appropriate acceptance evidence for
these boundaries, but full RECOVER-01 approval still requires their frozen final
check evidence and actual policy/fault/read receipts. Provider process restarts
alone must not be reported as functional read recovery or broad LIVE-01 completion.
