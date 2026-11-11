# Operating Network Privacy Watch

Start with the [guided installation](SETUP.md#start-and-use-the-dashboard). It
prepares a private Application, deploys it paused, provisions a separate service
account and opens the Python dashboard. The deployment administrator and human
manager must already exist; an existing model binding and managed FileStore are
server prerequisites. Python runs on the host that can reach your selected devices
and Pi-hole. Plowshare does not start or host this external process.

## Dashboard views

**Overview** shows collection status, the monitored-device count and recent project
investigations. **Review findings** opens the dedicated Findings view. Pause and resume are beside the status cards.
Expand **System health** for connection, agent, tool and credential details;
**Check retained work** reconciles existing receipts without replaying effects.

**Devices** holds observed device cards, Markdown profiles and linked privacy
issues. **Findings** holds observed changes, acknowledgements and investigation
reports beside a Markdown reader. **Evidence** holds the underlying collections,
observations and coverage gaps.
Opening evidence from a device history or finding selects Evidence automatically.
Opening a report selects Findings; it does not replace selected collection evidence.
Report and document readers format headings, lists, tables, quotations, code and
web citations. Raw HTML is displayed as text; embedded content is not loaded.

**Settings** holds device discovery, monitoring scope, Pi-hole, investigation
preferences and schedule frequency. Switching views preserves unsaved form fields
and pending operation state. It does not save, scan, submit work or reconnect.
Use the arrow keys, Home and End to move between the dashboard tabs.

## Finish setup in the dashboard

1. Open **Settings**, then **Find devices** on an explicitly selected private
   subnet. Select up to 256 addresses and eight TCP ports, or prepare the whole
   subnet with **Monitor whole subnet**. Add names and save
   monitoring.
2. Optionally expand **Connect Pi-hole v6**. Supply its HTTP(S) origin and an
   application password. **Test connection** authenticates and reads device
   records without querying DNS or saving the password. **Save connection** stores
   the connection and password independently of connectivity; testing is optional.
   A successful read can still report missing, stale or ambiguous associations.
   Saved changes wait for earlier scan work to settle before activation and a new
   comparison baseline.
3. **Request a scan**. Read its observation time, scope, coverage gaps and evidence.
   A completed collection does not mean an investigation has completed.
4. Expand **Investigation and alert preferences** to choose automatic admission,
   a daily limit and cooldown. The defaults are eight automatic investigations
   per UTC day and a 60-minute cooldown. These count retained admission decisions,
   including baseline investigations; they do not limit model calls, tokens or
   monetary spend. Legacy changed completions count conservatively on upgrade.
5. In **Settings**, expand **Change schedule frequency**. Choose a listed
   frequency, an IANA time zone and whether to pause. Enter the configured deployment administrator's password
   to **Review schedule revision**. Inspect the full prepared Application source,
   active revision and source digest. Choose a managed FileStore from the dropdown,
   enter the password again, then **Deploy reviewed revision**.

Application schedules are immutable source definitions. This dashboard editor
changes the source timing and initial pause setting through a reviewed Application
deployment, with the reviewed revision as a concurrency fence. For operational
pause/resume without changing source, use Plowshare CLI `schedule pause <internalName>`
or `schedule resume <internalName>`, TUI `/schedule pause` / `/schedule resume`, desktop
Activity controls, or the web console **Schedules** view. Obtain the registered
`internalName` from `schedule files`; use its owning account with project management
authority. Runtime controls preserve an unchanged deployed definition and restart
state; changing the schedule source clears the operational override.

The dashboard editor uses short-lived public login sessions and typed SDK
deployment operations. Administrator credentials are never saved or used by
collection. A newer Application deployed outside this setup is refused until an
operator reviews and synchronizes the private prepared source and its receipt.
The server may need a reconciliation pass before the new schedule is visible.

## Save configuration independently of connections

**Save monitoring** and **Save connection** store validated settings locally even
when Plowshare or Pi-hole is disconnected, or earlier scan work needs attention.
**Saved connection** reports whether a Pi-hole connection and password have been
stored. **Test connection** is optional; it checks the supplied password without
saving it. Saving never tests a connection, starts a scan or resumes a schedule.
The password field clears after either action for privacy; an empty field does
not mean that the saved password was lost.

The dashboard separates **saved** settings from **active** settings. Changes are
stored in a private pending configuration beside the collector configuration.
They apply automatically before the next collection pass once the worker is idle
and its earlier receipts are settled. Pending settings survive a restart; further
saves update the desired configuration without losing other pending fields. Old
scan work retains its original scope, evidence and receipt identities.

If settings are waiting to apply, saving still works. Wait for an active scan,
or use **Check retained work** under **System health** after resolving a connection
failure. This checks existing work without resending uncertain effects. A failed
optional connection test does not remove saved settings. A later dashboard
refresh failure does not undo a confirmed save. Invalid input and local storage
failures are reported separately from connection or activation problems.

## Read health, devices and findings

**System health** shows collector state, last successful contact, last completed
scan, Pi-hole and UniFi coverage, project agents, projected dynamic tools,
recent project investigations and service credential expiry. Remote checks and
report projections are cached for 20 seconds. Visibility is an inspection result;
permissions and availability are still enforced by Plowshare on each tool call.
A checklist highlights missing setup. The expiry notice warns within seven days.

Saving a subnet adds its addresses as candidates. Each sweep checks the saved
addresses. A completed sweep promotes an address into the device inventory when
it finds an open TCP service or a Pi-hole or UniFi association. A refusal alone leaves an
address as a candidate. In **Devices**, switch between observed devices and
candidate addresses, or search by name, address, MAC, hostname or vendor.
The inventory reconstructs first and last TCP responses from retained scan receipts
after a restart. A timed-out check updates the last check time, while the last
response stays at the last successful or refused connection. The latest 50
completed sweeps provide links to individual collections. An older device
association appears separately with its observation time when the latest sweep
has no association. This history belongs to the address; DHCP reuse and randomized
MACs can change which device holds it.

Enable **Quick presence checks every 2–5 minutes** in **Settings → Choose what
to monitor** alongside ordinary collection. File-managed collectors can set
`collection.quickScan` to `true`. The option defaults to off. The collector chooses a new random
delay after each quick check and waits for the normal collection lock. A restart
starts a fresh delay. The quick check covers only saved private IPv4 addresses
and never publishes evidence, fires the scan schedule, or starts an investigation.

Quick checks use connected UniFi clients first, then a Pi-hole v6 network-table
sighting whose address timestamp falls within the current sampling interval.
Remaining addresses get up to two short probes on the first configured TCP
ports. Only an open connection counts as a TCP sighting. DNS profiling is a
separate option and defaults to off. Malformed network-table rows and unavailable sources are
reported separately while the other sources continue.

Device cards show sightings out of completed quick checks over the last
24 hours. A known MAC follows the device across watched addresses. When a MAC
is first confirmed at an address, an uninterrupted run of preceding IP-only
sightings can count toward that device. Once an address has an identified
owner, a later IP-only run needs confirmation from the same MAC on both sides.
A missed check, conflicting association, or gap over seven minutes breaks
continuity. This infers presence from the available observations. An IP change
of owner between two samples can go undetected. Retained journals from older
collectors can credit only leading streaks in an intact history with a retained
address-to-MAC observation at the transition.

A missed response means the check did not see the device. A failed or skipped
check contributes no sample. The local private journal keeps the last positive
association and the latest MAC confirmation or conflict for each address in
the active scope even after the rolling count expires. New scopes start their
own history. Full-scan evidence and scheduled investigations keep their
existing path.

### DNS activity profiles

With Pi-hole configured, select **Profile device DNS activity with Pi-hole** in
Settings and save monitoring. File-managed collectors can set
`collection.dnsProfiles` to `true` alongside `collection.quickScan`. Profiles begin
after two successful quick checks confirm the same MAC at the same watched IP.
UniFi supplies the current association when available. Fresh Pi-hole network
sightings can supply it otherwise. Unknown MACs, conflicting addresses, reused
IPs and interrupted observation windows get no retrospective DNS attribution.
Two matching observations still leave gaps in visibility between checks.

Device cards show the last 24 hours of sampled DNS activity, grouped by
streaming services, advertising, audience measurement and unclassified
destinations. Counts distinguish DNS responses reported blocked and show partial
coverage if samples are truncated or unavailable. Category totals use the
retained destinations, with a maximum of 32 per window and eight shown per
category. Profiles remain in the collector's private presence journal and use
its rolling retention. They do not submit DNS history to Plowshare, external
lookups or a model. Turning the option off stops new sampling and hides the
profile while older windows age out.

The category list uses published service domains, including
[YouTube embeds](https://support.google.com/youtube/answer/171780),
[Google video delivery](https://knowledge.workspace.google.com/admin/getting-started/set-up-a-google-workspace-host-name-allowlist),
[Google Ads](https://developers.google.com/publisher-tag/guides/general-best-practices),
[Microsoft Advertising](https://learn.microsoft.com/en-us/xandr/monetize/export-placement-tags),
[The Trade Desk](https://partner.thetradedesk.com/v3/portal/resources/doc/TrackingTagsUniversalPixel),
and [Google Analytics](https://developers.google.com/analytics/devguides/collection/protocol/ga4/reference).
Classification describes the destination's known association. A query count
does not show time spent in an app, whether a connection succeeded, or whether
data was sent. Pi-hole visibility depends on the client using Pi-hole directly
and the query log retaining client and domain details. For sessions and bytes,
UniFi provides a separate [Traffic Flows export](https://help.ui.com/hc/en-us/articles/32201256219799-Traffic-Flows-and-Traffic-Logging-in-UniFi-Network)
outside the client inventory API used here.

### UniFi identity

UniFi reads connected wired and wireless clients through the controller's local
API. It keeps names and MACs for saved addresses only. The sweep withholds the
association for an address if sources disagree on its MAC. A failed UniFi read
appears under coverage gaps while TCP and other configured sources continue.
An address missing from the connected-client list has unknown UniFi coverage for
that sweep.

Quick presence also reads the bounded adopted-device inventory. Online adopted
switches and access points can supply watched identities even when they are
absent from the connected-client list. A unique matching MAC joins the adopted
name and product details to the connected client's current address, even when
the adopted record reports a different address. Conflicting MACs at one
address stay unresolved. Managed devices with current product evidence take
priority in the background research queue.

A UniFi gateway can advertise its WAN IP in the adopted inventory. If the
configured API host is the watched LAN gateway, stop the collector and use the
bootstrap `bind-unifi-gateway --device-id <adopted UUID> --lan-mac <confirmed LAN MAC>`
command. Obtain both values from the operator's controller and LAN records.
The collector checks that the bound device is online and that the same site's
enabled default gateway network advertises the configured private API host.
It does not infer an alias from a device name or the final bytes of a MAC.
The binding does not change the scan scope.

Open a device's type panel to see the observed name and conventional TCP port clues
from its latest completed sweep. Conflicting or insufficient clues leave the
type unknown. Possible services have exposure checks you can verify locally.
Port assignments are clues to check, and the collector sends no protocol probes
to verify them. See the [IANA service registry](https://www.iana.org/assignments/service-names-port-numbers).

Choose **Research device** on an observed card to review the local identity and
device-type clues. **Read UniFi product details** looks up the observed MAC in the
adopted-device inventory and checks for a unique online match.
Supported adopted switches, access points and gateways can supply model and
firmware, and UniFi may report an available firmware update. Connected-client
names and Pi-hole hostnames, MAC vendors and timestamps are identification clues.
For other devices, confirm the public model from a label or management page.

Review the public manufacturer and exact model before choosing **Look up
advisories**. This sends only the model to NVD and works before a profile is
saved. **Use model in profile editor** fills the existing profile form for review.
These actions leave the full-scan schedule and investigation activity untouched.

To enable advisory lookup, set the collector's
`PLOWSHARE_PRIVACY_NVD_ORIGIN` to the HTTPS origin of the NVD API
(`https://services.nvd.nist.gov`) before starting the dashboard. Choose
**Look up advisories for saved model** in the profile to send only that model
name. The dashboard shows up to ten keyword matches, any primary CVSS rating
reported by NVD, and any CISA known-exploitation flag reported there. Check the
vendor advisory for the affected model, firmware and configuration before
linking an issue to the device. A search without matches leaves applicability
unknown. The lookup runs only on request and leaves scan evidence and profiles
as saved.

**Check moved devices** compares fresh Pi-hole associations with the last local
LAN discovery, bounded to 256 addresses and read in 32-device batches. Confirm each suggested move
explicitly. Confirmation rechecks the association and uses the normal idle/scope
fence. No automatic retargeting, subnet discovery or scope expansion is available
to an agent.

**Mark expected** acknowledges a specific observation pattern. Its original
source remains readable, and **Expected · undo** reverses the acknowledgement.
Future automatic investigation decisions omit acknowledged patterns when no
other new finding needs attention. Previously admitted work is unaffected.
The dashboard keeps the latest occurrence of each pattern from the last 50 scans.

Select a collection to read its evidence, admission decision, any explicit start
receipt, and reports whose retained inputs actually cite that revision. Recent
project runs are shown separately: the integration does not guess that a run
belongs to a particular scan merely because their times are close.

When automatic admission was withheld, **Investigate this evidence** opens an
inline review. **Confirm one explicit investigation** starts one additional job
through the public SDK. This may incur model costs beyond the automatic limit.
An intent is retained before submission; the same identity cannot resubmit an
unknown outcome, and a second intent for the same scan is blocked until the first
is positively refused. Automatically admitted and legacy completions cannot be
manually duplicated through this control. Run IDs are opaque server identifiers
(`orc_…`), separate from UUID request identities. If a start reply cannot be
retained, keep the manual intent pending and use **Check retained work** to read its
exact receipt. If the service-token receipt read is refused, follow the
[project-scoped receipt gap](GAPS.md#project-scoped-orchestration-start-receipts);
do not retry the investigation or confirm it from a similarly timed run.

Browser notifications are optional and request permission only after your explicit
preference action. They carry a generic notice, with no addresses or DNS names.
The dashboard tab must remain open, including in the background, and the browser
must permit notifications. There is no offline push notification service. Findings
remain visible in the dashboard when browser permission is unavailable or declined.

## Keep the collector running in the background

Background operation is opt-in. Complete installation and open the launcher once
so it remembers your loopback dashboard bind. Finish pending work and stop the
foreground collector before enrolling a service. Keep the installed Python
environment and private setup folder available at the same paths.

Choose an existing per-user service directory. On macOS this must be your
`Library/LaunchAgents` directory. On Linux use a user systemd directory, commonly
`$HOME/.config/systemd/user`. Set `PRIVACY_SERVICE_DIRECTORY` explicitly and create
it if necessary, then run:

```sh
mkdir -p "$PRIVACY_SERVICE_DIRECTORY"
build/privacy-python-env/bin/plowshare-privacy-bootstrap \
  --directory "$PRIVACY_PRIVATE" service install \
  --service-directory "$PRIVACY_SERVICE_DIRECTORY"
```

The current Python interpreter is recorded; `--python` can select another installed
interpreter explicitly. Virtual-environment symlink paths are preserved. The
installer writes a reviewable definition and enrollment receipt before asking the
OS manager to activate it. It refuses to overwrite an existing definition, enroll
an active foreground collector, or manage a definition that has since been edited.
Pi-hole background operation needs a private password file because a login service
does not inherit credentials from your terminal's environment.

On macOS, the user LaunchAgent starts at login and retries unsuccessful exits with
a delay. Its output/error files are private under `service-logs`. On Linux, the
unit is enabled for the user's default target and uses bounded failure retries;
inspect its journal with `journalctl --user -u <label>`. User services normally
start with the user's session. Running before login on Linux requires separately
configured systemd user lingering; this installer does not change that host policy.
These are [launchd](https://developer.apple.com/library/archive/documentation/MacOSX/Conceptual/BPSystemStartup/Chapters/CreatingLaunchdJobs.html)
and [systemd service](https://github.com/systemd/systemd/blob/main/man/systemd.service.xml)
facilities, not Plowshare Application hosting.

Use the generated launcher to open the existing background dashboard. Closing
the browser does not stop collection. The remembered handoff contains only a
process-scoped dashboard credential, never the Plowshare token. Manage the service:

```sh
build/privacy-python-env/bin/plowshare-privacy-bootstrap \
  --directory "$PRIVACY_PRIVATE" service status
build/privacy-python-env/bin/plowshare-privacy-bootstrap \
  --directory "$PRIVACY_PRIVATE" service stop
build/privacy-python-env/bin/plowshare-privacy-bootstrap \
  --directory "$PRIVACY_PRIVATE" service start
build/privacy-python-env/bin/plowshare-privacy-bootstrap \
  --directory "$PRIVACY_PRIVATE" service uninstall
```

Uninstall removes only the exact owned definition and enrollment record. It keeps
the private setup, credential files, evidence, preferences and receipts. Foreground
operation remains available on other platforms. A service manager's status is not
proof that Plowshare checks or investigations are succeeding; inspect the dashboard.

## Recover without repeating uncertain work

Keep the dashboard open when collection stops. Its detail includes available
refusal codes/reasons; local evidence stays readable. Fix credentials, connectivity
or project/provider grants first, then choose **Check retained work**. For a stopped
worker this creates a fresh public SDK connection, keeps the existing provider
journal and checks exact retained evidence, publications, tool results and explicit
investigation receipts before allowing intake again. Missing receipts keep the
work fenced. Investigation receipt reads use the same service account and original
request UUID, with current project and evidence permissions. They do not submit
another investigation or resume an existing run. Catalogue leases may be published
with new metadata identities after recovery; old invocation and mutation identities
are never replayed.

For an uncertain schedule deployment, open **Settings** and expand **Change
schedule frequency**. Enter the deployment administrator password and choose
**Check retained deployment**. A positive receipt
for the exact retained request, matching the active revision, updates the private
prepared schedule and archives the intent. No deployment is sent. If the receipt
is absent, another revision is active or prepared source has changed, the fence
remains and an operator must inspect the retained records. Follow the
[Application recovery manual](../../docs/projects.md#deploy-update-and-activate-an-application).

Credential rotation remains an administrator operation. Rotate the existing token
UUID and replace the private injected credential using the
[service account guide](README.md#rotation-and-interrupted-setup). Preserve token
identity, Application grants and journals. Stop/start the background service or
reopen the foreground launcher to load the replacement; the dashboard never
stores administrator credentials or silently creates another execution identity.

| Private record | Purpose |
| --- | --- |
| `operator-preferences.json` | Admission/notification choices and expected pattern identities |
| `manual-investigations.json` | Explicit investigation intents and positive receipts |
| `collector-service.json` | Exact owned OS definition path and digest |
| `deployment/schedule-update-intent.json` | Unsettled deployment identity and exact reviewed source |
| `deployment/schedule-update-receipt.json` | Last confirmed schedule release |
| `deployment/schedule-<UUID>-{intent,receipt}.json` | Archived confirmed deployment evidence |

Keep these records private and back them up with the existing collector journal.
Do not delete an uncertain intent to make a retry button work. History is bounded:
512 acknowledgements, 128 manual intents and the existing 1,000-scan journal fence.
Archive settled history deliberately; preserve identities for reconciliation.

An investigation that stops with **Invalid analyst assessment** or **Invalid
reviewed assessment** and reports raw model protocol markup has kept the
original model response in its trajectory. It has stopped before saving a
draft from that response. Inspect the retained delegate response before starting
another investigation from the same evidence.

If an investigation reports **Report save blocked**, its review is retained in
the run trajectory. After the write refusal is resolved, use Plowshare's
orchestration **Resume** as the original run owner. For an automatically
launched service-owned run, submit `orchestration.resume` through the public SDK
with that service identity and a new resume request ID. The server retries the
draft save using the retained review and source revisions. The original scan
and model calls stay in the run history. A save with an uncertain outcome
requires receipt reconciliation before another submission.

## Upgrade an existing collector

Stop the foreground process or user service, preserve the private directory, then
install the updated integration package into its existing Python environment:

```sh
build/privacy-python-env/bin/python -m pip install --no-deps ./integrations/network-privacy
```

Restart the service or open its launcher. Existing receipts with no admission
extension retain their exact completion text during reconciliation. New decisions
are flushed before completion publication and remain immutable across preference
changes or lost replies. Withheld completions carry empty actionable `changes`,
while the evidence and optional `observed_changes` retain every observation. This
also works with the previous Application completion route, which already skips
empty changes. Deploy the updated Application package through its normal reviewed
workflow when updating its route; no core server or SDK change is required here.

The application service token reads its project-scoped schedule runtime status.
A successful read shows the current pause state; a missing schedule is reported
separately from failed connection or permission checks. Operational pause/resume
uses a project manager's Plowshare CLI, TUI, desktop or web-console session;
it does not redeploy the Application. The dashboard's schedule revision editor
requires a deployment-administrator login because it changes Application source.
Tool visibility comes from a current unmeasured
context projection of an existing project conversation, not declared agent grants.
Before a project conversation exists, visibility remains unknown.

## Background device profiles

Under **Devices → Background device profiles**, enable research after enabling
quick presence checks and deploying the matching Application source and collector.
It starts off. The collector admits one profile research run at a time, with up
to four admissions per UTC day. A candidate needs a fresh MAC association and
at least three associated quick sightings. Model work is separate from network
investigations. It uses retained evidence and starts no new network scan.

The collector matches the observed MAC to the configured UniFi adopted inventory
when available. Full-sweep port clues are used only when a retained sweep
observed the same MAC at that address. If the controller confirms a public model,
or you enter one during review, the configured NVD lookup checks that model
automatically. Configure `PLOWSHARE_PRIVACY_NVD_ORIGIN` as described above.
Unknown models still get a draft and may ask you for identifying details.
The Plowshare profiler receives local name, vendor and port clues, the public
advisory results and your feedback. Its project Information tool is read-only.
NVD receives only the confirmed public model.

Use **Load drafts and activity** to review results and see retained Plowshare run
IDs. **Research with feedback** queues another bounded run for the same MAC.
**Use draft in profile editor** requires a fresh association and fills empty
fields. Review the device and choose **Save profile** explicitly to publish a
profile. **Mark reviewed** and **Dismiss** keep the decision and prevent automatic
repeat research for that MAC. Suspending research stops new starts and continues
receipt reads for admitted work. An uncertain start is reconciled by the original
request UUID, with later admissions held until it settles. The private journal
holds up to 256 device records.

## Device knowledge base

Open **Device knowledge base** or choose **Device profile** on a device card.
Load existing project profiles, or create one for selected monitored addresses.
Give it a name, responsible person, model, purpose, expected services/behaviour and
operator notes. These are operator-confirmed expectations, separate from observed
TCP/DNS evidence. A profile association records its confirmation time; a reused
address or randomized MAC does not prove that two observations concern one device.

When saving a reviewed device draft, its current MAC can be retained with the
profile. The profile editor offers **Confirm observed MAC for this profile**
for an existing address-only record. Saving a new binding requires a fresh
matching sighting. A bound profile follows that MAC when opened from a device
card. Address changes remain an operator edit, and an IP reused by a different
MAC does not select the old profile. Retained evidence links require the same
MAC in the recorded sweep. Older address-only profiles and their exact
Markdown remain readable.

Each profile has a stable Application-assigned UUID and a named Markdown resource
`network-privacy-device/<UUID>.md` in the Application's Plowshare project. The
Application service account owns the document. Project/document authorization also
covers its chapters and sections; there are no device-specific permission scopes.
The collector submits only scoped public Information SDK requests, with
`includeGlobal:false`. Existing source-processing allowances apply to admissions;
a saved revision is not proof that extraction or model processing has finished.

Edits create immutable revisions under the same resource. The editor checks that
its base revision is still the latest visible revision before saving. This is a
single-collector edit fence, not a server-wide compare-and-swap guarantee; reload
profiles after changes made by another writer. Old revisions remain readable and
listed in the editor. Profile Markdown is generated from its strictly validated
record metadata. Edit through the dashboard so text and metadata stay consistent;
external edits that disagree with the record are refused rather than guessed at.

Evidence buttons show matching retained scans among the latest 50 local receipts,
explicitly at the profile's currently associated addresses. Opening evidence also
shows reports whose retained inputs cite it. Older address associations remain in
older profile revisions; no automatic IP reassociation or scope expansion occurs.
The analyst/reviewer/coordinator already have Information read grants. Their
Application definitions now instruct them to find relevant current profile
revisions, distinguish expectations from observations and cite profile context
actually used. The server's existing input ledger controls report dependencies.

Profile documents and versions are owned by Plowshare, not by a new app database or
local Markdown directory. `device-profile-intents.json` stores private delivery
identities and exact submitted text only. If a write reply is lost, **Check retained
writes** looks for one matching project document revision, including the retained
write UUID and exact content. A missing, different or ambiguous revision keeps the
write fenced; no upload/revision is resent. The same request UUID cannot be reused
for changed content. **Check retained work** includes unsettled profile writes when
recovering a stopped collector.

Reads are bounded to 256 profile resources and 4,100 visible revisions. Private intents
are bounded to 128 entries and 1 MiB; archive settled receipts deliberately without
discarding uncertain identities. Document history remains in Plowshare. Reload
profiles explicitly when needed; the dashboard does not repeatedly read all profile
text on its five-second health poll. Never put passwords in device notes.

The separate scoped chapter-outline SDK limitation is recorded in
[GAPS.md](GAPS.md); it does not block profile creation, revision or reading.

## Privacy issues and network mitigations

Both device profiles and shared issues are **Markdown documents** in the
Application's Plowshare project, with readable headings and validated record
metadata. This feature adds no application document database, chapter permissions,
core API or SDK changes. Whole-document authorization covers every section.

1. Open **Privacy issues & mitigations**, then **Create privacy issue**. Record the
   issue, affected models, firmware versions (or explicitly unknown), relevant
   settings, one to four HTTPS source citations, their publication dates when
   known, and the date you checked them. These are operator-supplied sources: the
   collector does not fetch arbitrary URLs or certify their claims. Use the
   separate product lookup for NVD references. Prefer original vendor notices
   or primary research and recheck stale
   claims before applying a change. No real vendor allegations ship as seed data.
2. Include one proposed mitigation: a firewall rule, exact Pi-hole domain block,
   network segmentation or a device setting. Record why it addresses the issue,
   expected impact, verification and rollback. Save the shared issue document,
   then load it again after Plowshare processing completes.
3. Open a device profile and select **Link this issue to this device**. Confirm
   identity, model, firmware and settings before choosing potentially affected,
   confirmed affected, not affected, mitigated or unresolved. Attach up to four
   retained evidence revisions for that device, and explain the assessment.
   A shared issue can be linked to several devices without duplicating its text.
4. Review the proposed plan, then apply it through your router/firewall, Pi-hole
   or device interface. In the device profile record proposed, accepted, applied,
   verified or reverted progress. Applied/verified/reverted states require an
   explanation of the action/result; mitigated requires verified progress. These
   are **operator reports**, not automatic enforcement or independent verification.
   Saving records the action/result at the profile revision's confirmation time.
   Earlier decisions, notes, evidence and dates remain in immutable profile history.

Firewall plans specify **both endpoints**, direction, action, protocol and
**destination ports**. Use `linked_device` as the outbound source or inbound
destination; the peer is an exact canonical IP/CIDR or `internet`. Lateral rules
require a private peer. TCP/UDP require one to eight unique ports; all-protocol
rules have no port list. Confirm the profile's current physical identity and
address before translating a plan into a device-specific rule. A plan does not
expand the collector's monitoring scope. Broad internet blocks can disrupt
streaming, updates, casting or time synchronization; preserve required services
and record the functions you tested. Firewall policy depends on these traffic
attributes, rather than a port number alone; see
[NIST firewall policy guidance](https://www.nist.gov/publications/guidelines-firewalls-and-firewall-policy?pub_id=901083).

Pi-hole plans name one exact lowercase DNS domain, outbound blocking and no ports.
They are suggestions to configure in Pi-hole separately; this connector still
reads Pi-hole only. DNS blocking cannot establish that direct-IP or alternate-DNS
traffic is stopped. Listening-port scans establish service responses, not outbound
flow, payload content or rule effectiveness. Verification may need router/firewall
flow records plus device functionality tests; unavailable evidence remains a gap.
Segmentation and device-setting plans describe their boundary/setting without
pretending to be port rules.

Shared issues have stable names `network-privacy-issue/<UUID>.md`. Device profiles
pin the **exact reviewed issue revision**, keeping an accepted/applied plan stable
when an issue is edited. A profile shows when a newer revision is available.
**Review and use latest plan** displays that revision and resets its draft progress
to proposed/unresolved; save the profile after reviewing it. Existing status does
not automatically carry over to a different plan. New profiles use version 2;
version-1 profiles and pending write receipts remain readable without migration.

Agent handoffs/definitions instruct the analyst and reviewer to read the exact
linked issue revision, cite the profile/issue revisions they actually consumed,
distinguish reported issues from evidence concerning this device, and include
supported mitigation suggestions with impact, verification and rollback. Existing
Information grants and the server's retained input ledger remain authoritative.
No extra model stage, scan, tool grant or network mutation is introduced.

Limits are 32 shared issues, 400 visible issue revisions, four links per device,
16 KiB per issue Markdown and 8 KiB per device Markdown (UTF-8, including metadata).
Each issue has one plan; separate issues can represent separately reviewed plans.
Local `privacy-issue-intents.json` contains only private, project/collector-bound
write identities and exact submitted text. It is limited to 128 receipts and 1 MiB,
with capacity reserved for settlement before transport. Like profile writes,
unknown delivery blocks further issue writes; **Check retained writes** confirms
one exact retained revision or keeps the write fenced. It never resends a mutation.
**Check retained work** includes issue reconciliation. Reload issues explicitly;
the health poll does not repeatedly read every issue's text. Catalogue limits and
unavailable extraction are explicit failures, not empty successful results.

Issue/profile saves preflight the latest visible head within one collector. Existing
Information revise does not provide server-wide compare-and-swap: use one collector
as the writer and reload after external edits. Change applicability/progress only
after reviewing the exact pinned plan. To record an action performed earlier,
include its actual date in the assessment notes; the profile timestamp records when
the operator entered that report. The UI's newer-plan reset is draft review guidance;
it does not undo an already installed network rule.
