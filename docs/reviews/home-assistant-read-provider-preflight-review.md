# Read-provider staging preflight — independent review

Verdict: **APPROVE**.

Scope: Stage the separate read-only provider distribution/configuration and create
its stopped container. This is not approval evidence for successful startup,
provider availability or live house reads. Review the actual staging receipt
before the single explicit start.

Implementer: `/root`.
Reviewer: `/root/ha_deployment_reviewer`.
Private candidate: `/private/tmp/ha-read-provider-20261123/preflight-v2-candidate.json`.
Candidate SHA-256:
`33959a916d2b28178fb4fec63ff6d1f043cb86340d5011373b90a897a1e93c49`.
The initial rejected preflight and script remain historical evidence.

I read the actual bound remote installer, one-shot submission wrapper, launcher,
configuration, distribution manifest/archive, selected-entity record and safe HA
credential-check result. All seven candidate file hashes and the separate
submission-wrapper hash match. I read no token/credential file, made no live call
or mutation and changed no implementation or ledger. This report is my only new
file.

## Findings and configuration

The initial installer used a private umask with directory modes that would leave
nonsecret root-owned mounts untraversable by container UID 1000. The corrected
installer explicitly gives only distribution/configuration directories mode 0755.
The private root/secrets directories remain 0700; state is 0700 owned by UID 1000,
and individually mounted secret files are 0400 owned by that UID. This resolves
the blocking preflight finding. No blocking finding remains for staging.

The selected record contains 68 distinct entities. The provider configuration
contains exactly that fixed set, with empty attribute lists, no actions, no
subscriptions and no control block. It does not automatically follow later
changes to HA's exposed entities. Its project, authenticated execution principal,
logical provider and prefix exactly match the deployed read-only Application.
The named tools remain the four already-granted read tools; catalogue publication
creates no authority and exposes no device-control path. Entity filtering is the
adapter's fixed policy, not a claim that the underlying HA bearer has narrower
native permissions.

The 120-second lease, 10-second renewal, 500-ms polling, 20-second connection and
2-second request/cleanup budgets fit the existing host contract. The private
journal is separate from the server, HA component and any control provider.

## Artifact and container boundary

I independently verified all 56 archive members against the exact distribution
manifest, including regular-file/path admission. Its 28 launcher/JAR hashes match
the previously accepted outbound binary evidence. No core, SDK or shared runtime
patch is introduced. The existing digest-pinned server image supplies Java only;
the explicit entrypoint/classpath runs the reviewed HA distribution instead of
the server. No server process restart/configuration change is part of staging.

The network-disabled offline check runs as UID 1000 against read-only distribution
and configuration, without credential or journal mounts. Only after that succeeds
does the installer create the separate stopped container. The runtime container
has no published ports, automatic restart, server data/configuration or Docker
socket mount. Its root filesystem is read-only, capabilities are dropped and new
privileges disabled. Heap/container resources and log retention are finite; a
bounded temporary filesystem and independent writable state are explicit.
Secrets are mounted as private files and read by the launcher inside the process,
not embedded in Docker environment configuration or Application source.

## Lifecycle and next evidence

Both local and remote guards refuse an existing attempt, provider directory or
container instead of replacing an uncertain owner. The one-shot wrapper records
its attempt before SSH submission. An uncertain observation must be reconciled
against those retained identities rather than resubmitted. No staging path starts
the runtime provider or invokes a model/device action.

Recovery specifies one SIGTERM and bounded observation of the original process;
unsettled ownership remains retained, with no journal reset, forced kill or
container replacement. Automatic restart is disabled. Startup approval still
requires the actual stopped-container receipt, installed artifact/configuration
hashes, ownership/mount settings and unchanged server evidence. Availability and
scoped discovery/read acceptance require their own actual public results.
