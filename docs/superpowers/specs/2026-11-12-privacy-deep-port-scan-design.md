# Network Privacy Watch: deep port scan of a flagged device

Status: agreed with the project owner on 12 Nov 2026; ready for planning.

## Why

The collector's regular collection checks a short, fixed port list (PROD: 8
ports across 254 addresses). A device can be listening on many other ports,
such as a cast service, a debug shell or a vendor control port, and none of
those show up. When an assessment already says a device is worrying, the
operator should learn everything that device is listening on, without having
to ask.

Only the household's administrator sees this. It is a useful capability, not
a stage piece for an audience.

## What it does

When an assessment for a device settles with at least one concern at the level
**potential breach** or **worth checking**, the collector automatically runs a
**deep port scan** of that one device. This is a connection-only probe of every
TCP port, 1–65535.

The result is shown in the device pane. New open ports appear in "Needs your
attention", and the next assessment of that device includes them.

## Goals

- **Trigger:** the scan starts automatically from the collector's own
  assessment ledger. It needs no Relay round trip, agent or operator click.
  - It triggers once per assessment report revision: a new report for the
    device can trigger again, but a refresh cannot.
  - "Settles" means the report has reached review. The scan does not wait for
    Accept; whether Accept or Dismiss changes this is for the builder to decide
    and document.
- **One device at a time:**
  - At most one deep scan runs at once, whether automatic or requested by the
    operator; others queue in order.
- **Automatic scans have a configurable daily limit.** Settings has "Automatic
  deep scans per day", a whole number from 0 to 12.
  - 0 turns automatic scans off, and is the default.
  - The day is the household day of the configured `timeZone`, the same as the
    other daily limits.
  - This replaces a separate on/off switch, as the background assessment limit
    does.
- **Scans the operator requests have no daily limit.** They still run one at a
  time.
- **Cancellable** from the device pane.
- **Progress** in the device pane: ports checked of 65535, open ports found so
  far, and roughly when it will finish.
- **Retained and fed forward:**
  - Each completed scan is kept, with its open ports, when it ran, how long it
    took and how many ports did not answer.
  - The device's next assessment dossier includes it.
  - Open ports the regular collection never saw become an attention item.
- **One setting:** the automatic limit above. It is 0 (off) by default, and
  stays 0 on PROD until the owner raises it (see Constraints).
- **Request shape ready for later:** the scan request is a typed request
  (device, reason, request id). A later change can then accept the same
  request from a Relay topic or an agent tool without redesigning it. That
  transport is not part of this work.

## Non-goals

- UDP, banner grabbing, protocol identification, OS fingerprinting, or any
  data sent after the connection is made.
- Deep-scanning a subnet or more than one device per scan.
- Automatically re-running the assessment after a scan, which costs model
  calls. The pane offers "Assess again" instead.
- Relay or swarm integration. A later analyst-swarm feature may post scan
  requests; see the request shape above.

## Anti-goals (a failure even if it works)

- **Probing an address that no longer belongs to the flagged device.** Before
  and after the scan, the address must still map to the device's confirmed MAC
  address. Otherwise the scan stops and its result is marked unreliable, never
  credited to the device.
- **Probing anything outside the private network.** The address must pass the
  same private-IPv4 rule the dashboard uses.
- **Delaying or blocking** scheduled collection, quick checks or the dashboard.
- **Resending or duplicating** a scan after a restart without the operator
  knowing. A scan interrupted by a restart is recorded as interrupted, not
  silently resumed or silently repeated.
- **Presenting an open port as a finding.** It is an observation; the existing
  exposure wording ("possible service … check …") applies.

## Constraints

- **Integration boundary** (AGENTS.md, docs/decisions/0001):
  - Collector code and, if needed, Application files only.
  - No change to plowshare-server, protocol, SDKs or shared contracts.
- **Probing method:**
  - Probes are plain TCP connects, as `collection._quick_connect` and
    `discovery` do today.
  - No raw sockets or elevated privileges.
  - Timeouts, refusals and unreachable results keep their existing meanings: a
    timeout is a coverage gap, not "closed".
- **Bounded cost:** the worst case for a fully filtered device must stay under
  roughly 20 minutes. Timeout and concurrency are bounded constants, or
  validated configuration with bounds; the builder chooses which. The builder
  also chooses the defaults within that bound.
- **Persistence:** results live in a private, bounded collector journal, like
  `device-assessments.json` and the device-facts ledger. They use the same
  private-file rules (no symlinks, mode 0600, size bound) and keep only a
  small number of scans per device.
- **Dossier:**
  - Prefer carrying the deep scan in the existing dossier shape. Examples are
    a coverage line, and exposures derived from the deep-scan open ports.
  - If a structural field is genuinely needed, bump the dossier envelope to
    version 4 and update `assess_network_device.js` in the same change. The
    script must keep accepting versions 1–3.
  - The deploy then needs the script installed first, as for version 3.
- **Before the PROD limit is raised above 0:** the owner checks that UniFi's threat
  detection (IDS/IPS) will not block or quarantine the collector's address
  during a full sweep. This is an operational step, not code, and the Settings
  hint says so.
- **Tests and checks:** mocked or in-memory tests, not real database tests.
  Python unittest, the web tests, mypy, ruff, and the Gradle formatCheck and
  Java tests if the script changes.

## Decided by the owner

- The scan triggers automatically when an assessment flags a device; no click
  is needed.
- No Relay for this. The collector triggers it directly, because a Relay round
  trip adds nothing today.
- It is advisory: it never changes the network.

- **Raising the limit above 0 starts no backlog.** Only reports that reach
  review afterwards trigger a scan. Devices flagged earlier are not scanned
  automatically. The operator can request a deep scan of any device at any time
  from its pane, including one flagged before, and re-request one after an
  earlier scan. Those requests are not limited per day.

## Calls made in the design (check these)

- The automatic limit defaults to 0 (off), including on new installs.
- The automatic limit ranges from 0 to 12 a day.
- "Flagged" means at least one non-refuted concern at potential breach or worth
  checking. An expected-only report does not trigger a scan.
- The scan does not re-assess automatically; the pane offers "Assess again".
- A "Deep-scan this device" button in the pane runs one by hand, even with
  automatic scans off and without a daily limit. It's cheap, and useful for
  checking a device the assessment didn't flag.

## Rules settled after review

- **The "new open ports" attention item.**
  - "New" means open in the latest deep scan but not on the collector's
    configured port list. The regular collection never probes those ports, so
    only the deep scan can show them.
  - The item clears when the device's next assessment is admitted with that
    deep scan in its dossier, which is what "Assess again" does.
  - It also clears when a later deep scan finds none of them open.
  - There is no separate acknowledge action.
- **Triggers that cannot run straight away** are kept, not dropped. They live in
  a small, bounded queue in the journal: one pending trigger per device, the
  newest report revision wins, oldest first.
  - **Automatic limit used up:** the trigger waits for the next household day.
  - **Limit set to 0:** queued automatic triggers are discarded, and the pane
    says automatic scans are off. Queued operator requests stay.
  - **Restart:** queued triggers survive a restart, because nothing was sent,
    so nothing can be resent. A scan that was running is recorded as
    interrupted and is not resumed.
  - **Address no longer the device's** (checked just before the scan starts):
    the trigger is dropped and recorded "address changed; not scanned". The
    pane's button can run it later.
  - A queued trigger expires after 7 days.
- **Proof the address is still the device's.**
  - Just before the scan and just after it, the collector makes a fresh
    identity read from its configured identity sources, the same ones
    collection uses.
  - The address must map to the device's confirmed MAC both times. If any
    source disagrees, or none answers, the result is unreliable. Before the
    scan, that means the scan does not run.
  - A collector with no identity source configured (no UniFi or Pi-hole)
    cannot deep-scan. The Settings field says why.
  - The reads must not hold the worker's collection locks.

## Left to the builder

- Module layout, names, journal format and the dashboard endpoints.
- How cancellation is delivered, and the exact progress estimate.
- Probe timeout and concurrency, within the 20-minute bound.
- How deep-scan results appear in the dossier, within the dossier constraint
  above.
- The exact wording of pane, attention and Settings text. It follows
  `docs/console-design.md` ("Round 3: one pattern", copy rules: counts through
  `plural()`/`formatCount()`, never "sweep").
