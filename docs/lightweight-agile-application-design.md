# Excalibur: lightweight agile Application

Status: external Application design and implementation, refreshed 19 October 2026
against public release `1b9f8bbb550cff914da1957c3ea677f21c6deeca`.
[Authored information](authored-information.md)
and [document navigation](authored-navigation.md) now supply the public schema,
metadata revision, current-document and saved-path contracts used below. Schema
and navigation writes default on in source; probe the target server's actual
capabilities and permissions before installation. This is not deployment proof.

Build a small agile board using Plowshare's document system. Use it to track
Plowshare features, bugs and chores, and make the same Application available to
external users. Its purpose is to demonstrate how much functionality an
Application can add through public contracts, with no ticket-specific core code.

The product name is **Excalibur**. Its existing `plowshare-agile` package,
CLI name and schema identifiers remain compatible; `excalibur` is a CLI alias.
[Docker packaging](../integrations/agile/deploy/README.md) runs only the external
app, with a dedicated scoped credential, private persistent request journal and
an explicit browser origin. That guide also records live public-operation gaps;
packaging or a healthy listener does not prove every server capability works.

## Separate Application and interfaces

Ship a separate installable `plowshare-agile` package. It has three interfaces:

| Interface | Purpose | Delivery |
| --- | --- | --- |
| CLI | Deterministic commands for people, scripts and explicitly authorized external coding agents | First implementation |
| Web UI | Four-column board, ticket and epic details, and intersecting facets | Separate app server over the same Application actions |
| Agent skill | Explain the record conventions, commands, permissions and recovery rules | Bundled with the Application |

The CLI and web UI call shared Application actions and the public SDK.
Plowshare owns schemas, tickets, epics, revisions, permissions and any optional
durable work. The package owns its presentation, configuration and exact local
request intents. It neither adds a core ticket subsystem nor keeps a second
authoritative database. The web UI is hosted separately from the Plowshare console.

The initial implementation lives in [integrations/agile](../integrations/agile/README.md).
Its Python CLI uses only generated public SDK requests. Commands include
`tickets list`, `ticket show`, `ticket create`, `ticket move`, `ticket update`,
`epics list`, `epic show`, `epic create`, `ticket set-epics`, `ticket set-tags`
and explicit `request show`/`request recover`. JSON output supports automation.
Bundled schemas and a deployment manifest accompany the skill. The `serve` command
runs the separate web UI with explicit bind, port, public origin and web-access-key
configuration. Automatic type installation and running agent procedures remain
outside this implementation.

## First version

One Application project contains one board. A person can create a ticket, write
its description and acceptance criteria, set its priority and assignee, group it
under one or several epics, add topic tags, select a planning iteration, and move
it between four statuses:

```text
Backlog -> Ready -> In progress -> Done
```

These columns describe work rather than enforce a sequential procedure. Any
status may move to any other, including reopening Done work. An iteration is an
optional text label such as `Next` or `October maintenance`; selecting the label
filters the board. There is no separate sprint record or sprint lifecycle.

The first version has no estimates, dependencies, custom workflows,
threaded comments, notifications, automatic assignments or mandatory reviews.
Ticket creation and movement do not invoke an agent or start an orchestration.

## Ticket documents

Each ticket is an ordinary Markdown source with the project-local content type
`agile-ticket`. Its stable Information resource UUID is its identity. Revision
UUIDs identify particular edits. Use an application-generated UUID in a stable
source name such as `agile-ticket/<UUID>.md`; retain the returned resource UUID.
The interface can abbreviate the resource UUID, but uses the full identity for
all operations. Sequential ticket numbers are unnecessary.

The schema defines six metadata fields. All are required; an unassigned ticket
has explicit null values for `assignee` and `iteration`.

| Field | Values | Meaning |
| --- | --- | --- |
| `title` | Text, 1–160 characters | Card and detail heading |
| `kind` | `feature`, `bug`, `chore` | Small classification |
| `status` | `backlog`, `ready`, `in-progress`, `done` | Board column |
| `priority` | `low`, `normal`, `high` | Sorting within a column |
| `assignee` | Text, 1–120 characters, or null | Planning label for the responsible person |
| `iteration` | Text, 1–80 characters, or null | Optional batch of planned work |

Assignee is descriptive metadata. It neither grants access nor selects an
execution account. The Application checks nonblank labels; schema string lengths
alone do not reject whitespace. Schema validation does not verify that a named
person exists.

The proposed schema uses the [shared executable profile](tool-schemas.md):

```json
{
  "type": "object",
  "properties": {
    "title": { "type": "string", "minLength": 1, "maxLength": 160 },
    "kind": { "type": "string", "enum": ["feature", "bug", "chore"] },
    "status": { "type": "string", "enum": ["backlog", "ready", "in-progress", "done"] },
    "priority": { "type": "string", "enum": ["low", "normal", "high"] },
    "assignee": { "type": ["string", "null"], "minLength": 1, "maxLength": 120 },
    "iteration": { "type": ["string", "null"], "minLength": 1, "maxLength": 80 }
  },
  "required": ["title", "kind", "status", "priority", "assignee", "iteration"],
  "additionalProperties": false
}
```

The create form supplies explicit starting values: `feature`, `backlog`, `normal`,
null assignee and null iteration. These are Application choices; the schema does
not insert defaults. The body starts with a small editable template:

```markdown
## Description

What should change and why?

## Acceptance criteria

- [ ] An observable outcome

## Notes

Implementation notes and links to relevant documents or work.
```

Notes provide a simple place for discussion and result links. They remain edits
to one document, without pretending to be separately authenticated comments.
Acceptance checkboxes guide people; Done does not certify that they passed.

## Epics and intersecting facets

An epic is another Markdown source, with the project-local content type
`agile-epic`. Its body describes the intended outcome, scope and notes. Its only
required metadata field is `title`:

```json
{
  "type": "object",
  "properties": {
    "title": { "type": "string", "minLength": 1, "maxLength": 160 }
  },
  "required": ["title"],
  "additionalProperties": false
}
```

Each epic's stable resource UUID defines an Application tag,
`epic:<lowercase resource UUID>`. A ticket belongs to that epic when its existing
Information resource tags include that value. Tickets can belong to zero, one
or several epics. Renaming an epic preserves membership because its tag uses
identity rather than the title. There is no single parent field or duplicate
membership array in ticket metadata.

Keep ordinary topic tags such as `sdk`, `websocket` and `accessibility` alongside
epic tags. The Application reserves the `epic:` convention for its epic picker,
which resolves authorized epic documents in the same project. This convention
provides no foreign-key, deletion or access guarantee. Missing or unreadable
epics remain unresolved; never reveal their titles, silently remove their tags,
or create a replacement epic from an unresolved tag.

For example, a ticket called "Explain reconnect failures" can belong to both
"SDK usability" and "Operational reliability", with topic tags `sdk` and
`websocket`. Selecting either epic shows that same ticket. Selecting both epics
shows their intersection; adding `websocket` and iteration `Next` narrows it
further. Clearing the epic selection returns to the wider board.

The first interface uses intersection semantics for all selected epic and topic
tags, matching the existing [Information filters](information-system.md#faceted-navigation-and-tags).
It counts unique current ticket resources after filters. Epic views can overlap,
so their totals must not be added into a board total. Optional progress is simply
Done tickets divided by all readable current tickets in that epic; an empty epic
shows no tickets rather than 100 percent. Counts reflect the reader's access.

Epics add no nested hierarchy, independent workflow or automatic closure. Their
membership is explicitly owner-authored; generated `autoTag` and automatic tag
groups never add or remove tickets from an epic. Native tags are resource-level
state, so document revision history does not by itself reconstruct historical
epic membership. Historical scope tracking is outside this first version.

## Interface

The board has four columns, a New ticket button, an Epics view, and intersecting
filters for epic, topic tag, kind, assignee and iteration. The Epics view creates
and opens epic documents and selects their ticket facet. Ticket details have an
epic multiselect and topic tag editor. Cards show the title, abbreviated identity,
priority and assignee, with epic titles available as grouping chips.
Order cards by priority, then stable resource identity. Buttons move cards between
columns; drag and drop is optional presentation work.

Opening a card shows the Markdown body, metadata editor and retained revision
identity. A full history browser remains planned; exact retained revisions remain
available through the existing Information interfaces.
Conflicts preserve the unsaved edit and offer reload and reapply. Render Markdown
through the existing safe grammar, with no executable HTML from ticket content.

The browser talks to the separate Python Application server, which uses the same
`Board` actions and public SDK port as the CLI. It has no
authoritative ticket database, private server API or background execution engine.
Hosting the client is a separate deployment step; Application installation does
not imply that Plowshare hosts arbitrary web assets. Browser closure does not
erase saved tickets. Configure the connection explicitly and keep credentials
out of Application source and browser assets.

The initial server is a single-maintainer workspace with a separate web access
key and short-lived cookie sessions; the Plowshare bearer stays server-side.
Origin/Host and CSRF checks protect its mutation boundary. Original Markdown is
read through public `information.interchange.readable`, checking representation,
identity and hash; retained extraction is never used as editable source. Loading
the reader cannot turn a metadata save into a body rewrite. A pending request ID
survives browser reloads while the shared private journal retains its exact input.
The UI exposes explicit recovery, preserving drafts after conflict or uncertainty.

Saved navigation paths can retain the type, epic-tag and topic-tag selection
through `information.views.save`. They are account-owned. Assignee and iteration
remain client filters; the current saved-path contract does not add arbitrary
metadata-field filters or shared team views.

## Reads and writes

Probe `information.types.capabilities` and `information.navigation.capabilities`
using the explicit project scope. Type-aware operations select
`view:"content-types-v1"`; service connections also use `includeGlobal:false`.
Refuse unavailable capabilities rather than silently dropping schemas.

Create a source through typed `information.upload`, selecting the active project
schema version. Read its body through existing Information reads and its binding
through `information.content.get`. Metadata edits use `information.content.revise`
with `inherit`, the complete replacement metadata object and the displayed
`expectedRevision`. Body edits use typed `information.revise` with the same exact
starting-revision discipline. These operations are implemented public contracts,
described in [ADR 0027](decisions/0027-authored-information-contracts.md).

Create and edit epic documents through those same typed operations with the
`agile-epic` schema. Add or remove epic membership and topic tags through existing
owner-authorized `information.tags`, preserving unrelated tags and the shared
32-tag resource limit. Native tags have their own mutation contract; a revision
argument does not establish the schema extension's expected-head concurrency.
Keep the one-maintainer editing limit and refresh after writes.

Ticket admission and tag replacement are separate writes. Retain separate
requests, show a ticket whose tag save is pending, and reconcile only that
pending operation. Do not report both saves as atomic or repeat a confirmed
ticket create because assigning its epic failed.

Retain a request UUID and exact payload before each write. A stale revision is a
conflict; a lost reply is uncertain. Recover through the owning retained receipt
or status contract before another mutation. Never generate a replacement request
to repeat an uncertain create or edit. Clear unsaved changes only after confirmed
admission. After a write, reload authoritative state rather than treating a card
animation as a committed update.

Metadata-only movement follows ADR 0027's explicit-only processing policy and
requires no document-processing inference. Body creation and editing retain the
ordinary source-processing rules and configured allowance. The initial Application
installs no model-calling hooks. Zero inference for every possible ticket operation
is not a guarantee of the schema extension.

Use `information.documents.list` for the board, with the installed ticket type
UUID and selected epic/topic tags. It returns one current entry per readable
resource, including resource/revision UUIDs, resource tags and scope. Filtering
applies to the actual current head; an older matching revision is not substituted
after typing changes or the head becomes unavailable. A type filter includes its
bound versions, so inherited deprecated versions remain discoverable.

Read each returned revision's binding through `information.content.get` for
title, status, priority, assignee and iteration. Load epic documents by their own
type through the same current-document API. `information.documents.get` follows
a resource's current head for detail refresh; its unavailable result must not
be replaced with an older readable ticket. Historical inspection remains an
explicit revision read.

Use `information.documents.facets` for native current-document tag/type counts.
The older `information.facets` still counts revisions and is unsuitable for
ticket totals. Assignee, iteration and other custom metadata filters remain
client-side; compute their visible totals from the loaded unique ticket set.
There is no per-ticket relationship required for this tag-based navigation.

Cap the first board at 100 tickets and 20 epics. Use bounded paging and native
document counts to detect overflow; refuse to present a partial set as a complete
board. Current-head selection and document counts are now supplied by public
navigation contracts. General metadata-field querying remains a possible later
platform need; the first Application adds no SQL or ticket-specific query endpoint.

## Ownership and installation

Begin with one authenticated maintainer owning the ticket and epic documents, with
project-authorized readers. This is a useful personal or maintainer-operated agile
board. An assignee can be a reader and still cannot edit another owner's document.
Expose actions according to actual document authority, not an assumed project
Contributor editing right. Do not borrow a service credential to hide that limit.

A collaborative board requires general document editor grants or a separately
designed, authenticated service-mediated writing model. That is a visible gap for
team use and is outside this first version.

Deploy a normal root `plowshare.json` with explicit access grants. An authorized
project Manager registers and activates both supplied schemas through public type
operations; the ticket owner needs separate Information write authority. Retain
the namespace-local type and version UUIDs in private installation configuration.
Existing tickets keep their bound versions when the schema evolves.

Supply both schema files and installation instructions with the Application. The
public registry supports their explicit creation and activation; automatic
Application-distributed type installation remains outside this design.
No undocumented manifest field, direct SQL or ticket-specific migration is needed.

## Optional work procedures

AI is useful around the work: turn a rough request into a ticket with observable
acceptance criteria, find overlapping tickets across epics, investigate a bug,
or propose a resolution supported by retained evidence. Ordinary creation and
board changes remain useful without inference. Suggestions, investigation results
and human acceptance remain distinct records or clearly attributed notes.

Plowshare contributes durable server-owned execution, permission-scoped evidence
and inspectable work history when those procedures are added. It is not necessary
to invoke a model for ticket CRUD merely because the Application uses Plowshare.
The first agent skill teaches the CLI and existing granted Information tools;
it does not start agents automatically. Built-in model-facing Information tools
still exclude owner-authored tag writes. An unavailable epic-edit action remains
a proposed change for a person, not an excuse to borrow stronger credentials.

After the manual board works, add an explicit Work on ticket action that starts a
granted orchestration using the exact ticket revision as input. Retain its start
request and run identity. Its result can be linked in Notes after a fresh read
and an authorized revision write. A conflicting edit requires reconciliation;
work completion does not silently overwrite the ticket or mark it Done.

The ticket persists across zero, one or several runs. There is no permanent run
per ticket. Automatic triage, Relay subscriptions and scheduled follow-ups remain
optional later additions. If mandatory transition rules are introduced, they need
enforcement at every applicable document admission path; routing this UI through
an orchestration alone cannot enforce them.

## Core boundary and delivery

| General platform responsibility | Application responsibility |
| --- | --- |
| Typed documents, immutable revisions and expected-head concurrency | Ticket and epic schemas, body templates and four ticket statuses |
| Current resource selection, native resource tags and authorized discovery | Epic membership convention, intersecting board facets and unique ticket counts |
| Ownership, permissions and durable write recovery | Honest editing controls and conflict presentation |
| Public deployment, schema and optional orchestration contracts | Installation instructions and any later work procedure |

The current schema and navigation source provide the initial board's data and
discovery contracts. Verify their capability probes on the configured target,
then type installation and current-document reads, then create/read/edit one
ticket, then add the board. Shared editing, arbitrary metadata queries and an
automatic type installer remain outside the first version. Missing public capabilities
are reported as general platform gaps under [ADR 0001](decisions/0001-integration-boundary.md).
This design authorizes no core changes to make the Application work.

The first dogfood scenario is deliberately short:

1. Install on an otherwise unmodified compatible Plowshare release.
2. Create two epics and a feature ticket; assign both epic tags, a topic tag and
   an iteration.
3. Move it through Ready and In progress; retain body and metadata history.
4. Record a result, mark it Done and reopen it without losing earlier revisions.
5. Demonstrate stale-edit refusal, uncertain-write recovery and reader write refusal.
6. Restart the client and server; recover the same board from retained documents.
7. Find the same ticket under either epic and their intersection without duplication;
   rename an epic without losing membership, and remove one membership without
   changing the other epic or topic tags.

Use mocked SDK boundaries for client behavior and a configured live server for
the final persistence and authorization scenario. Record installation steps,
Application code size, public-operation gaps and duplicated plumbing. Success
means a useful basic board with zero ticket-specific core changes; it also makes
the effort of building an Application comparable with integrating an existing
tracker.
