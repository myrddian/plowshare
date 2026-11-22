# HA Assist and automatic read-provider recovery — publication

This handoff publishes the existing-SDK HA Conversation/Assist and optional AI
Task integration, independent Application profiles, installable component,
read-provider packaging, native first-turn/schema corrections and verified
read-only recovery. Control defaults off and remains disabled in the deployed
68-entity state/unit provider. The optional control template is separate and inert;
UI capability flags do not grant authority. HA context remains ordinary task/user
input without native system-role or prefix-cache rewriting claims.

The branch is based on the validated main revision `4a3ed34e`; its incoming Desktop-only changes
do not overlap HA or SDK code. The accepted PLAT-SQL-01 mock regression fixture
correction is a separate test-only commit. No production core/API/SDK/schema
change is part of this PR. Earlier external-ID mapping work is already on main.
Remote main advanced independently to `872e051a` after this validation; those
later runtime/workflow commits are outside this checkpoint and its test claims.

The [current publication evidence](home-assistant-assist-recovery-publication-evidence.json)
binds the final source, package files, tests and 31 independent approvals. Initial
source-readiness evidence is historical: HA-FIX-01/02 and RECOVER-01 supersede
changed bytes; their frozen manifests/reviews/snapshots remain unchanged. The
outbound README's obsolete feature-gate link is corrected to the separate Assist
component. Runtime code and permissions are unchanged by that documentation fix.

Validation passed: 141 isolated HA Python cases with Ruff, including six native
pipeline and three installed-consumer cases; all 189 HA Java cases; the default
repository format/check and installDist on that declared baseline (90 seconds,144 tasks);
the owning module check after the README correction; and eight fresh installed
outbound checks over 56 files/26 JARs. SDK package conformance is retained from the
accepted source chain because SDK source is unchanged. No database tests were run.
The preserved Assist0.1.1 archive has SHA-256
`1ad223a4859e415fd9e2b63344a3736b162a175e753e2fc3f7b8371e049110aa`.

The [deployment review](home-assistant-deploy-01-v3-review.md) and
[recovery review](home-assistant-recover-01-v1-review.md) confirm actual HA-to-agent
reads and both provider-only process/network recovery checks. Same-container
restart policy, original journal identity/owner/replay fences and unchanged jobs
were verified. The Docker boot setting/persisted policy are verified; a shared
host reboot was not performed. Unconfirmed publication receipts stay fenced.
Raw credentials, private endpoints/configuration, readings and diagnostic logs
remain outside source; public evidence contains sanitized summaries and hashes.

Microphone/STT/TTS, AI Task generation, full follow-up/isolation and physical-control
acceptance remain outside this publication's live claims. Optional cache backend
measurement remains separate. Source approval, publication, merge and deployment
are distinct: the final Git refs/PR metadata identify publication; this checkpoint
does not claim the requested PR has already been merged.
