# Supplemental information and automatic assessment

Status: built. Device facts (`device_facts.py`, `device_describer.py`), the
dossier's `supplemental` section, the report section, the dashboard block, the
Settings option, vendor notes and the facts-changed requeue are in place; see
OPERATIONS.md ("Device descriptions", "Device privacy assessments").

Deviations from this proposal:

- The script does not yet check agent text against supplemental facts. Agents
  are told the facts are context, the reviewer weakens or refutes concerns
  that restate them as observations, and the section itself is code-written.
- Vendor notes cannot tell a TV from a streaming player (detection groups
  them), so each note names the products it applies to instead.
- Amazon Fire TV is not in the vendor table: its only source was on an
  unexpected Amazon host. LG Live Plus is included, attributed to reporting.
- The dossier document moved to version 2. Version 1 revisions are still read
  and are revised on the next retention.
- The proposed daily limit of 4 is not a default; the background limit stays
  0 (off) until you set it.

## Goal

Privacy Watch should work as a network sentinel. It notices a device, learns
what it can about that device, and assesses it without being asked. A person
can still point at a device and say "look at this one now".

Today an assessment sees only what the network shows: DNS lookups, traffic
volume, open ports and Pi-hole silence. This proposal adds what can be learned
about the device itself. That material goes into a report section called
**Supplemental information**, written by code from collected facts, with every
fact naming its source.

## What you would see

In the device pane, a new block called **What we know about this device**:

- **Reported by the device**: manufacturer, model, firmware version and the
  services it advertises, for example "screen casting" or "remote control".
- **Public security advisories** for that model: CVE ID, severity, date, and
  whether it is known to be exploited.
- **Your profile**: the purpose and expected services you confirmed.
- When each fact was collected, and whether anything changed since the last
  assessment.

In the assessment report, a new section between "Open questions" and
"Evidence":

```
## Supplemental information

The device describes itself as a Sony BRAVIA KD-55X85K running firmware
6.6.0 (reported over the local network, 12 Nov 2026 07:10).

It advertises: screen casting (DIAL), media rendering (DLNA), remote control.

Public advisories for this model (NVD, checked 12 Nov 2026): none found.

Sources: device description (UPnP), service list (mDNS), NVD.
```

The model and firmware in the example are illustrative.

The agents can read these facts and use them in their reasoning. For example,
"the TV advertises remote control, so the open port is expected". The section
itself is rendered by code, so an agent can neither invent a fact for it nor
reword one.

## Where the information comes from

### 1. What the device says about itself (new: the collector asks the device)

Many TVs, speakers, streamers and printers publish a self-description on the
local network. The collector would ask each monitored device directly:

- **UPnP / SSDP.** Send one search packet to the device's own address on UDP
  port 1900. If it answers with a description location *on that same address*,
  fetch that XML file and keep only these fields: manufacturer, model name,
  model number, firmware or software version, and service types.
- **mDNS / Bonjour.** Send a direct query to the device's address on UDP port
  5353 asking which services it advertises. Read the model fields that
  AirPlay, Google Cast and printer records commonly carry.

Safety and privacy rules:

- **Only monitored devices.** Ask only devices with a confirmed MAC address
  that are inside the configured watch list. Never broadcast, never scan the
  subnet, never contact anything outside the private network.
- **No redirects.** If the reply points the description fetch anywhere other
  than the device's own address, refuse it. That prevents the device from
  steering the collector at another host.
- **Bounded fetches.** At most 64 KB, a 2-second timeout and no redirects.
  Reject the XML if it declares a document type or entities, which closes off
  the known XML expansion attacks.
- **Identifiers are discarded.** Serial numbers, UUIDs, MAC addresses and
  friendly names are dropped before anything is stored. Your living-room TV's
  name does not need to be in a report.
- **Rate limits.** Ask each device at most once a day. Ask again sooner only
  when a new MAC address is seen at its IP address. The collector paces its
  requests so that a full sweep sends only a few packets per second.

A device that doesn't answer is normal. Many IoT devices don't publish
anything. The report then says "the device did not describe itself"; that is
not a finding.

### 2. Public advisories (existing code, wider use)

The profile researcher can already look up a confirmed model in the US
National Vulnerability Database (NVD), and the NVD lookup code already exists
(`advisories.py`). Only the public model string is ever sent, never an address,
MAC or name. Under this proposal:

- **Which model is looked up.** The first available of: your profile, a model
  the UniFi controller confirms, or the model the device reports. Each is
  labelled with where it came from.
- **How often.** Each model is checked at most once a week. The results are
  cached privately.
- **Change tracking.** A newly published advisory counts as a change. See
  "Running automatically".

### 3. Your profile (existing)

The device's confirmed purpose and expected services are already in the
dossier. They are shown in the section as your words, separate from what the
device reported.

### 4. Optional: known tracking features by manufacturer (a curated list)

This was prompted by the GN report on LG TVs. Some vendors ship
viewing-recognition features: LG LivePlus, Samsung Viewing Information
Services, Vizio Viewing Data, and Sony/Samba TV interactive features. A small
list maintained in code would map each manufacturer and device type to:

- the feature's name,
- what it collects, in one sentence,
- where to turn it off,
- the vendor's or a reputable source's URL.

The report would add, for example, "Sony TVs can include Samba TV content
recognition; see Settings → … to turn it off (source)". It would be phrased as
something to check, never as an observation. Like the DNS classification
table, an entry is added only with a published source.

## Running automatically

The pieces mostly exist; this proposal connects them.

1. **Notice.** Quick presence checks already find devices every 2-5 minutes.
2. **Learn.** A new "device facts" step runs after quick checks. It asks
   eligible devices for their self-description (source 1) and refreshes
   advisories (source 2), within the rate limits above.
3. **Assess.** The existing background assessment queue already handles
   devices with attention signals: first those never assessed, then those
   whose signals changed. It would also requeue a device when:
   - its supplemental facts change, such as new firmware, a different model or
     a new advisory; or
   - a never-assessed device gains its first facts, even with no signals.
4. **Limits.** The existing daily limit still applies, along with the rule
   that only one assessment runs at a time. A device whose report is waiting
   for review is not reassessed. I suggest 4 a day to start; it is a dashboard
   setting.

The collector never accepts or finalises a report on its own. Reports wait for
you with **Accept and make final**.

## How it fits the code

- New `device_facts.py`:
  - typed `DeviceFacts` records: what the device reported, which model was
    used for advisories, the advisory page, and the source and time of each;
  - a bounded private journal, `device-facts.json`, of at most 256 devices;
  - the SSDP/mDNS readers behind a narrow `DeviceDescriber` interface, with
    tests using fake responders.
- `device_dossier.py` gains a `supplemental` field. Facts count towards the
  dossier's byte budget, and advisories are trimmed first, keeping the most
  severe.
- `assess_network_device.js`:
  - renders `## Supplemental information` from the dossier with code;
  - the validator rejects agent text that cites a supplemental fact the
    dossier doesn't contain.
- The dashboard pane gets the "What we know about this device" block.
- `device_assessments.py` adds the facts-changed requeue rule.
- No core server or SDK changes. Deployment is the usual collector release
  plus the in-place Application file update recorded in the PROD runbook.

## Questions for you

1. **Active queries.** Is it OK for the collector to send these small direct
   queries to monitored devices (UDP 1900 and 5353, once a day each)? This is
   the one change from listening to asking.
2. **Curated vendor list.** Should source 4 be included in this first version?
3. **Daily limit.** Is 4 automatic assessments a day the right starting point?
   Each one uses a few model calls on PROD.
