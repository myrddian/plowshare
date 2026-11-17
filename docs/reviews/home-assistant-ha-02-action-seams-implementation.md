# HA-02 affected action seams: reapproval evidence

Status: READY for independent affected review; this note does not approve or
replace the previous frozen HA-02 report.

HA-04's coordinator explicitly authorized additive action-only changes to three
accepted HA-02 production paths. Comparing the current tree with
`home-assistant-ha-02-v2-candidate.json` confirms that these are the **only three
changed paths in that candidate's file set**. All other HA-02 v2 manifest files,
including its previous reports and tests, retain their exact recorded hashes.
The new action contracts and focused tests belong to the separately reviewed
[HA-04 candidate](home-assistant-ha-04-implementation.md).

## Three additive seams

1. `HomeAssistantAdapter.actionExecutor(bindingName)` supplies a typed
   credential/binding-isolated action attempt over the existing native connection.
   It requires the exact original configured service/profile/bundle and validates
   parameters before effects. The existing command helper now has an action-only
   overload that runs a local guard after command-slot queueing, outside the
   adapter monitor; captures the native epoch/request ID; checks interruption and
   remaining time before send; and fences a changed epoch at registration.
   Any possible-send failure, closure, timeout or invalid acknowledgement yields
   an UNKNOWN receipt and is never resent. Native service refusal also retains
   unknown physical effect because partial service work is possible. Ordinary
   read/manual callers retain the original overload and do not acquire the new
   action guard or interrupted-action branches.
2. `HomeAssistantWire.actionContext` reuses the existing acknowledgement decoder
   and returns a typed optional HA context ID/parent ID. It does not expose HA user
   identity or equate acknowledgement with physical success. The previous manual
   acknowledgement method and result shape remain unchanged.
3. `HomeAssistantResourceCatalog` additively implements `ActionOfferValidator`.
   `requireCurrent` checks the existing cached policy/snapshot, full configured
   action profile, exact offered revision and current connection epoch/metadata/
   state/dirtiness/age. It never refreshes, reads state or performs network work.
   Source snapshots are obtained outside the catalogue lock; unchanged cached
   policy/snapshot checks avoid adapter/catalogue lock inversion. Dirty, expired
   or replaced evidence refuses a queued effect and requires an explicit new
   offer. Existing discovery/read methods and HA-01 contracts are unchanged.

The new executor is composed only under a separate explicit control provider,
nonoverlapping prefix and trusted local control ceiling. SDK/provider authority,
journal intent and recovery still own external invocation admission. No server,
SDK, database or prompt-dependent capability was added for this seam.

## Actual regression evidence and limits

The coordinator's focused HA selection passed on these exact hashes: **90 tests
in 13 classes, zero failures/errors/skips, 14s**, including 24 new action cases.
Relevant existing HA-02 regression classes are `HomeAssistantNativeResourcesTest`
(9), `HomeAssistantObservationLifecycleTest` (9), and
`HomeAssistantResourceCatalogTest` (8): **26 current HA-02 tests**. The same run
also passed HA-01 resource contracts (11) and the HA-03 named provider/read/journal/
transport cases (29). The 24 new cases include native delivery/identity/context,
pre/post-send cancellation, current cached offer invalidation without refresh,
SDK file-journal restart, lost acknowledgements, and separate fresh verification.

Canonical `./gradlew format` and a separate `./gradlew formatCheck` passed. Logs:
`/private/tmp/ha04-focused-check.log` and
`/private/tmp/core03a-v2-format-check.log`; preserved copies and the exact 13 JUnit
XML results are under `/private/tmp/ha04-verification-evidence/`.

`HomeAssistantAdapterTest`, `PipelineTest` and `HarnessPipelineTest` were **not**
selected in this invocation. Manual runtime compatibility is an implementation
intent/source-review claim pending those current tests; the earlier accepted
HA-02 evidence is historical and is not substituted for a current manual-path
check. Repository-wide verification and independent affected review remain open.
No real database, live HA, physical action, live grant or deployment was tested.

## Exact affected path hashes

| Path | Prior accepted HA-02 v2 SHA-256 | Current SHA-256 |
| --- | --- | --- |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantAdapter.java` | `d350421b64be10ae30039657440bbcfbfb5cfea8dbe2df436a93564d929565cf` | `24baa8a0c5116b71f989ff16903ba5ba134c312409d2f48b8a1bae7b1a1f21ce` |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantResourceCatalog.java` | `8349e14cdfa47752ab438fe8d1c7ece491365faa1aa1bc204e48386223f8b452` | `186f5dddbb62622a3e57f192882a2acd3ca4f93cda66f5cd93abd7c6b1c46acb` |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantWire.java` | `2bf3d3d5c19c4e9da7ca2cd911b8b30538c0845c89d246e85b6ab1084ebcd34e` | `34477a58ce99edc6677c61e9079ae7468c88f85350bb38519a7d9e0c608504cb` |

New contract/test dependencies and their exact hashes are listed in the HA-04 implementation report; the coordinator freezes dependency manifests separately. This note's final hash is recorded in the affected candidate manifest.
