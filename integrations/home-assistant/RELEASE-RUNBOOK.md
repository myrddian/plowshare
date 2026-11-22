# Home Assistant release and recovery

This runbook covers the separately packaged Assist component and outbound provider
host. It prepares an operator-authorized rollout; it does not record an installed
or live-validated release. Source review, publication, merge, deployment and live
acceptance are separate milestones in the
[delivery ledger](../../docs/home-assistant-delivery-state.json).

## Identify the candidate

Use the [final validation evidence](../../docs/reviews/home-assistant-final-validation-evidence.json)
and its [independent verification review](../../docs/reviews/home-assistant-verify-01-v1-review.md)
to identify the exact tested bytes. The evidence contains 116 source hashes,
including uncommitted/untracked adapter work; its base commit alone is insufficient.
The evidence SHA-256 is
`dbf472faa3aa0a62a832698e097ef77d0790da169b909bf062324dbc601a5e67`.

| Artifact | Tested identity |
| --- | --- |
| Assist ZIP | `assist/build/plowshare-assist.zip`, SHA-256 `9f3be08f07b5b72849f2d62b407119292e10663bdb112da4dd2f0cf16d267038` |
| Assist contents | 51 ZIP entries, including `package-manifest.json` describing 50 files |
| Outbound installation | `build/install/plowshare-integration-home-assistant`, 56 files including 26 runtime JARs; every relative path/hash is in the evidence |

Check the ZIP digest and complete contents manifest, and every outbound payload
hash, before transfer and again at private staging. A matching application version
string or JAR filename does not identify the tested artifact. Any changed source
or rebuilt output requires comparison and relevant verification before switching.
The runbook itself is repository documentation, not an extra file silently added
to those frozen artifacts.

From a matching source checkout, the build/check entry points are:

```sh
python3 integrations/home-assistant/assist/scripts/package.py --output /path/to/private-staging/plowshare-assist.zip
./gradlew check :plowshare-integration-home-assistant:installDist --max-workers=2
python3 integrations/home-assistant/scripts/check-outbound-package.py --distribution integrations/home-assistant/build/install/plowshare-integration-home-assistant --report /path/to/private-staging/outbound-check.json
```

Builds need the repository toolchains. The outbound host needs Java 21 and does
not bundle a JRE. For the isolated HA checks, use Python 3.14 and the pinned check
extras described in [Assist verification](assist/README.md#verification).
No database/full-db or live model/device check is needed to reproduce the offline
acceptance. Explicit SDK distribution checks are a separate entry point.

## Prepare the intended target

Record the intended HA installation, actual HA/Python versions, Plowshare
connection, selected artifacts and operator-authorized restart/check scope in
private deployment records. The tested baseline is **HA 2026.9.4/Python 3.14**;
the user's running HA version has not been inspected. A different version needs
compatibility verification or an explicitly chosen upgrade before installation.
Do not infer that an older target supports the current Conversation/AI Task APIs.

Configure explicit origins, private credential sources, project, execution
principal, journal paths and named agent routes. Choose one
[Application profile](assist/PACKAGE-PROFILES.md) and provision actual membership
and exact grants using the supported Application workflow. Empty example access
lists, visible agent catalogues and HA UI control flags do not issue grants.
Task-only generation needs no HA provider token; house reads/control need the
separate [provider host](PROVIDER-HOST.md#configuration-and-authority). Keep its
read/control prefixes, exact selections and separate journals as documented.

Run the installed provider's `--provider --check` against the private configuration
before starting it. That command validates configuration without reading tokens,
opening journals or connecting. Verify credentials/actual grants through the
supported operator controls only within the authorized target scope. Keep tokens,
addresses, prompts and journals out of source and review evidence.

Before a switch, privately inventory pending/uncertain job IDs, external aliases,
entry IDs and provider receipts. Preserve current HA config entries,
`.storage/plowshare.<entry_id>` journals, provider journals, effective configuration,
ownership/permissions and previous artifacts. Take backups using the target's
supported method, record their hashes and validation, and retain a compatible
rollback artifact. Listing a backup is not a restore rehearsal.

Set finite phase budgets for the actual target before starting. Suggested
observation allowances are ten minutes each for backup/transfer and two minutes
each for clean stop/start readiness; adjust them explicitly to the target's
supported mechanism. SDK/provider/storage calls keep their documented resource
budgets. An expired observation stops further rollout steps and triggers inspection;
it never proves physical cleanup, remote abort or permission to kill accepted work.

## Switch and verify within the authorized scope

1. Stop new intake and let existing owners settle through normal supported
   shutdown. A failed/incomplete drain requires inspection; do not run a second
   provider host around an occupied journal or replace an unsettled HA runtime.
   An accepted Plowshare job may continue after its HA connection closes.
2. With a clean operator-authorized HA stop, install only the archive's
   `custom_components/plowshare` directory at the documented
   [HA path](assist/README.md#build-and-install). Keep all private config/journals.
   Replace the outbound binary installation separately and preserve its private
   configuration and journals. No database migration or further core change is
   part of this adapter release.
3. Start each selected component normally. Check actual loaded versions/artifacts,
   bounded configuration/catalogue validation, setup and cleanup errors. Healthy
   process/container status alone does not prove authentication or functional use.
4. Begin separately authorized functional checks with scoped discovery/read and
   read-only Assist. Start a new chat without a supplied conversation ID; preserve
   HA's returned ID for the next utterance. Verify selected agent/project routing,
   caller isolation and the explicit error paths before enabling control.
5. If AI Task checks are authorized, use a bounded supplied-data text task and a
   supported flat structured task. Each repeated action is a new invocation.
   Inspect retained work before repeating anything after an uncertain response.
6. Test voice only with the intended HA speech-to-text/Assist/text-to-speech
   pipeline. HA owns those engines; the component supplies the conversation entity.
   Test a device mutation only when its exact reversible target/action is
   authorized, once, and observe physical state separately from acknowledgment.

Record exact artifacts, actual target versions, checks, original receipts and
observed outcomes without private payloads. Mark deployed and live-validated
milestones only for actions actually completed. Backend prefix-cache measurements
are optional and do not block source or release readiness.

## Recover without repeating uncertain work

On timeout, lost acknowledgment, failed cleanup or startup error, stop intake and
inspect the original retained job/receipt through supported Plowshare/operator
controls. Preserve original identities. Do not repeat an action, create a new
entry, change a credential, delete a journal or reset an alias to obtain a cleaner
response. Changing a credential fences earlier receipts rather than transferring
their authority. Corrupt/unsupported and legacy journals remain preserved for
inspection; the component does not automatically import old local aliases.

A same-process entity reload can preserve owned HA chats. Direct Conversation
calls with an unowned old chat ID are refused; direct expiry transitions return
an explicit error/new fenced ID without replacement model work. Native Assist
can replace an expired or restarted session's ID before invoking this component.
Its matching active empty session/log may be admitted as a new conversation;
the adapter cannot recover an original ID that HA no longer supplies. This is not
old-chat continuity: earlier SDK receipts remain retained and are never imported,
erased or replayed. Reconcile outstanding effects before deliberately beginning
a fresh session. A pending follow-up in the same owned chat reconciles existing
work rather than submitting its new instruction.

Rollback replaces code only after checking the older artifact can read the
current journal/configuration formats and preserving all newly written receipts.
Do not restore a stale backup over newer pending/uncertain work. If compatibility
is unknown, keep intake stopped and retain both artifacts/state for reconciliation;
do not force an older component to discard fields. A stopped/reverted client does
not cancel an accepted server job or undo a physical action.

The [Assist contract](assist/README.md#sessions-receipts-and-recovery) and
[provider lifecycle](PROVIDER-HOST.md#lifecycle-and-budgets) own detailed recovery
behavior. HA prepared text keeps ordinary task/user semantics under the selected
Plowshare agent. No native system-role, special compaction or prefix-cache rewrite
guarantee is part of this release. Attachments/images, nested/custom schemas,
evaluation and HA LLM tool translation remain unsupported.
