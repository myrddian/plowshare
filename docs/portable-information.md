# Portable information exchange

Status: implemented locally under [ADR 0029](decisions/0029-portable-information-interchange.md).
Source validation and deployment are separate. Portable exchange supports agents,
external authoring and Git workflows around retained Plowshare information.

## Export a document

Select an explicit information scope and discover the document through its tags,
type or relationships. Resolve its current head with `documents.get`, then pass
that exact revision UUID to `interchange.export`. To select history, supply exact
revision UUIDs explicitly; export never chooses an older readable revision when
the requested version is unavailable.

In Library → Documents → Manage document sources and imports, select a version
and open **Portable exchange**. Download either:

- **A document bundle:** a versioned JSON file containing original bytes, exact
  hashes, names/titles, owner-authored tags, schema bindings, metadata, provenance
  and included relationships.
- **Markdown and a JSON sidecar:** original UTF-8 Markdown, preserving BOM, line
  endings and Unicode, or retained extracted text labelled `extraction`. The
  sidecar distinguishes those representations. Original PDF/other-format bytes
  are available through bundle export, not disguised as Markdown.

A bundle automatically includes required retained input/reference dependencies.
Every included revision is checked against live access in the selected scope.
If a required revision is unavailable, export refuses the complete selection
before returning bytes. Optional relationships are included only when both
endpoints, and any pinned target revision, are included. The constant coverage
value `included-endpoints-only` carries no private target identifiers or counts.

The first format, `plowshare-information-bundle/1`, bounds exchange to 16 revisions,
64 relationships, 2 MiB of original bytes and 8 MiB serialized JSON. Larger or
incomplete required closures fail explicitly. Segmented WebSocket transport
carries larger messages; there is no HTTP fallback or automatic mutation retry.

## External authoring and Git

Portable paths identify files in an exchange view. They are safe relative paths,
not Plowshare's canonical document hierarchy. Resource and revision UUIDs remain
independent of filenames. Import destination names are logical information names;
they do not instruct the server to access a filesystem.

The TypeScript SDK provides platform-neutral `unpackPortableBundle`,
`packEditedPortableBundle` and `readablePortableFiles` helpers. They accept the
same narrow byte/crypto capability used by the transport and return caller-owned
bytes plus validated relative paths. They never access a filesystem. Node exports
the helpers and its `portableCodec` from `plowshare-client-node/interchange`:

```ts
import { connectPlowshare, requirePayload } from 'plowshare-client-node';
import {
  unpackPortableBundle,
  packEditedPortableBundle,
  portableCodec,
} from 'plowshare-client-node/interchange';

// origin, token, projectId and selectedRevision come from your application.
const client = await connectPlowshare({ origin, token });
try {
  const bundle = requirePayload(await client.request('information.interchange.export', {
    scope: { kind: 'project', project: projectId, includeShared: false },
    revisions: [selectedRevision],
  }));
  const { manifest, files } = await unpackPortableBundle(bundle, portableCodec);
  // Your application persists/edits files under its explicit workspace fence.
  const editedFiles = await editInYourWorkspace(files);
  const edited = await packEditedPortableBundle(manifest, editedFiles, portableCodec);
  // Review an explicit import plan before submitting edited.
} finally {
  client.close();
}
```

The unpacked manifest keeps descriptors, relationships and stable foreign
identities separate from file bytes. Repacking explicitly edited bytes recalculates
`contentHash`; `provenance.sourceHash` retains the original export claim. Hashes
are checked by SDK codecs and again by server admission. Editing content creates
new revisions, never an in-place update to cited history. Changing schema metadata
still requires a valid exact binding and metadata hash; use the existing metadata
operations for ordinary classification rather than guessing those identities.

An extraction sidecar is not a complete original-file bundle. Exported foreign
claims do not become authenticated authorship merely because their hashes match.

## Prepare and review an import

Import is an authenticated write through `information.interchange.import`.
Its request carries `scope`, a stable UUID `requestId` and a `plan`:

| Plan member | Meaning |
| --- | --- |
| `bundle` | The validated original or explicitly edited bundle |
| `destinations` | One choice per resource receiving new revisions: `resource`, logical `name`, `mode: "create"` or `mode: "revise"`; revise requires exact `expectedRevision` |
| `types` | Explicit `{foreignVersion, localVersion}` mappings for newly admitted typed revisions |
| `reuse` | Explicit `{foreignRevision, localRevision}` mappings for unchanged existing dependencies |
| `restoreRelationships` | Explicit boolean choosing restoration or omission |

For new documents, choose `create`. Existing names are refused instead of silently
appended to. For an edit, choose `revise` against the selected local head. Stale
heads refuse the whole import. Selected foreign history is admitted in ordinal
order with fresh local revision UUIDs and locally allocated ordinals.

For a one-file edit, reuse unchanged dependency/history revisions explicitly.
The server requires readable local revisions with identical bytes, media and
schema/metadata bindings. A reused history prefix must belong to the explicit
destination resource. This admits one edited head without duplicating unchanged
history. Fully reused resources need no destination entry. Reuse confers read
access only; it cannot grant ownership or membership.

Typed imports require an assignable active local version with the same immutable
definition hash, including presentation/profile semantics. A manager may copy the
exported schema and presentation through `types.version.create` and `types.activate`,
then provide the resulting local version UUID. No automatic type creation,
activation, slug matching or Personal/project lookup occurs. Unused mappings are
refused. A matching imported source name never overrides this explicit choice.

Restored links map to local resource UUIDs; pins map to exact local revisions.
Sources must be imported resources, and navigation writes must be enabled.
Both information switches default on within existing authority; see the
[configuration reference](server-administration.md#information-feature-switches)
for operator suspension, environment overrides and retained read behavior.
A pinned target must retain its original exported bytes. If that file was edited,
omit restoration or include the unchanged pinned revision; a pin cannot silently
change meaning during import.
Choose omission when reusing relationship sources. Required dependencies remain
retained access constraints even when ordinary links are omitted or later removed.

In Library, choose an import-plan JSON file, review the displayed destinations,
revision counts and relationship choice, then confirm **Import reviewed plan**.
Malformed plans are refused before submission. Changing connection/scope requires
review again. The request ID remains stable across a refusal or uncertain response. Reopening
the sources panel in the same Library window preserves the reviewed draft. Before submission Library opens a native save dialog for
an import recovery JSON file containing the original request ID, scope and exact
plan. The import is submitted only after the complete file is written and synced;
canceling the save or a write failure prevents submission. Keep that file until
the outcome is known. After closing the Library window or restarting the app, select the
original connection/account/scope, review the recovery file through the same file
picker, and explicitly confirm recovery. Loading a recovery file never submits
work automatically. Recover the same request rather than reviewing the original
plain plan as a new operation.

## What is retained

Import validates the complete plan before effects and rechecks scope, type,
ownership and expected-head authority inside the committing transaction. Existing
information write gates, hooks and receipts govern the operation. Admissions,
relationships, dependency edges and imported-origin records commit atomically.
A late failure rolls back the document/graph effects.

The authenticated importer owns and revises new local records. Foreign owners,
producer/log identities, input/evidence claims and original timestamps remain
foreign provenance. A report is imported as a source, not a locally authenticated
report or minted citation. `interchange.origin` reads that retained origin only
while the local revision is readable. Re-export retains a bounded original-origin
reference rather than recursively nesting every bundle. Deleting a revision also
redacts its duplicate imported metadata and evidence claims.

Import follows `plowshare.information.content-types-enabled`; disabling authored
writes also disables import, while retained reads/exports remain available.

New imports are copies governed by the explicitly chosen destination's policies.
Foreign memberships or policy claims cannot establish local access or prove that
redistribution is authorised. Reused local revisions keep their existing access
constraints; revision replacement cannot discard those dependencies.

No foreign membership, sharing, policy, grants, processing authorization, model
projection, allowance or lifecycle state is restored. Imported revisions use
`explicit_only`, spend zero processing allowance at admission and remain
unprocessed. Use existing allowance and selected-stage processing operations when
processing is wanted. Existing local policies and retained dependencies still
apply. Service credentials require their named project and `includeShared:false`;
they cannot route work into Personal space.

`origin.manifestHash` is a versioned server fingerprint of the validated bundle,
using verified content/schema/metadata hashes rather than serializer key order.
It is an audit identity, not a signature or the checksum of a pretty-printed file.

This is bounded interchange, not backup/restore or continuous synchronization.
Copies outside a server cannot be remotely revoked. Access is enforced when
exporting, admitting, resolving links and reading retained local information.
