# DEPLOY-01 — Dedicated Application and Assist connection phase

Implementer: `/root`. Independent reviewer: `/root/ha_deployment_reviewer`.
This receipt covers the user's explicit request to create the dedicated HA
Application on Production with `house_reader` and `house_task`, keep control off
and connect Assist. Review approval is recorded separately. Private endpoints,
account/token identifiers, credentials and operator configuration remain outside
source. The earlier component-only receipt/candidate/review are immutable.

## Application and authority

`home-assistant-readonly` is registered as a permanent **DISJOINT Application** in
the configured server Application FileStore, with no writable areas. Its two
agent files exactly match the independently reviewed read-only profile. The
reader has dynamic external read tools enabled, no built-in tools or callees;
the task agent disables dynamic tools and has no tools/callees. Both use the
configured `reasoning` model alias, 20-step/40-model-call caps and no automatic
allowance increases. Local/server commands remain off.

The Application has a separate SERVICE account, an actual Contributor membership
and a 30-day token scoped only to this project with a Contributor ceiling. The
human operator's Manager cap is separate. A request authenticated with this new
credential successfully returned `read`/`work` access as Contributor and served
both requested agents. The execution principal and static read-provider principal
match that token's identity. Only four exact `ha_read_*` tools are assigned, only
to `house_reader`; no control provider, control grants, wildcard, delegation or
workspace write admission was added. The credential is retained in HA's private
configuration and the Production root-only secret store, never in Application
source or these reports. Its metadata/expiry are retained privately for rotation.

The managed first-deployment route returned a definite project-access refusal
before source activation. Independent source inspection identified a common
namespace-admission bootstrap gap. We used the documented permanent external
source registration/manual service workflow instead: publish a minimal manifest,
register its DISJOINT source with `application.create`, establish the dedicated
membership/token, then publish the reviewed agents/provider configuration and
final execution manifest through that source owner, with the manifest last.
This was not a create/forget or attach/detach workaround. No server runtime, API,
SDK, schema, authorization implementation or server image was changed. Future
source updates retain this external ownership; they do not use managed-release
activation or claim an Application-owned `service` identity.

## HA entry, pipeline and compatibility correction

HA Core 2026.9.4/HA OS 18.3 required no upgrade. HA successfully created its
Plowshare entry with Conversation enabled, `house_reader` selected, AI Task
explicitly enabled with `house_task`, and control disabled. Both entities loaded.
The Conversation entity is `conversation.house_reader`. The existing HA assistant
was preserved. A separate **Plowshare (read-only)** Assist pipeline is preferred,
selecting that Conversation entity, with local-intent preference off. It retains
the existing speech-to-phrase recognition and Piper en_US/amy-medium output
settings. Existing HA sentence-trigger automations remain HA behavior; this
configuration does not claim to disable all native automations.

The initial text check exposed the first native session-ID defect before SDK
submission. The independently approved [HA-FIX-01 correction](home-assistant-ha-fix-01-v1-review.md)
and [its evidence](home-assistant-ha-fix-01-v1-evidence.json) supersede that source:
141 HA outer tests, including six real pipeline cases and the existing three
installed-consumer subprocess cases, passed with Ruff; repository checks passed.
The refreshed 0.1.1 artifact SHA-256 is:

`1ad223a4859e415fd9e2b63344a3736b162a175e753e2fc3f7b8371e049110aa`.

Before replacement, a new supported Supervisor configuration backup was created
excluding the recorder database. It returned `ok`; its archive was nonempty,
tar-readable and contained the Home Assistant payload. Backup identity/hash are
private; this was not a restore rehearsal. The updater verified the installed
0.1.0 source against its preserved baseline, admitted no unsettled local journal,
verified every staged/new component hash and used a same-filesystem code switch
while HA was normally stopped. It retains the old code separately for code-only
recovery. Normal stop/start both returned `ok`. The final 37 source files match
the approved 0.1.1 package; filtered entry/configuration and correlation-journal
fingerprints are unchanged. The transfer listener was closed afterward. No
uncertain lifecycle call was replayed and no stale state backup was restored.

## Live text result and remaining limits

A fresh native Assist chat returned **“Plowshare Assist is connected.”** The same
service credential's retained Production job independently confirms
`house_reader`, `DONE`, `ANSWERED`, one model call, one step and zero tool calls,
with that exact answer. There was one submitted model request; the earlier
first-turn refusal occurred before submission. The original retained job was
read rather than repeating the request. The preferred pipeline, disabled-control
notice and answer are recorded in a private screenshot/accessibility snapshot.

This establishes the requested Application, scoped credential, loaded entities,
preferred Assist selection and live text round trip. It does not establish a
microphone/audio/TTS run, AI Task generation, physical state or device effects.
The separate house-read provider host is **not running**: declaring the read
scope and seeing the agent in the catalogue do not make sensor readings
available. That provider needs its own HA API credential and an explicit fixed
entity selection; control remains disabled. Full provider installation and the
broader LIVE-01 acceptance remain separate work. A scoped approval for this
Application/Assist phase must not silently close those remaining contracts.
