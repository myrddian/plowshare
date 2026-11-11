# Lounge TV: privacy assessment

Assessed 2026-11-11. Reviewer verdict: accepted.

## In short

The TV looked up OzTAM, an audience-measurement company, 14 times in eight hours. That is consistent with an app on the TV reporting what is watched. Nothing else stands out.

## What it is

A Sony smart TV used for streaming.

Confidence: medium. This rests on:

- The device name suggests a TV.
- The network card was made by Sony.
- Most lookups are for Netflix and Sony services.

## Who it talks to

- OzTAM (audience measurement): Looked up 14 times; none were blocked. OzTAM measures Australian TV audiences for broadcasters.
- Netflix (streaming): Looked up 120 times. Ordinary streaming use.

## What it sends

Between 2026-01-01 00:00 and 2026-01-01 08:00 UTC, the network counted 310 MB sent by this device and 4.2 GB sent to it.

Most of the volume went to the TV, which is consistent with streaming video.

Where the outgoing bytes went, and what they contained, cannot be told from this data.

DNS lookups show that a name was looked up, not that data was sent. Byte totals show volume only; they include local traffic and do not name a destination.

## What is concerning

### 1. Viewing measurement by OzTAM

Worth checking. Confidence: medium.

- Fact: The TV looked up an OzTAM measurement name 14 times.
- Inference: A broadcaster app on the TV is probably reporting viewing to OzTAM.

## What you can do

### 1. Viewing measurement by OzTAM

- Mitigation: Block oztam.com.au in Pi-hole. Some catch-up apps may refuse to play.
- How to verify: Check that the Pi-hole query log shows the OzTAM lookups as blocked.

## Open questions

- Which apps on the TV are used for broadcast catch-up?

## Evidence

- Device: `mac:02:00:00:00:00:01` at 10.1.2.10
- Dossier revision: `aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa`
- Port scan revision: `11111111-1111-4111-8111-111111111111`
- Operator profile revision: `66666666-6666-4666-8666-666666666666`
- Linked issue revisions: `88888888-8888-4888-8888-888888888888`
- Previous assessment revision: `bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb`
- DNS window: 2026-01-01T00:00:00Z to 2026-01-01T08:00:00Z, 96 windows, 0 partial
- Traffic window: 2026-01-01T00:00:00Z to 2026-01-01T08:00:00Z, 310 MB from the device and 4.2 GB to it
- Review: accepted; concerns 1 held, 0 weakened, 0 refuted and removed

Signals:

- `measurement_dns`: 14 lookups to measurement destinations (OzTAM); 0 blocked

Coverage:

- The port scan covers only the configured TCP ports; other services are unknown.
- DNS lookups show names resolved, not data sent.
- Byte totals include local traffic and say nothing about destinations.

Automatic fixes to the assessor answer:

- None
