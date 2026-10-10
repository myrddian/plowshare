# Operating Network Privacy Watch

Start with the [guided installation](SETUP.md#start-and-use-the-dashboard). It
prepares a private Application, deploys it paused, provisions a separate service
account and opens the Python dashboard. The deployment administrator and human
manager must already exist; an existing model binding and managed FileStore are
server prerequisites. Python runs on the host that can reach your selected devices
and Pi-hole. Plowshare does not start or host this external process.

## Finish setup in the dashboard

1. **Find devices** on an explicitly selected private subnet. Select up to 32
   addresses and eight TCP ports, add names and save monitoring.
2. Optionally expand **Connect Pi-hole v6**. Supply its HTTP(S) origin and an
   application password. **Test connection** authenticates and reads device
   records without querying DNS or saving the password. Enter the password again
   for **Test and save connection**. A successful read can still report missing,
   stale or ambiguous device associations. Saving requires an idle collector,
   writes a new private password file and begins a new comparison baseline.
3. **Request a scan**. Read its observation time, scope, coverage gaps and evidence.
   A completed collection does not mean an investigation has completed.
4. Expand **Investigation and alert preferences** to choose automatic admission,
   a daily limit and cooldown. The defaults are eight automatic investigations
   per UTC day and a 60-minute cooldown. These count retained admission decisions,
   including baseline investigations; they do not limit model calls, tokens or
   monetary spend. Legacy changed completions count conservatively on upgrade.
5. Expand **Scan schedule**. Choose a listed frequency, an IANA time zone and
   whether to pause. Enter the configured deployment administrator's password
   to **Review schedule revision**. Inspect the full prepared Application source,
   active revision and source digest. Choose a managed FileStore from the dropdown,
   enter the password again, then **Deploy reviewed revision**.

Application schedules are immutable source definitions. Frequency/pause changes
therefore use a new Application deployment, with the reviewed revision as a
concurrency fence. The dashboard uses short-lived public login sessions and typed
SDK deployment operations. Administrator credentials are never saved or used by
collection. A newer Application deployed outside this setup is refused until an
operator reviews and synchronizes the private prepared source and its receipt.
The server may need a reconciliation pass before the new schedule is visible.

## Read health, devices and findings

**System health** shows collector state, last successful contact, last completed
scan, next scheduled scan, Pi-hole coverage, project agents, visible dynamic tools,
recent project investigations and service credential expiry. Remote checks and
report projections are cached for 20 seconds. Visibility is an inspection result;
permissions and availability are still enforced by Plowshare on each tool call.
A checklist highlights missing setup. The expiry notice warns within seven days.

Device cards show configured names, observed MAC/vendor associations, TCP service
responses, sampled DNS destinations and collection history. History is explicitly
at an address; a DHCP change or randomized MAC does not prove physical identity.
**Check moved devices** compares fresh Pi-hole associations with the last local
LAN discovery, limited to 32 responding addresses. Confirm each suggested move
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
manually duplicated through this control.

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
or project/provider grants first, then choose **Check and resume**. For a stopped
worker this creates a fresh public SDK connection, keeps the existing provider
journal and checks exact retained evidence, publications, tool results and explicit
investigation receipts before allowing intake again. Missing receipts keep the
work fenced. Catalogue leases may be published with new metadata identities after
recovery; old invocation and mutation identities are never replayed.

For an uncertain schedule deployment, expand **Scan schedule**, enter the deployment
administrator password and choose **Check retained deployment**. A positive receipt
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

## Device knowledge base

Open **Device knowledge base** or choose **Device profile** on a device card.
Load existing project profiles, or create one for selected monitored addresses.
Give it a name, responsible person, model, purpose, expected services/behaviour and
operator notes. These are operator-confirmed expectations, separate from observed
TCP/DNS evidence. A profile association records its confirmation time; a reused
address or randomized MAC does not prove that two observations concern one device.

Each profile has a stable Application-assigned UUID and a named Markdown resource
`network-privacy-device/<UUID>.md` in the Application's Plowshare project. The
Application service account owns the document. Project/document authorization also
covers its chapters and sections; there are no device-specific permission scopes.
The collector submits only scoped public Information SDK requests, with
`includeShared:false`. Existing source-processing allowances apply to admissions;
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
for changed content. **Check and resume** includes unsettled profile writes when
recovering a stopped collector.

Reads are bounded to 32 profile resources and 400 visible revisions. Private intents
are bounded to 128 entries and 1 MiB; archive settled receipts deliberately without
discarding uncertain identities. Document history remains in Plowshare. Reload
profiles explicitly when needed; the dashboard does not repeatedly read all profile
text on its five-second health poll. Never put passwords in device notes.

The separate scoped chapter-outline SDK limitation is recorded in
[GAPS.md](GAPS.md); it does not block profile creation, revision or reading.
