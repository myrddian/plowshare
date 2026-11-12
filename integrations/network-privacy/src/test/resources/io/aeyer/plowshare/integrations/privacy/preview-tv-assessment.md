# Living room TV: privacy assessment

Assessed 2026-11-12 08:00 AEDT. Reviewer verdict: accepted.

## In short

The TV looked up Samba TV's content-recognition service 96 times in a day. That is consistent with the TV identifying what is on screen and reporting it. It also looked up OzTAM, an audience-measurement company, 412 times, and an advertising service that Pi-hole blocked every time.

## What it is

A smart TV used for streaming and broadcast catch-up.

Confidence: medium. This rests on:

- The device name says it is a TV.
- It describes itself as a Sony BRAVIA over UPnP.
- Most named lookups are for ABC iview and Netflix.

## Who it talks to

- Samba TV (audience measurement): Looked up 96 times; 40 were blocked. Samba TV matches what is on screen against known programmes and adverts.
- OzTAM (audience measurement): Looked up 412 times; none were blocked. OzTAM measures Australian TV audiences for broadcasters.
- Google Ads (advertising): Looked up 58 times; all 58 were blocked. Apps on the TV request adverts.
- ABC iview and Netflix (streaming): Looked up 360 times. Ordinary streaming use.

## What it sends

Between 2026-11-11 08:40 AEDT and 2026-11-12 08:38 AEDT, the network counted 412 MB sent by this device and 18.4 GB sent to it.

Most of the volume went to the TV, which is consistent with streaming video.

Where the outgoing bytes went, and what they contained, cannot be told from this data.

DNS lookups show that a name was looked up, not that data was sent. Byte totals show volume only; they include local traffic and do not name a destination.

## What is concerning

### 1. Viewing recognised by Samba TV

Worth checking. Confidence: high.

- Fact: The TV looked up a Samba TV content-recognition name 96 times, and 56 of those lookups were answered.
- Inference: The TV's built-in viewing-data feature is probably on and reporting what is watched.

### 2. Viewing measurement by OzTAM

Worth checking. Confidence: medium.

- Fact: The TV looked up an OzTAM measurement name 412 times; none were blocked.
- Inference: A broadcaster app on the TV is probably reporting what is watched to OzTAM.

### 3. Advertising lookups

Expected behaviour. Confidence: high.

- Fact: All 58 lookups for Google's advertising service were blocked by Pi-hole.
- Inference: Apps on the TV request adverts; the block is working.

## What you can do

### 1. Viewing recognised by Samba TV

- Mitigation: Turn off viewing information or content recognition in the TV's privacy settings, then block samba.tv in Pi-hole.
- How to verify: Check that the Pi-hole query log shows no further answered Samba TV lookups after a day of normal use.

### 2. Viewing measurement by OzTAM

- Mitigation: Block oztam.com.au in Pi-hole. Some catch-up apps may refuse to play.
- How to verify: Check that the Pi-hole query log shows the OzTAM lookups as blocked.

### 3. Advertising lookups

- Mitigation: None needed; the existing Pi-hole block covers it.
- How to verify: Keep an eye on the blocked count staying equal to the total.

## Open questions

- Was viewing information turned on during the TV's first setup?
- Which catch-up apps are used on the TV?

## Supplemental information

The device describes itself as Sony BRAVIA KD-55X85K, firmware 6.6.0 (reported over the local network via UPnP, 2026-11-12).

It advertises over UPnP: screen casting (DIAL), media rendering (DLNA), remote control (Sony IRCC).

These are the device's own claims, not confirmed identity.

Public advisories for this model (NVD, checked 2026-11-12, model BRAVIA KD-55X85K from the device's own description): 2 found.

- CVE-2099-0002 (high, published 2099-01-02, known exploited): Synthetic preview advisory; not a real vulnerability.
- CVE-2099-0001 (medium, published 2099-01-01): Synthetic preview advisory; not a real vulnerability.

An advisory is about the model, not proof that this device is affected; check whether the firmware it names matches.

Worth checking: Samba Interactive TV (some BRAVIA TV models) — Sony describes it as third-party content recognition that uses viewing history for recommendations and adverts; Sony says it does not recognise content inside installed apps such as Netflix. To turn it off: System Preferences → Samba Interactive TV → Disable (older Android TV: Home → Settings → Preferences → Setup → Network → Samba Interactive TV). Menus may vary by model year. Source: Sony support article (https://www.sony.com.au/electronics/support/articles/00182856); Sony BRAVIA Help Guide (https://helpguide.sony.net/tv/gmxl4/v1/en/060nhm-06-01-06.html).

Vendor notes are features this manufacturer ships, not observations of this device.

## Evidence

- Device: `mac:02:00:00:00:00:0a` at 192.0.2.10
- Dossier revision: `aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa`
- Port scan revision: `eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee`
- Operator profile revision: not used; see Coverage
- Linked issue revisions: none
- Previous assessment revision: none
- DNS window: 2026-11-10T21:40:00Z to 2026-11-11T21:38:00Z, 288 windows, 3 partial
- Traffic window: 2026-11-10T21:40:00Z to 2026-11-11T21:38:00Z, 412 MB from the device and 18.4 GB to it
- Review: accepted; concerns 3 held, 0 weakened, 0 refuted and removed

Signals:

- `measurement_dns`: 508 lookups to measurement destinations (OzTAM, Samba TV); 40 blocked
- `advertising_dns`: 58 lookups to advertising destinations (Google Ads); 58 blocked

Coverage:

- The port scan covers only the configured TCP ports; other services are unknown.
- DNS profile partial in 3 of 288 windows; lookups in those windows may be missing.
- DNS lookups show names resolved, not data sent.
- Byte totals include local traffic and say nothing about destinations.
- 1 counter reset dropped intervals; byte totals are a lower bound.
- No operator profile supplied; expected behaviour is unknown.
- The manufacturer, model and firmware under supplemental are the device's own claims over the local network, not confirmed identity.
- Advisories match the model name, not this device's firmware or settings; a listed advisory may not apply to it.
- Vendor notes are features worth checking for this manufacturer, not observations of this device.

Automatic fixes to the assessor answer:

- None
