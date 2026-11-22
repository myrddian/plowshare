# Read-provider single-start — independent review

Verdict: **APPROVE**.

Scope: One explicit start of the exact created/stopped read-only provider.
Approval does not establish running availability, successful catalogue renewal,
house reads, model results or complete LIVE-01 acceptance.

Implementer: `/root`.
Reviewer: `/root/ha_deployment_reviewer`.
Private candidate: `/private/tmp/ha-read-provider-20261123/startup-candidate.json`.
Candidate SHA-256:
`a47fe33f6966dec175ac3db83fde8586e7fe486846a4ddb27909a60f62af4ef7`.

I independently read the guarded start script, actual stopped-container staging
receipt and separate target staging audit. All four candidate evidence hashes,
the approved preflight-v2 identity, its seven bound file hashes and submission
wrapper hash match. The actual receipt's archive, configuration and launcher
hashes match their reviewed local artifacts. I read no token file, made no live
call or mutation and changed no code, target or ledger. This report is my only
new file.

The staging receipt and audit identify the same exact container and digest-pinned
runtime image. Its state is `created`, with an empty independent journal, all 56
installed distribution hashes matching and a successful network-disabled offline
configuration check. The fixed policy still selects exactly the reviewed 68
state-only entities, with no actions, subscriptions or control provider.

The actual audit confirms UID 1000, the explicit provider entrypoint, read-only
root filesystem, dropped capabilities, disabled new privileges, no automatic
restart, no published ports and disabled image healthcheck. Only the independent
state mount is writable; reviewed distribution/configuration, launcher and
individual secret files are read-only mounts. There are no server data or Docker
socket mounts. The audited Docker environment names contain no HA/provider
bearer variables; inherited server image defaults are not server processes or
credentials, and the explicit launcher/classpath runs only the HA distribution.

The start script refuses an existing attempt and requires the original container
ID, created state, image identity, exact configuration/launcher/distribution
hashes and empty journal. It records the attempt before issuing one bounded
`docker start` call. An uncertain result is retained for inspection, without an
automatic repeat, container replacement, forced shutdown or journal reset. The
short subsequent state observation is explicitly not functional-read acceptance.

No blocking finding remains for that single start. Preserve its receipt and
inspect actual provider state/catalogue after startup. Scoped discovery/read
checks must use the original fixed entity policy and report unavailable evidence
honestly. Control stays disabled; broad voice/device/LIVE acceptance remains
separate. No server restart, core/runtime/SDK change or device action is approved
or claimed by this review.
