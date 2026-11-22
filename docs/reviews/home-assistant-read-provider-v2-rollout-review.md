# Read-provider replacement generation — independent conditional review

Verdict: **APPROVE**.

Scope: The exact conditional stop, separate-generation staging and single guarded
start below. This is approval of the guarded rollout plan, not evidence that its
conditions passed or that house reads subsequently succeeded.

Implementer: `/root`.
Reviewer: `/root/ha_deployment_reviewer`.
Private candidate: `/private/tmp/ha-read-provider-20261123/inline-v2/rollout-candidate.json`.
Candidate SHA-256:
`0270cc89ab3ba8d51f9956e6d42006e99b8bdf3f19d2e2616a0720c7a5d1a35f`.
Source prerequisite: independently approved HA-FIX-02 v2 candidate
`507b83649e387b25440cf7a1f7c5870b4292807863a5cf15f5d2f9a16dffd1ed`.

I read the exact original-owner stop script, new remote installer, one-shot
submission wrapper, start script, configuration/launcher, distribution manifest
and archive, and retained terminal-job evidence. All six bound files, stop script,
terminal-job evidence and submission-wrapper hashes match. I made no target/API,
model or device call, read no credential file and changed no implementation or
ledger. This report is my only rollout output.

## Preconditions and old-owner settlement

The retained original read job is terminal, with eleven model calls and an honest
unavailable answer after schema-refused reads. Its successful search is not a
sensor reading. The original job was not cancelled or replayed.

The stop script requires the exact original running container and a settled
journal before writing a one-shot stop attempt and sending one SIGTERM. Its
finite observation never force kills the process. It records success only after
the original process stops, all receipts remain settled with no UNKNOWN result or
pending catalogue publication, and the catalogue withdrawal is confirmed. Any
unknown signal, still-running owner or unresolved receipt refuses replacement.
Original code, configuration, container, journal and receipt evidence remain.

## New-generation boundaries

The remote installer refuses an existing replacement directory/container. Before
staging it rechecks the original stop receipt, exited original container and
unchanged withdrawn journal hash, plus unchanged server image and Application
source. Failure of any condition must stop the rollout; a new path is not a way
to bypass it. The catalogue fingerprint changed because the offered schema
changed. Its distinct empty journal is therefore a new declared generation after
settlement, not deletion or resetting of the old owner.

The fixed configuration and launcher are byte-identical to the prior reviewed
ones: the same 68 selected state-only entities, no attributes/actions/workflow
subscriptions, no control provider and the same project/principal/read prefix.
The 56-file archive matches the refreshed installed report; only the integration
JAR and provider guide differ from the previous distribution. Credentials are
reused privately, not returned to the reviewer or embedded in Docker environment
configuration. The previous permissions correction remains present.

An isolated network-disabled offline check must pass before creating the stopped
replacement. Nonroot UID, read-only filesystem/mounts, dropped capabilities,
no-new-privileges, finite resources/logging, no published ports, disabled image
healthcheck and no automatic restart remain unchanged. Only its independent state
mount is writable. No server data, configuration or Docker socket is mounted.

The final start script requires the exact created container, image, installed
configuration/launcher/distribution hashes and empty new journal. It writes its
attempt before one bounded start. Unknown delivery requires inspection of that
same attempt/container; there is no automatic restart, replacement or force kill.
A running status alone is not scoped read acceptance.

No blocking finding remains for this conditional rollout. Preserve actual stop,
stage and start receipts, and separately establish catalogue/typed-object read
results before reporting house-read success. Control and broad LIVE-01 acceptance
remain incomplete. No HA/server restart or core/SDK change is part of this plan.
