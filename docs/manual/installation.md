# Install the manual in the Library

The manual is checked-in Markdown plus a manifest of chapters. The installer
uploads those chapters as ordinary information sources through the Node SDK,
tags them for navigation, waits for retained text to be readable, and optionally
shares each revision. The server retains the manual in PostgreSQL and its existing
document projections. No separate documentation service or REST endpoint is added.

## Prepare and preview

Use a checkout matching the manual edition you want. The repository's Java and
TypeScript build prerequisites are Java 21, Node.js 22.12+ and pnpm. The offline
preview needs only Node; it does not authenticate, create a local journal or submit
work:

```sh
node scripts/install-manual.mjs --dry-run
```

The manifest includes introductory/architecture chapters and existing detailed
capability guides. It reads only those bounded Markdown files inside this
checkout. Missing, duplicate, escaping or malformed chapters stop the installation
before any upload. Repository-relative chapter links become stable
`plowshare-manual:<chapter-id>` links in installed text; the desktop manual reader
resolves and opens their current shared revisions. The original Markdown retains
its repository navigation.
External links and fenced code examples remain intact.

Build the client SDK and CLI, then sign in with a normal completed account:

```sh
./gradlew :plowshare-client-node:nodeBuild :plowshare-cli:cliBuild
bin/plowshare-cli login --url http://127.0.0.1:8091
```

The address above is a local-development example. Use your actual server origin
for a deployment. Every live installer invocation requires `--url` or
`PLOWSHARE_URL`; there is no localhost fallback. To reuse the configured origin
across the commands below, set it explicitly, for example in local development:

```sh
export PLOWSHARE_URL=http://127.0.0.1:8091
```

Use one stable account to publish future updates. It owns these ordinary sources;
other accounts gain access through explicit sharing. The installer needs no
database credentials or operator-token bypass. Saved authentication uses the same
credential store as the other clients. Account/password environment overrides
are supported through the SDK and are not stored in the manual journal.

Use a human account for this installer. Clear `PLOWSHARE_TOKEN` if your environment
currently selects a scoped service credential: those identities have no Personal
collection and cannot read the shared catalogue. See
[accounts and service tokens](../server-administration.md). The installer currently
has no project-scoped publication mode for service-account readers.

## Install

To publish the non-private manual for authenticated accounts on the server:

```sh
node scripts/install-manual.mjs --url http://127.0.0.1:8091 --share
```

Without `--share`, new revisions stay in the installing account's personal
information collection. Sharing is explicit; running later without `--share`
does not unshare a revision already shared. Changed content creates a new revision
with its newly selected audience, while unchanged content reuses its confirmed
receipt. This is an information audience, not creation of a new Personal project
or an account-wide workspace attachment.

Each completed chapter prints its chapter ID, revision UUID and verification result.
Without `--share`, it reports readable text without asserting the current shared
audience of a previously installed revision.
Save those IDs if diagnosing partial installation. A success line means the
installer verified actual readable text, including a shared-scope read when
`--share` was selected. It does not claim that every model-derived summary or
embedding completed.

## Processing and cost

These are ordinary source admissions. They use the server's configured document
processing allowance, models, hooks and lifecycle. The installer tags chapters
early, but does not bypass model processing, grants or denied stages. Extraction
and derivation are required for reading/sharing; embeddings and summaries may
continue independently or fail while retained text remains usable.

There is no separate agent run to import the manual. Normal background document
preparation may still incur inference costs. Inspect source status and usage rather
than assuming the whole installation is model-free. A bounded per-chapter
readiness wait prevents the installer waiting indefinitely for unavailable work.

```sh
node scripts/install-manual.mjs --share --readiness-ms 600000 --timeout-ms 30000
```

`--readiness-ms` controls waiting for each chapter's extraction/derivation;
`--timeout-ms` bounds authentication and individual WS requests. A wait timeout
does not cancel server processing or erase already installed chapters.

## Find and read it

In the desktop, click the **Help** question-mark icon in the main toolbar. It opens
**Library → Manual** at the index. Follow chapter links or choose a chapter from
the source list. This reader fetches the complete bounded chapter through ordinary
shared information reads and renders its Markdown. An absent installation shows
**Manual unavailable** and asks an administrator to install it.

Help reads the shared manual. To read a personal installation, open
**Library → Documents → Manage document sources and imports**, choose your
collection, and filter **Your tags** by `plowshare-manual`. The ordinary source
reader presents bounded windows; chapter-link navigation belongs to the Manual tab.

### Names, IDs and links

Each chapter has a stable manifest ID, for example `13-hooks`. Its supplied upload
name is `Plowshare manual / 13-hooks.md`; the catalogue calls this `source_name`.
The Markdown heading is its readable title. Names remain stable when titles or
content change. Uploading the same name in the same publishing account's namespace
reuses the resource identity and creates a new immutable revision UUID.

The server generates resource and revision UUIDs. The installer does not preallocate
or override them. Each chapter resource carries `plowshare-manual` and
`manual-chapter-13-hooks` tags; these survive new revisions. Help resolves these tags and verifies the supplied
name before opening the returned revision UUID. Installed chapter links therefore
survive updates. An explicitly retained revision ID continues to identify that
historical text, subject to the existing access and retention rules.

Use one publishing account. If two discoverable shared revisions supply the same
chapter, Help reports the ambiguity instead of choosing a publisher. Exclude the
superseded publication through the information lifecycle. Installation is explicit;
the server does not upload these documents automatically at startup.

CLI listing:

```sh
bin/plowshare-cli information list '{"scope":{"kind":"shared"},"filter":{"tags":["plowshare-manual"]},"limit":100}'
bin/plowshare-cli information read '{"scope":{"kind":"shared"},"revision":"<revision-uuid>","offset":0,"limit":8192}'
```

In the TUI:

```text
/information list --scope shared {"filter":{"tags":["plowshare-manual"]},"limit":100}
/information read --scope shared {"revision":"<revision-uuid>","offset":0,"limit":8192}
```

Use the returned end offset to continue a long chapter. Reading source text does
not submit a model job. Asking a question about the chapter through the Library
uses the existing document-question workflow and its model allowance.

For an agent granted `information_read`, a discovery request is:

```json
{"operation":"list","filter":{"tags":["plowshare-manual"]},"limit":100}
```

Then use `{"operation":"read","revision":"<revision-uuid>","offset":0,"limit":8192}`.
Account/home come from its run. Share the manual if project agents should discover
it through their normal shared-inclusive scope. The tool cannot grant itself access
to another account's personal installation. Treat manual examples as reference
material, not automatically executable instructions.

## Updates and recovery

The default private journal lives under the Plowshare configuration directory's
`manual/` subdirectory. Its filename is derived from the server origin and
authenticated account. It stores chapter hashes, revision IDs and operation UUIDs,
not tokens, passwords or chapter bodies. Keep it across updates. `--state` can
select an absolute private path outside the checkout:

```sh
node scripts/install-manual.mjs --share --state /absolute/private/manual-journal.json
```

Known completed operations are reused. Changed chapter text is uploaded with a
new receipt. After that replacement is readable and its audience is verified,
the previous installer-tracked revision is excluded from ordinary discovery.
Exclusion preserves authorized direct reads and evidence coordinates; it does
not delete an old source. Removed manifest chapters are not automatically deleted
or unshared. Manage them explicitly if you intentionally remove a manual section.

A mutation receipt is persisted before sending. If delivery or its confirmation
is uncertain, the installer stops without resending. Inspect the receipt and
server revision where known, then deliberately resume with identical manual text:

```sh
node scripts/install-manual.mjs --share --resume
```

`--resume` authorizes repeating the pending idempotent operation with its same
UUID. Keep `--share` selected when recovering an uncertain sharing operation.
It is not a retry loop. Changed pending content or an unavailable saved
edition is refused; restore that exact edition and inspect before proceeding.
Once an upload is confirmed, subsequent processing wait failures can be resumed
by an ordinary rerun because no upload is repeated.

An operating-system kill can leave the journal's `.lock` directory. Confirm no
installer is running before removing that lock. Do not delete the journal to
recover: losing it loses the known receipt and previous-version bookkeeping and
can create duplicate retained revisions. A manually withdrawn, excluded or
unshared chapter is not silently restored from an old receipt.

Installation is per-chapter, not an atomic replacement of the whole manual.
Earlier chapters remain usable if a later one fails. The error identifies the
chapter/revision when known. It does not hide the failure behind a success line
or delete partial work. After completion, verify the Library using the intended
reader account as well as the publisher when testing project membership and
audience policy in your deployment.
