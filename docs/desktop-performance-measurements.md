# Desktop performance: what was measured, and what it says

Measurements taken 22 October 2026 against deployed server `8467c821`
(`http://192.168.1.16:8091`) with a real ten-document corpus, and against the
local protocol fixture for client-side timings.

## Correction, 23 October 2026 — read this first

**The cause was found, by someone else, and these measurements were built to
miss it.** The numbers below are sound. The inference drawn from them — "the
server is not the bottleneck" — was not.

`docs/specs/2026-10-23-dispatch-capacity-design.md` opens on this exact
symptom: the desktop reporting bursts of `RESPONSE_LOST`, *"confirmed as a
server-side stall, not a client defect. During the fault the desktop held six
ESTABLISHED sockets to the server and the server's HTTP layer answered in 3ms.
Frames arrive, the server holds them, and it says nothing."* Its summary of the
defect is that *"the dispatch contract cannot express congestion at all, so
congestion is delivered as silence."*

The mechanism, from that design:

| | per connection | global |
|---|---|---|
| queue depth (may be outstanding) | 64 | 1024 |
| concurrency (actually executing) | 2 | 8 |

Requests are admitted freely up to the depth bound and then queue behind the
execution bound, and **queue time counts against the client's deadline,
because the client's timer starts at submission rather than at execution.**
A multi-window desktop holding six sockets can have hundreds of requests
admitted against a global execution concurrency of eight, with nothing
signalling congestion. Separately, the server *"kept exhausting its HikariCP
pool"* (`95008be3`), where a pool-acquire timeout was indistinguishable from
the database being unreachable.

**Why this was measured and still missed.** Every probe here used **one**
connection against an **idle** server — precisely the configuration in which
queueing cannot occur. A fast server and a stalled desktop are not
contradictory; they are the same picture, and the design doc's 3 ms HTTP
during the fault agrees with the 5–7 ms p50 below. The p90 of 30–43 ms
against that p50, on an idle server with a single client, was the faint edge
of the queue; it is reported below and was not interpreted.

The lesson is specific, and worth more than the numbers: **a latency probe
that holds one connection cannot speak about contention, and must not be
read as doing so.** Load, connection count and queue depth are the
independent variables for this symptom.

**Deployment gap.** The 32 commits fixing this are on `main` at `0785a3f0`
and are **not deployed** — production runs `8467c821`. So every measurement
here was taken against a server that still has the depth-64 queues, the
unbounded pool and no backpressure. Re-measure after deploying, under real
multi-window load, before trusting any of it.

---

The original headline follows, kept because the correction above is only
legible against it.

**I could not reproduce the reported sluggishness.** Every path measured is
fast. The server answers in milliseconds, the renderer opens a window in
under 100 ms, and opening a document costs two frames. The bundle waste is
real and now halved, but fixing it changed window-open time by nothing
measurable.

## Server-side latency, real corpus

Ten documents, 35–72 KB each, personal scope. One connection, reused, so
these are per-operation times and not connect cost.

| operation | reply | min | p50 | p90 |
|---|---|---|---|---|
| `information.navigation.capabilities` | 116 B | 3.9 | 4.6 | 12.4 |
| `information.documents.facets` | 581 B | 5.1 | 5.6 | 42.7 |
| `information.documents.list` (50) | 2.8 KB | 5.3 | 7.1 | 42.7 |
| `information.list` | 8.4 KB | 5.2 | 5.9 | 35.4 |
| `information.documents.get` | ~300 B | 9.6 | 10.4 | 15.4 |
| `document.list` | 3.2 KB | — | 24.7 | — |

All milliseconds. `connect + authenticate` is **~97 ms**, paid once per
connection — and once per CLI invocation, which is why the CLI feels
different from a long-lived client.

`document.detail`, which the Documents view renders, by document size:

| document | source | chapters/sections/paragraphs/chunks | reply | p50 |
|---|---|---|---|---|
| Authored knowledge design | 72 KB | 1/1/172/175 | 493 B | 27.0 |
| Authority substrate impact report | 60 KB | 1/6/104/113 | 1.4 KB | 26.3 |
| Standalone allowances and recovery | 54 KB | 1/14/82/94 | 2.7 KB | **89.5** |
| Role and scope permissions | 53 KB | 1/1/83/87 | 495 B | 22.3 |
| Account delegation map | 51 KB | 1/1/95/107 | 487 B | 22.7 |

Two things worth noting. `document.detail` returns an **outline**, not
content — a few hundred bytes for a 72 KB document — so opening a document
in the Documents view never transfers the body. And its cost tracks
**section count, not document size**: the 54 KB document with 14 sections
costs 3–4× the 72 KB document with one. If a slow document is ever found,
section count is the thing to look at.

Document **content** comes from `information.interchange.readable`, which
the desktop does not call at all: 36 KB in **91 ms**, the slowest single
operation measured.

## Client-side: the operation cascade per user action

Measured from the protocol fixture's own frame log rather than by hooking
the renderer, so a click that hit nothing shows up as zero frames instead of
being silently mismeasured. (An earlier renderer-hook harness reported zero
calls for every action; its numbers were meaningless.)

| action | wall | frames | operations |
|---|---|---|---|
| open Library window | 146–172 ms | 4 | `types.capabilities`, `interchange.capabilities`, `information.list`, `information.facets` |
| click Documents | 45–56 ms | 1 | `document.list` |
| open a document | 49–65 ms | 2 | `document.detail`, `information.read` |
| reopen the same document | 52–65 ms | 2 | same two again |
| click Manual | 64 ms | 4 | **`information.list` ×2**, `types.capabilities`, `facets` |

`main` and the unmerged desktop branch are the same here — two frames per
document open on both. Two small observations:

- **The Manual tab issues `information.list` twice** on a single switch.
- **Reopening a document refetches both frames**; nothing is cached.

Neither is expensive at these latencies, but both are free to fix and would
matter if the real path is slower than the fixture.

## Window open, and why the bundle fix did not help

| | baseline | after the fix |
|---|---|---|
| renderer JavaScript, total | 4465 KB | **2322 KB** |
| domInteractive, median | 61.3 ms | 60.9 ms |
| domContentLoaded, median | 75.3 ms | 74.8 ms |
| first contentful paint, median | 88 ms | 92 ms |
| JS heap | 4.2 MB | 4.0 MB |

Six rounds each. **Halving the renderer JavaScript changed nothing
measurable.** Bundles load from local disk — `responseEnd` is about 6 ms —
and V8 parses lazily, so 4.4 MB was never on the critical path. The fix is
worth keeping for the reasons below, but it is not a performance fix and
should not be described as one.

## The bundle waste, and the fix that landed

Every renderer bundled the generated 282-operation JSON-schema catalog, and
two bundled it twice:

| bundle | before | schema | copies | after |
|---|---|---|---|---|
| `app.js` | 1453 KB | 659 KB | 2 | **613 KB** |
| `usage.js` | 1054 KB | 658 KB | 2 | **394 KB** |
| `library.js` | 697 KB | 329 KB | 1 | 477 KB |
| `proc.js` | 574 KB | 329 KB | 1 | 375 KB |
| `relay.js` | 445 KB | 329 KB | 1 | 317 KB |

The duplication was a resolution split, not a bundler quirk. The desktop
imports `plowshare-client-ts/operations/*`, whose exports map points at
`sdk/typescript/build/...`; `plowshare-console` reaches the same modules
relatively at `sdk/typescript/src/...`, because it declares no dependencies
and so cannot use the package specifier. Those are two different files, so
esbuild bundled both.

Fixed by mapping the package's subpaths, in `plowshare-desktop/tsconfig.json`,
to the same sources the console uses. The map mirrors the exports map rather
than globbing, because `plowshare-client-ts/jobs` resolves to
`jobs/lifecycle`, not `jobs`. Renderers were also shipping **unminified**;
they now minify with external sourcemaps.

Typecheck is clean and all 327 desktop tests pass.

### What remains, and is the real waste

The catalog is still **~300 KB of every bundle** — it is data, so minifying
does not shrink it — and each window uses about **six** of the 282
operations. `library.js` is still 63% schema.

It cannot be tree-shaken as written, because `schema.ts` reaches it by
runtime string key (`OPERATION_SCHEMAS.inputs[type]`), which no bundler can
narrow. Removing it needs the catalog to become reachable per operation —
a change to how `decodePayload`/`decodeReply` are given their schema, with
call sites supplying it via static imports. That is a design change, not a
build tweak, and was not attempted. The prize is roughly another 1.5 MB and
a `library.js` nearer 180 KB.

A cheaper interim step, if it is ever wanted: load the catalog lazily so it
is off the first-paint path. Given that first paint did not move when the
bundle halved, this would probably also change nothing, and should be
measured before being believed.

## What was not measured, and why it matters most

**The real desktop against the real server.** Every client-side number here
comes from the local protocol fixture, whose documents are synthetic and
tiny; every server number comes from a direct SDK connection with no UI.
The reported symptom lives in the combination, and that is the one thing not
covered.

Testing it means running the desktop on the evaluation VM against the live
server, which needs the live account's password. Logging in is an explicit
operator action under the evaluation host's own rules, so I did not do it.
**This is the first thing to do next**, and it is cheap: open Library >
Documents on the live desktop with devtools open and read the timings.

Also untested:

- **Chapter clicks in the Manual.** The fixture's manual corpus is empty, so
  the harness found no chapter controls. Each click fires a `library-view`
  IPC, and the user's report mentions waiting on manual pages specifically.
  Worth measuring against a populated manual.
- **Whether the manual is populated at all.** The user reported it "broke
  during the permissions change", and the manual installer writes Markdown
  chapters through the information write path — the path that was refused
  until PR #104. A manual with no chapters, or one whose pages fail, would
  look like slowness while actually being the write defect's shadow. Check
  this before chasing performance.
- **Connection count per window.** `connect + authenticate` is ~97 ms. If
  each window authenticates separately this is additive, and it is worth
  confirming whether windows share one session.

## Corpus state, for whoever picks this up

Ten documents are loaded in the live `client-evaluation` personal space as
observation data (35–72 KB, deliberately varied). At the time of writing the
pipeline had summarised and tagged them with `paragraph_summariser` and
`information_tagger` answering normally, but `auto_tag_generated` was still
false on all ten — it was progressing, not stuck. One transient embedding
failure was logged against the `studio` pool
(`unexpected end of stream on http://192.168.1.122:1234/`, attempt 1/2),
which retries.

## Method note

Two harness mistakes are recorded because each produced confident, wrong
numbers:

1. A renderer hook on `window.plowshare.request` reported **zero** calls for
   every action, because the harness reloaded the window after installing the
   hook. Zero was reported as a result rather than as a broken instrument.
   Reading the server's own frame log instead removed the whole class of
   error.
2. A probe killed locally left its **remote** node process alive holding the
   credential-store lock, so every later probe hung in `authenticate` and
   looked like a server problem. Kill remote work remotely.

Measure the thing the user described, from where they described it. Each
layer measured here is fast; that is informative but it is not the answer.
