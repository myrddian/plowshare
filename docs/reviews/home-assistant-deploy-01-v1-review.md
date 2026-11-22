# DEPLOY-01 v1 independent review

Verdict: **APPROVE**.

Scope: HA component installation, normal restart and discoverable setup form
only. This approval does not establish configured entities, Production Application
provisioning, an outbound provider host or live model/voice/device acceptance.

Reviewer: `/root/ha_deployment_reviewer`.
Implementer: `/root`.
Candidate: `docs/reviews/home-assistant-deploy-01-v1-candidate.json`.
Candidate SHA-256:
`09fab13ec6b2044777072168f3d42d33f8a8b7c8445013c30485fc9b6698420e`.

I independently read the deployment report, frozen diff and ledger snapshot,
release dependency, actual installation script, constrained remote receipt,
private deployment evidence, transfer log and live setup-form screenshot. I
verified the candidate identity, report/snapshot/diff hashes and all five private
evidence hashes. The dependency manifest and current release documents match.
I authored no installation code, changed no target or ledger and submitted no
model or device work. This review report is my only authored file.

## Findings and resolution

Preflight found that an initial move from temporary storage could cross
filesystems, and requested post-stop state checks, symlink refusal and explicit
lifecycle observation limits. The final script resolves these findings:

- It stages and verifies the component on the configuration filesystem before
  the final move. The coordinator recorded matching device IDs after following
  the known configuration-root symlink. The misleading earlier non-following
  stat observation is not used as filesystem evidence.
- After a successful normal Core stop, it rechecks that no component, Plowshare
  entry or correlation journal appeared. It rejects destination/journal symlinks,
  including dangling journal links. There was no previous Plowshare installation
  or retained work to overwrite.
- Stop/start observers have explicit 180-second limits. A timed-out observer does
  not establish remote abort and does not trigger an automatic repeated lifecycle
  request. Private stage/work/receipt evidence is retained on failure. The actual
  successful receipt reports both stop and start as `ok`; no failure-recovery
  branch was needed.

No blocking finding remains for the declared installation scope.

## Evidence inspected

The native version preflight and authenticated target observations report HA
Core 2026.9.4 and HA OS 18.3. Core matches the tested baseline, so no HA update
was necessary for this component. No HA/OS update occurred. The report correctly
does not claim independently enumerated target Python/dependency patch versions.

The Supervisor configuration backup returned `ok`. The script requires a
nonempty archive, successful complete tar listing and the Home Assistant payload,
and records its SHA-256 privately. The report accurately limits coverage to
configuration excluding the recorder database and does not call this a restore
rehearsal.

I independently reverified archive SHA-256
`9f3be08f07b5b72849f2d62b407119292e10663bdb112da4dd2f0cf16d267038`,
all 50 contents-manifest hashes and the exact 37-file component subset. The
installation script verifies those hashes after transfer and again at the final
installed paths; the constrained successful remote receipt records completion.
The transfer log records the corrected 4,457-byte script and the archive. These
are reviewed script/result evidence, not a claim that I separately accessed the
target filesystem. All 116 source hashes in final validation evidence remain
unchanged. Profile templates and archive-only guides were not installed as
component files.

The inspected live screenshot shows HA's real **Connect Plowshare profiles**
form, with the expected token, Application and separate agent fields. It contains
no supplied service credential or Application/agent selection; control and AI
Task are off, and the form has not been submitted. Alongside the recorded Core
restart and catalogue discovery, this supports successful component discovery
and configuration-flow loading. It does not prove a configured entry or any
functional model/device operation. The manifest identifies component 0.1.0 with
Conversation/AI Task dependencies and `websockets>=17,<18`.

The coordinator records closing the temporary transfer listener. No credential
was extracted or copied into the setup form. Production was selected by the
user, while its missing HA Application and scoped credential remain explicit
configuration work rather than guessed values.

## Completion boundary

DEPLOY-01 may be accepted as the authorized HA component installation/discovery
slice. Record that narrow deployed scope explicitly. Keep Production Application,
service credential and agent selection, configured Conversation/AI Task entities,
outbound provider installation and LIVE-01 unfinished. Installation acceptance
must not be presented as an operational house agent or live voice/control success.
