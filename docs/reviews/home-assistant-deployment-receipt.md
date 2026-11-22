# DEPLOY-01 — HA component installation receipt

Implementer: `/root`. Independent reviewer: `/root/ha_deployment_reviewer`.
The user authorized installing the reviewed component on their specified HA
instance after checking its version. This report is ready for review; approval
is separate. Private host addresses, credentials and target configuration are
excluded from source.

## Version and backup

The native unauthenticated HA WebSocket greeting reported Core **2026.9.4**.
The authenticated Terminal add-on independently confirmed that version, amd64
and HA OS **18.3**. Core exactly matches the validated integration baseline;
no HA update is required for this component. HA's own Settings page offered
Core 2026.11.2, but no HA or OS update was performed. The exact Python/dependency
patch versions were not separately enumerated on the target.

Before installation, the target had no Plowshare component, configuration entry
or correlation journal. The supported Supervisor CLI created a partial backup
of the Home Assistant configuration, excluding the recorder database. It returned
`ok`; the archive was nonempty, its full tar member listing succeeded and it
contained the Home Assistant payload. Backup identity and SHA-256 are retained
privately. This validates archive readability and declared coverage, not a
restore rehearsal or recorder-database backup.

## Installed bytes and lifecycle

The installed source is the independently approved [Assist package](home-assistant-ha-11-v1-review.md)
and [final installed-contract validation](home-assistant-verify-01-v1-review.md),
whose [evidence](home-assistant-final-validation-evidence.json) binds the source
and archive. Archive SHA-256:

`9f3be08f07b5b72849f2d62b407119292e10663bdb112da4dd2f0cf16d267038`.

Only the 37 component files were installed under
`custom_components/plowshare`, including the unchanged public SDK, resources and
license. The source archive's 50 content hashes passed after transfer; all 37
installed component hashes passed against that manifest. Profile templates and
archive-only documentation were not copied into HA's component namespace.

The component was staged and verified on HA's configuration filesystem before
publication. Following the known configuration-root symlink, configuration and
`custom_components` had the same filesystem device ID. The script rechecked
absence of component/entries/journals after a successful normal Core stop, then
renamed the staged component and started Core normally. Both CLI results were
`ok`; the UI subsequently reported that HA had started and Terminal reconnected.
Stop/start observations had 180-second limits. No timeout/recovery branch was
needed, and no uncertain installation or agent submission was replayed.

## Actual loading and configuration status

After restart, HA's **Add integration** catalogue offered **Plowshare**. Selecting
it successfully loaded the real **Connect Plowshare profiles** form with the
expected service-token, Application and separate agent fields. The component's
manifest declares version 0.1.0, Conversation/AI Task dependencies and
`websockets>=17,<18`. The live form establishes discovery/import/config-flow
readiness; it does not establish a configured entry or functional agent run.

The user selected Production. Its origin is prefilled in the open form. A
read-only catalogue lookup through the explicitly selected installed Production
CLI connection found no HA Application. No Application/agent was guessed or
provisioned and no administrator token was copied into HA. A project, agent
selection and project-scoped service credential remain to be configured.
The form was not submitted: **component installed and discoverable; no configured
Plowshare entry or Conversation/AI Task entities yet**. Control and AI Task remain
off in the form. No model, voice or device test was issued, and the separate
outbound provider host was not installed by this HA-component operation.

## Evidence and scope

Private evidence contains the constrained transfer receipt, original Supervisor
results, backup hash, deployment-script identity and live form screenshot. Its
SHA-256 is recorded in the frozen deployment candidate. The temporary package
listener was closed after transfer. Existing accepted source/review artifacts
remain unchanged; no further core/SDK/runtime/schema change was made.

Installation approval, if issued, applies to the HA component bytes, normal
restart and discoverable setup form only. It must not mark Production Application
provisioning, configured entities, outbound tooling or LIVE-01 complete. The
[release runbook](../../integrations/home-assistant/RELEASE-RUNBOOK.md) retains the
state-preserving recovery contract.
