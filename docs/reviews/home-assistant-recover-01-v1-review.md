# RECOVER-01 v1 independent final review

Verdict: **APPROVE**.

Implementer/coordinator: `/root`.
Recovery test implementer: `/root/ha_recovery_implementer`.
Reviewer: `/root/ha_deployment_reviewer`.
Candidate: `docs/reviews/home-assistant-recover-01-v1-candidate.json`.
Candidate SHA-256:
`16461fff0cb51f301c54e8c18e3cbb117b227c965bcc5b1a44919c661a64f70d`.

I independently read the actual test diff from its preserved initial working-tree
bytes, operator scripts, documentation/reports, focused and broad check evidence,
actual policy/fault/runtime receipts, retained jobs and post-recovery read
trajectory/result. All four source/report hashes, 19 safe private evidence hashes,
two preflight review hashes, frozen ledger/diff and two accepted dependency
manifest/review identities match. The six nested test-evidence identities match
the source, baseline, owned diff, log, XML and report. I read no credential file,
made no target/API/model/device call and changed no implementation or ledger.
This report is my only final-review output.

## Findings resolved and implementation boundary

No blocking finding remains. The earlier operator predicates could mistake an
old catalogue for recovered availability; the approved correction requires a
changed publication ID, publication no earlier than the new process start and
an unexpired lease. A later ordering-sensitive mount comparison refused before
attempt creation or mutation. The preserved refusal receipt confirms that result;
ops-v3 compares unique destinations with exact source/read-write identity instead.
Original rejected/superseded candidates and prior approvals remain immutable.

The only deployed change is `unless-stopped` on the same existing read-provider
container. There is no runtime reconnect implementation, second process manager,
container recreation, image/configuration/credential change or new journal.
All 30 HA production source hashes and 4,042 protected core/protocol/SDK/shared
runtime hashes still match their baselines. All 28 current built launcher/JAR
hashes match the recorded deployed binaries. New implementation material is
limited to tests, operator policy/evidence and documentation.

The provider retains its fixed 68-entity state/unit policy, empty explicit
attributes/actions/subscriptions and absent control provider. The same scoped
Application principal, mounts, journal identity/owner and original replay fences
remain. Token expiry, policy/configuration mismatches, corrupt state and missing
uncertain-event evidence do not acquire fallback authority or permission to
reset/replay work. Process restart can still leave those cases unavailable.

## Public SDK and durable-journal verification

The seven focused tests use real segmented concurrent public SDK connections,
actual local HA protocol peers and the same disk journal across fresh owners.
Positive lost-catalogue and lost-state-result acknowledgement cases reconcile the
original accepted events rather than publishing or collecting them again.
EXECUTING becomes UNKNOWN without handler execution; READY publishes the retained
result once across two fresh owners. The existing absent-event cases remain
fenced. The native state case counts necessary new-connection snapshots separately
from the original invocation read and metadata collection.

I inspected the actual focused log/XML and owned diff. All seven cases passed.
Formatting, formatCheck, default repository check and HA installDist passed in
81 seconds across 165 tasks. Actual HA Java XML contains 189 tests with zero
failures/errors/skips. No real database tests were required or enabled. These
tests establish the declared recovery boundaries without changing SDK/core code;
they do not claim live host-reboot or arbitrary-failure recovery.

## Actual policy and fault evidence

The policy receipt shows the original container's policy changed without an
immediate process restart. Docker is enabled at boot. The provider-only normal
self-SIGTERM check then produced restart count one, a new process start and a
fresh catalogue. The provider-only bridge-disconnect/restore check produced
restart count two and another fresh catalogue. Actual publication identities
differ and each publication time is at or after its corresponding new process
start; the guarded observations also required an unexpired lease.

Both checks retained the same container/image, mounts, configuration/launcher and
56 artifact hashes, journal owner/identity and original replay-fence generations
and high-water positions. The network attachment was restored in finally. No
forced kill, replacement owner or automatic uncertain mutation replay was used.
Actual server identity/start time/image remained unchanged. No HA, Docker daemon,
main server or host restart was performed. Boot recovery is a persisted-policy
and enabled-service claim, not a live reboot acceptance claim.

Final runtime evidence shows the same provider running under `unless-stopped`,
two restarts, restored bridge, active confirmed catalogue, no unresolved intent
or retained result and preserved replay fences. Docker's configured operator-stop
and restart/backoff semantics are documented in the
[official reference](https://docs.docker.com/reference/cli/docker/container/run/);
this review does not present those documented behaviors as additional live tests.

## No duplicated jobs and functional read after recovery

The three preexisting Production jobs are byte-for-byte identical before and
after the faults, with no new model submission from recovery. Exactly one new
job appears after the separately identified fresh HA Conversation acceptance.
Its retained result is DONE/ANSWERED with three model calls and two tools:
search and read. The read uses an object revision and returns an AVAILABLE,
nonstale permitted state with only its allowed unit attribute. The actual HA
answer equals that retained job output and matches the tool's value/unit.
Raw readings and private identifiers remain outside source.

This establishes observed process/network recovery plus a working post-recovery
read, beyond container liveness alone. It does not prove recovery of every
ambiguous publication, credential/configuration failure or live host reboot.
No control tool ran. Broader LIVE-01 voice, AI Task, follow-up/isolation and physical
control acceptance remains separate and uncompleted. RECOVER-01 may be marked
verified, independently reviewed and operationally complete within this scope.
