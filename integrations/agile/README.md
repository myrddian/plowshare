# Excalibur

A separate, small agile Application using the public Plowshare Python SDK. The
package provides a deterministic CLI, a web board and shared Application actions.
Tickets and epics live in Plowshare Information; local files hold configuration
and exact request intents, never a second ticket store.

Excalibur is the app's name. The `excalibur` command is an alias for
`plowshare-agile`; the Python package, Application template and `agile-ticket` /
`agile-epic` schema identifiers remain compatible. See [Docker deployment](deploy/README.md)
to run this app independently of the Plowshare server.
The earlier `furrow` alias and Docker installation identifiers remain compatible;
changing the displayed product name does not rename a live project or invalidate
its private journal binding.

## Install and configure

Use Python 3.11 or newer. Neither this package nor the SDK is published remotely;
build/install the matching public SDK and Application packages in a virtualenv.
The SDK must match the deployed server's current `includeGlobal:false` project
scope contract; the older `includeShared` SDK contract is not supported. When
the server is newer than this checkout, build its SDK wheel from that exact
public release rather than editing generated SDK files:

```sh
python -m pip install "$PLOWSHARE_SDK_WHEEL" ./integrations/agile
```

Deploy the bundled `plowshare_agile/application/` root as a normal Plowshare
Application, with explicit human grants and existing project membership. It
contains a root manifest and the companion `agile-board` agent skill. Obtain
installed assets using `importlib.resources.files('plowshare_agile')`; source
assets are under `src/plowshare_agile/`. Do not put credentials into the manifest.

A project Manager registers and activates both supplied schemas through public
`information.types.version.create` and `information.types.activate`. Print each
schema with `plowshare-agile schema ticket` or `plowshare-agile schema epic`.
Use slugs `agile-ticket` and `agile-epic`. Record their returned type/version UUIDs.
This explicit setup does not add an Application schema-installer contract.

Copy the bundled `config.example.toml` to a private location and fill every value. The origin,
project, token environment variable, private request directory and four schema
identities are required. Use a token whose identity owns the documents it edits;
project membership alone does not grant another owner's editing rights. The CLI
uses project scope with `includeGlobal:false` and the supporting typed view.

```sh
plowshare-agile --config "$AGILE_CONFIG" check
plowshare-agile --config "$AGILE_CONFIG" tickets list
plowshare-agile --config "$AGILE_CONFIG" epics list
```

Set the configured bearer variable privately. The CLI never accepts a bearer on
its command line, logs it, logs in through HTTP, or silently selects a local server.
`check` probes typed and navigation reads; admission and schema assignment remain
server-authorized and may be disabled even when reads succeed.

## Commands

Commands write JSON to stdout and errors to stderr. Exit codes are 0 for success,
2 for input/response/local-file errors, 3 for a server refusal, 4 for a transport
failure and 130 for interruption. An admission response is not proof that source
processing or an investigation has completed.

```sh
plowshare-agile --config "$AGILE_CONFIG" ticket create --title 'Explain reconnect failures' --body-file ticket.md --request-id "$REQUEST_ID"
plowshare-agile --config "$AGILE_CONFIG" epic create --title 'SDK usability' --body-file epic.md --request-id "$REQUEST_ID"
plowshare-agile --config "$AGILE_CONFIG" ticket show "$TICKET_ID"
plowshare-agile --config "$AGILE_CONFIG" ticket show "$TICKET_ID" --body
plowshare-agile --config "$AGILE_CONFIG" ticket move "$TICKET_ID" in-progress --expected-revision "$REVISION" --request-id "$REQUEST_ID"
plowshare-agile --config "$AGILE_CONFIG" ticket update "$TICKET_ID" --fields-file fields.json --expected-revision "$REVISION" --request-id "$REQUEST_ID"
plowshare-agile --config "$AGILE_CONFIG" ticket set-epics "$TICKET_ID" --epic "$EPIC_A" --epic "$EPIC_B" --expected-revision "$REVISION" --request-id "$REQUEST_ID"
plowshare-agile --config "$AGILE_CONFIG" ticket set-tags "$TICKET_ID" --tag sdk --tag websocket --expected-revision "$REVISION" --request-id "$REQUEST_ID"
plowshare-agile --config "$AGILE_CONFIG" tickets list --epic "$EPIC_A" --tag sdk --status ready --iteration Next
```

Supply a fresh caller-retained UUID for every distinct mutation; the shell
variables above are placeholders, not a sequence that reuses one request ID.
`ticket create` supports `--kind`, `--priority`, `--assignee` and `--iteration`.
`ticket update` replaces the complete six-field metadata object; use the schema
or the `fields` object returned by show. Reopening is a move to any other status.
Add `--body-file` to that command to replace original Markdown through the same
shared body-revision action used by the web UI. Supply original Markdown, not a
copy of extracted text from `show --body`.

`set-epics` replaces the ticket's epic set and preserves its topic tags. Repeat
`--epic` for intersections; omit it to clear membership. `set-tags` replaces
topics while preserving epic tags; omit `--tag` to clear topics. Neither creates
a document relationship. Both use the existing owner-only tag control and its
32-tag limit. Tag writes lack metadata's expected-head compare-and-set, so this
first version supports one maintainer and explicitly refreshes before replacement.
There remains a possible race with another tag writer; no concurrency guarantee
is inferred from the command's expected body revision.

Lists count unique current documents, not historical revisions. A complete
filtered selection is limited to 100 tickets or 20 epics; overflow and duplicate
resources during paging fail explicitly. Status/assignee/iteration filtering is
local after bounded native type/tag discovery. `show --body` uses bounded retained
text windows and may be refused until extraction is ready. Showing metadata does
not require that read. Epic title editing and history navigation are not yet CLI
commands. The web UI orders tickets by priority and stable identity.

## Web UI

Install the optional web dependency alongside the matching public SDK:

```sh
python -m pip install "$PLOWSHARE_SDK_WHEEL" './integrations/agile[web]'
plowshare-agile --config "$AGILE_CONFIG" serve --bind "$AGILE_BIND" --port "$AGILE_PORT" --public-origin "$AGILE_WEB_ORIGIN" --access-token-environment AGILE_WEB_ACCESS_KEY
```

Configure all four server arguments explicitly. The public origin is the exact
browser-facing HTTP(S) origin, without a trailing slash or path. Set a separate
random web access key of 32–256 characters in `AGILE_WEB_ACCESS_KEY`; never use
the Plowshare bearer as this key. For remote use, terminate HTTPS at your reverse
proxy and preserve the configured Host. The server does not trust forwarded
identity headers or add a multi-user login system.

Open that origin and enter the web access key. This is a single-maintainer
workspace: a holder of the key operates through the configured Plowshare identity,
whose current document authority still governs every SDK request. The bearer
never reaches the browser. Web sessions last eight hours, use HttpOnly,
SameSite=Strict cookies (Secure for HTTPS), and end on sign-out or app restart.
Mutations require the exact configured origin and a per-session CSRF value.

The interface provides:

- A four-column board with status controls and ticket creation.
- Ticket details with metadata, original Markdown reading/editing, epic
  membership and topic tags. Each section saves explicitly through shared actions.
- Epic creation, detail editing, and overlapping ticket progress counts.
- Intersecting epic, topic, kind, assignee, iteration and title filters. Changing
  the selection preserves an open document and its unsaved edits.

Original Markdown is read through public `information.interchange.readable`,
including exact resource/revision and hash checks. Extraction is never substituted
as editable source. Metadata-only saves do not rewrite a loaded body merely
because a textarea normalizes its line endings. Body changes create typed
revisions; they retain normal document processing and accounting behavior.
The safe Markdown reader is bundled from the public SDK grammar; installed users
need no Node runtime or CDN. Rebuild it with `node scripts/build_web.mjs` from
this package's directory after changing its source or the matching SDK grammar.

The browser retains only the outstanding request identity in local storage before
submission, while the private server journal retains its exact payload. A lost or
unreadable reply blocks further saves until **Check saved request** explicitly
recovers that same request. A stale-revision refusal preserves the draft for review;
it never silently merges against a newer head. Refreshing the board preserves
document edits. Recovering a request confirms its prior outcome; reload the document
explicitly to review current content. The app checks upstream authority and
readiness every 30 seconds and exits on a failed check. A container supervisor
can restart it with a fresh SDK connection; writes are not auto-replayed. Web
sessions end on that restart, while pending request identities and private
journal files persist for explicit recovery.
Internal server failures also leave a submitted intent pending. They do not prove
that a mutation was refused or safe to submit under a new request identity.

The app's own small HTTP interface serves the browser; all Plowshare traffic
still passes through `SdkBoardPort` and the public authenticated WebSocket SDK.
The CLI and browser share `Board.update`, creation intents, membership actions
and the same private journal. This adds no core HTTP endpoint or ticket database.

## Recovery and private state

The configured request directory must be private (mode 700). Before submission,
each command creates a mode-600 intent containing its exact mutation inputs and
a connection/schema binding. This data can contain private ticket text; retain it
privately. It is not an authoritative copy of the ticket and stores no bearer.

```sh
plowshare-agile --config "$AGILE_CONFIG" request show "$REQUEST_ID"
plowshare-agile --config "$AGILE_CONFIG" request recover "$REQUEST_ID"
```

The first command reports the local historical outcome. Recovery explicitly
submits the exact retained identity/payload to recover the server's durable
receipt; it never rebuilds a mutation from the latest ticket. The SDK does not
automatically retry. Unknown delivery, interrupted settlement and unexpected
replies leave the intent pending. A confirmed receipt is historical, not current
ticket state; read the resource again. Recovery is bound to a non-reversible
credential fingerprint, so even local inspection needs the configured bearer.
Changing the bearer, origin, project or schema identities refuses recovery under
that configuration. Recovery across credential rotation is not implemented: the
CLI cannot safely infer that another bearer represents the same receipt owner.
It never submits a pending mutation under a different identity to guess.

Creation and tag assignment are separate commands/receipts. A failed membership
write never repeats a confirmed ticket creation. Disk persistence uses flushed
private files; this initial journal makes no guarantee against storage-device
failure or loss of the private directory.

## AI and scope

AI is optional. Useful later work includes drafting acceptance criteria from a
rough request, finding related tickets, investigating a selected bug and returning
evidence with a proposed resolution. Keep human decisions distinct from model
suggestions and link exact input revisions and durable run receipts.

The included skill describes the first CLI. Plowshare-native model tools still
exclude owner-authored tag controls: do not evade that policy with a shared
credential. A future Work on ticket action should use a granted server-owned
orchestration. This package starts no agent, model or schedule automatically.

## Verify

With the package, matching SDK and check dependencies installed:

```sh
python integrations/agile/scripts/check.py
python -m build integrations/agile
```

Checks use fake SDK boundaries, local HTTP/WebSocket fixtures, browser projection
and injection tests, and a generated Markdown freshness check, without a
database. A configured live-server acceptance run is separate: verify ownership,
schema activation, typed admission, epic intersections, conflicts, receipt recovery
and persistence across server restart before declaring deployment ready.
