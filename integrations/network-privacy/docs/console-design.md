# Network Privacy Watch console design

This is the design plan for the web console in
`src/plowshare_privacy/web/` (`index.html`, `style.css`, `app.js`). Follow it
when changing the console. It records decisions and the reasons for them, so a
later change can tell a rule from an accident.

## Brief in one paragraph

One technically capable homeowner runs this console to answer four questions:
what is on my network, who is each device talking to, how much is each device
sending, and which devices cannot be inspected. The client asked for a slick
retro-futurist look in the manner of Deus Ex: Human Revolution, with less
generated-UI filler, and insisted that the information stays correct. The
console shows observations, their source and their limits. It never shows a
score, rating or threat level, because the data contains none.

## Palette

Gold linework on warm black is the identity. Two signal colours each have one
meaning and are never used as decoration.

| Name | Hex | Role |
| --- | --- | --- |
| Obsidian | `#0b0a08` | Page ground |
| Bitumen | `#15120d` | Framed surfaces (panels, the triptych leaves) |
| Gilt | `#d9a441` | Linework, primary actions, and advertising, measurement and telemetry marks |
| Parchment | `#ece3cf` | Body text and data values |
| Sightless cyan | `#6fd6d2` | Only "No DNS via Pi-hole": something the monitor cannot see |
| Cinnabar | `#ff8a75` | Only errors and work that needs attention |

Derived tones, not new colours: Tallow `#a89a7d` (secondary text, parchment
dimmed), Umber `#4a3a1c` (hairlines), Brass `#8a6b2b` (form-control borders,
3:1 against the ground), Soot `#1d1810` (hover and open rows).

Contrast on Obsidian: Parchment 15:1, Gilt 8.7:1, Tallow 7:1, cyan 11:1,
Cinnabar 8:1. Primary buttons are Obsidian text on Gilt (8.7:1).

## Type roles

Only system fonts; the Content-Security-Policy forbids anything else.

| Role | Stack | Used for |
| --- | --- | --- |
| Display | `Didot, "Bodoni 72", "Bodoni MT", Georgia, serif` | The page title, the connect title and the wordmark, never below 26px. This is the Renaissance voice. |
| Structure | `"Avenir Next Condensed", "DIN Alternate", "Arial Narrow", sans-serif` | Panel headings, tabs, buttons, table headers, tags. This is the instrument voice. |
| Body | `"Avenir Next", Avenir, "Segoe UI", system-ui, sans-serif` | Sentences, caveats, forms |
| Data | `ui-monospace, "SF Mono", Menlo, Consolas, monospace` with tabular figures | Addresses, MACs, byte counts, query counts, domains, revision ids. Numbers are right-aligned in columns. Nothing else is monospace. |

## Layout

Overview: one altarpiece answers the four questions. Everything else on the
page is quiet.

```
 project · collector · [mode]                      Refresh  Request a scan  Disconnect
 Overview   Devices   Findings   Evidence   Settings
 ─────────────▼──────────────────────────────────────────────────────────────
 Network overview
                      ╱‾‾‾‾‾‾‾‾‾‾‾‾‾‾╲╱‾‾‾‾‾‾‾‾‾‾‾‾‾‾╲
  ╱‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾╲ │ Advertising, measurement    │ ╱‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾╲
  │ Out of Pi-hole's │ │ and telemetry               │ │ Data volume      │
  │ sight            │ │ Living room TV          566 │ │ Storage server   │
  │ Driveway cam 24h │ │ ▓▓▓▓▓▓▓▒▒                   │ │ ████████ 44.1 GB │
  │ Nursery mon. 24h │ │ Measurement 508 · Ads 58    │ │ Driveway camera  │
  │ △ visibility gap │ │ △ sampled lookups, not      │ │ △ lower bounds,  │
  │   not proof      │ │   connections               │ │   incl. local    │
  ╲_________________╱ ╲_____________________________╱ ╲_________________╱
  ╲ On the network   16 devices observed · 160 candidate addresses · types  ╱
 ─────────────────────────────────────────────────────────────────────────────
 Collection | Changes in latest collection | Reports     Monitoring schedule
 Recent investigations                                    System health
```

Devices: a ledger on the left and one side pane on the right. Selecting a
device opens the pane; the page never scrolls to a distant form. Candidates are
a separate mode of the same list and render as a compact address grid.

```
 Find a device [________] Show [Observed ▾] Order [▾] │ Living room TV      [Close]
 [All 16] [Ad, measurement, telemetry 4] [No DNS 3]   │ What it is
 Device           Address     Signals   Lookups  …    │ What it talks to (behavioural first)
 Gateway          192.0.2.1   None      No profile    │ How much it sends
▌Living room TV   192.0.2.10  1 reset   ▓▓▒ 566       │ Open ports and exposures
 Driveway camera  192.0.2.20  ◇ No DNS via Pi-hole    │ Your notes and expectations
                                                      │   saved profile · Edit profile · issues
                                                      │ Agent assessment (honest empty state)
                                                      │ Product and advisories
                                                      │ (editors open here, in place)
                                                      │ Evidence history
```

The pane is fixed to the right edge and the page content narrows beside it, so
it is always next to what was clicked. It also hosts the profile editor, the
issue editor and reader, the agent draft review and the product lookup: one
element each, shown in place. The open view and selected device are kept in the
URL fragment (`#tab=devices&device=<address>`), so reload and Back restore them;
Back from a collection opened from a device returns to that device. Escape and Close
return focus to the row.

At 375px the triptych stacks (centre leaf first), the device ledger becomes a
two-line row with inline labels, the side pane and the report and evidence
readers become full-screen sheets with a Close button, and work panes stack.

Findings and Evidence keep a list-and-reader split. Settings is a single column
of framed disclosure panels.

## Principles

1. **Observation, source, limit.** Every number sits with where it came from
   and what it cannot show. A caveat is marked with a small triangle and is
   placed next to the figure it limits; it is never removed to tidy a layout.
   "Not measured" is written differently from zero ("No DNS profile" versus
   `0`).
2. **One bold object.** The Overview altarpiece (three leaves and a base strip)
   is the only ornate element and the only place with motion: the wings open
   once when the dashboard first appears. Nothing else animates, and
   `prefers-reduced-motion` removes it.
3. **Angles are structure.** A cut corner means "this is a framed container or
   a control". A filled triangle means "a limit of the evidence" or "this
   expands". A hollow diamond marks the cyan visibility gap. No shape is
   sprinkled for atmosphere; there are no scanlines, glows or glitch effects.
4. **Colour agrees with words, never replaces them.** The three behavioural
   destination groups share Gilt and differ by fill pattern (solid, hatched,
   dotted) and always carry a text breakdown. Cyan always appears with the
   words "No DNS via Pi-hole". Errors say they are errors.
5. **Dense where the data is tabular, plain where it is prose.** Ledger rows,
   hairlines and aligned monospace figures for devices and destinations;
   ordinary sentences in the body face for explanations and forms.

## Revisions made to the first idea

The first sketch was checked against the brief and these parts were changed
because they were defaults rather than decisions.

- **Four headline stat tiles became the altarpiece.** A row of big numbers
  (devices, changes, reports) answered none of the four questions and is the
  stock dashboard opening. The counts that remain are a small ruled register
  below the altarpiece.
- **Seven category colours became one colour with three patterns.** A hue per
  destination group would have been neon-cyberpunk decoration and failed the
  "colour is not the only carrier" rule. Only the three behavioural groups are
  marked; the other four are plain text.
- **A red/amber/green "risk" tag per device was removed.** The data has no
  risk rating. Rows show observed signals in words instead.
- **Monospace everywhere and upper-case tracked labels were dropped.** Mono is
  reserved for tabular values; headings are sentence case in the condensed
  face.
- **Questions as headings were reworded.** "Which devices hide?" presumes
  intent; the leaf is titled "Out of Pi-hole's sight" and its caveat says it is
  a visibility gap, not proof of misbehaviour.
- **Per-section entrance animation was reduced to one moment.**
- **Glow on gold lines was removed.** Lines are flat 1px rules.

## Implementation notes

- Cut corners use `clip-path` on an element whose background is the line
  colour, with a `::before` layer inset by 1px carrying the surface colour.
  Because `clip-path` clips outlines, chamfered buttons drop the clip while
  focused and show a rectangular Parchment outline.
- The server's CSP forbids inline styles in markup. Bar widths are set from
  JavaScript through `element.style`, which the policy allows.
- `renderDnsActivity` and `renderTraffic` are exercised by
  `tests/web_settings.test.cjs` in a minimal fake DOM. Keep them to
  `element(...)`, `append` and property assignment, and guard `style`.
- The device list is rebuilt only when the device data changes; an open device
  view is re-rendered from the new data, and its profile, draft and issue
  reads happen once per open and after a save, not on the five-second refresh.
- The ledger uses a container query, not a viewport query, because the side
  pane narrows it.
- Report tables scroll inside `.markdown-table`; cells never break mid-word.

## Round 2 decisions

- **Notes are not analysis.** The device profile is the operator's own record,
  so the pane titles it "Your notes and expectations". "Agent assessment" is a
  separate block that says plainly when there is none; a background research
  draft appears there, labelled as a draft. Never fill it with derived content.
- **Record metadata is folded.** `collapseRecordMetadata` in `app.js` moves the
  profile's trailing JSON into a closed `<details>` after rendering.
- **Direction words match everywhere.** Leaves, ledger and pane all say "from
  device" and "to device". Volume bars are a dim gold fill with a gilt outline.
- **Signals are behavioural only.** The Signals column holds "No DNS via
  Pi-hole". A counter reset is a data-quality note: a limit triangle on the
  byte totals with the text "lower bound; N counter resets". "Address only"
  sits with the device type under the name.
- **Unlabelled figures are not allowed.** The quiet leaf says "quiet 23 h".
- **The DNS caveat** now reads "Counts are sampled DNS lookups, not connections
  or bytes. Traffic volume is under How much it sends; which host received
  those bytes is not observed." Readout summaries use the structure face.
- **Compact ledger row** (container under 860px, so beside the pane): name,
  type, signal and address on line one; lookups and bytes on line two. Under
  520px the signal tag takes its own line.
- **Phone filters.** At 600px and below, Show, Order and the signal chips fold
  behind "Filter and order", which reads "in use" when anything is narrowed.
