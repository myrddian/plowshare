# ADR 0068: Readability is decided per document, with cross-boundary content enforced at write time

Status: accepted. This records the user's decisions, including owner ejection of restricting revisions through the same staged archive and export as conversations. Implementation has not started.

## Context

A reader asks for a document, but the system checks each revision separately, and on every read.
`knowledge_revision_readable` (V229) and its Java mirror (`KnowledgeReadability`) evaluate, for each
revision:

- the document's home scope, root policy and navigation policy, which are the same for every revision
  of that document;
- the revision's own `availability`, `excluded` and `processing_policy` columns on
  `information_revisions`;
- the revision's whole dependency closure (`knowledge_dependency_revisions`): every revision it was
  derived from, transitively, up to 1,024 nodes, each checked the same way.

The closure walk exists so that a revision derived from another document cannot disclose that
document's content to a reader who may not read it. It is the reason a revision of a readable
document can still be unreadable. It is also the most expensive part of every read.

Before slice 1 this ran as a per-row SQL predicate at about 270–290 ms per targeted check, and about
195 ms per single-document read on PROD. Slice 1 caches document and revision facts and walks the
closure in Java. The walk itself, and the per-revision state checks, still run on every read.

The current rules are also inconsistent with how the product describes them.
`availability(context, revision, state)` says it changes "a document's availability", but it updates a
single revision.

## Decision

### 1. Visibility state belongs to the document

Hidden, excluded and withdrawn are properties of a document, not of individual revisions. Changing
them changes the state of the whole document. A reader never sees some revisions of a document as
available and others as withdrawn or excluded. Readability no longer has a per-revision branch for
these states.

### 2. Cross-boundary content is enforced when it is written

A document's access matches its most restrictive revision. Each document carries a **required-scope
set**: its own home scope, plus the home scopes of everything any of its revisions was derived from,
transitively. It also carries the most restrictive policy flags among them (`private_only`, the
privacy principal, contained-scope membership, and whether every dependency is public).

A reader may read a document when:

1. the document is available;
2. the reader may read every scope in its required-scope set, decided against the authority snapshot;
3. the reader satisfies the document's policy flags.

Revisions of a readable document are readable. No per-revision check and no closure walk runs at read
time.

The set is maintained on writes, which are rare, instead of being recomputed on reads, which are
frequent:

- **A revision gains inputs.** Its document's set gains the inputs' required scopes and flags.
- **An input's home scope, root policy or availability changes.** The change propagates to every
  document that depends on it, through the reverse edges in `information_inputs` and
  `information_reference_obligations`.
- **Bounded propagation.** A change whose propagation exceeds a fixed bound marks the affected
  documents as **pending recompute**. A pending document is answered by the current closure rule
  until it is recomputed, so a large change can never produce a too-permissive answer.
- **Invalidation.** A change to a document's set moves that document's item version
  (`resource:<id>`), so cached reads of it are invalidated individually, as in
  [item-level invalidation](../query-efficiency-item-invalidation-design.md).

Authority changes do not touch the set. Revoking a grant changes which scopes a reader may read, not
which scopes a document requires.

The alternative of removing a revision automatically when its parent changes was rejected. It deletes
content to preserve access, where tightening the document's access preserves both. Removal is the
owner's choice instead (decision 3).

### 3. Owners see which revision restricts a document, and may eject it

A document's owner can see why it is restricted: for each scope in its required-scope set that is not
its own home scope, the revisions that brought it in. The input itself is named only if the owner may
read it now; otherwise the owner sees the revision and the scope, not the input's identity or content.

The owner may **eject** a restricting revision from the document's revision log. Ejection reuses the
staged retention path conversations already have (`Retention`, `ConversationLifecycle`,
`PayloadExport`): archive first, export, and only then delete the bytes. Nothing is deleted in the
step that changes access.

1. **Eject (immediate, access only).** A write requiring the owner's management authority over the
   document, checked in the write transaction and recorded with who ejected it and when. In that
   transaction:
   - the revision leaves what the document serves, so readers never see it again; if it was current,
     the newest remaining revision becomes current;
   - the document's required-scope set and flags are **recomputed** from its remaining revisions. A set
     only grows incrementally; shrinking always takes a full recompute in the same transaction, so a
     document is never briefly less restricted than its remaining revisions require;
   - the document's item version moves, so cached reads are invalidated individually.
2. **Archived (held, restorable).** The revision keeps its bytes, extraction and evidence for at least
   one retention interval. The owner may restore it during that window; restoring adds its scopes back
   to the set, which is an ordinary incremental growth.
3. **Exported.** A later retention sweep, not the eject call, writes the revision to the export
   destination as raw data in the system's own interchange format,
   `plowshare-information-bundle/1` ([ADR 0029](0029-portable-information-interchange.md)): original
   bytes, SHA-256 hashes, schema bindings, owner-authored tags, metadata and provenance, with
   relationships only where both endpoints are included. That bundle is the main export and archival
   format for information, so an archived revision can be read without Plowshare and re-imported
   through `information.interchange.import`. It is never reduced to extracted text. A revision whose
   bundle would exceed the format's bounds is not exported partially; it stays archived and the
   sweep reports it, so its bytes are never deleted without a complete export.
4. **Deleted.** Only after the export is durable does the sweep delete the bytes, extraction,
   evidence quotes and projections, leaving a restricted tombstone, as explicit revision deletion
   does today.

Documents derived from the ejected revision are not changed at any stage: they still carry its
required scopes, because their content was built from it, matching today's "dependencies remain
restrictive".

Ejection is the only way a document becomes less restricted without its inputs changing.

**Same retention policy as conversations.** Revisions use the conversation retention settings and
trigger, not settings of their own:

- the export lands in the same destination: the project's `exports/` directory under the data
  layout, or `plowshare.conversations.retention.export-directory` when set (`ExportDirectories`);
- the same sweep, `POST /v1/retention/sweep`, moves revisions through the stages. There is no
  scheduler and no interval key; the operator decides when sweeps run;
- the restorable window is the same: a sweep exports and deletes only what an earlier sweep found
  ejected, so every ejected revision stays restorable for at least one sweep interval;
- the operator caveat is the same: if the server's disk is not backed up, neither is the export.

## Consequences

- **Reads become cheap and bounded.** A read checks one document's availability and a short scope set
  against memory. The 1,024-node cap and the closure walk leave the read path.
- **Stricter, never looser.** If any revision of a document has an input the reader may not read, the
  reader may not read the document at all. Today the reader could still read that document's other
  revisions. Some readers will see fewer documents. No reader sees more.
- **Writes do more.** Adding an input and changing a document's scope or policy update the
  required-scope sets of dependent documents, within the propagation bound.
- **Owners can undo a restriction.** A document hidden by one cross-boundary revision is recovered by
  ejecting that revision, not by changing permissions. The revision's content is archived, exported
  and only then deleted, so the decision is recoverable until the export completes.
- **The SQL rules change.** `knowledge_revision_readable`, `knowledge_dependencies_readable`,
  `knowledge_readable_candidates` and their Java mirror are replaced by the document rule, or reduced
  to it. Their current form remains the oracle until the switch is verified.

## Migration and verification

1. **New Flyway migration** (never editing shipped ones):
   - add document-level availability and exclusion, the required-scope set with its flags and pending
     marker, and a per-revision retention stage (`active`, `ejected`, `exported`, `deleted`);
   - backfill from current data. Each document takes the most restrictive state of its revisions;
     revisions already deleted map to the `deleted` stage with their tombstones.
2. **Dry-run report before switching.** Count, per document, the revisions whose readability would
   change for each reader class, and list documents that become hidden because of a cross-boundary
   input. Review it before the rule switches.
3. **Differential test against the current rule:**
   - **never more permissive:** for every actor, selection, ceiling and revision in generated fixtures,
     the new answer is readable only where the current answer is readable;
   - **equal where nothing crosses a boundary:** documents with no cross-boundary input give exactly
     the current answers;
   - **write paths:** propagation and the pending marker are tested with real triggers or
     repositories, including a change past the propagation bound;
   - **ejection:** ejecting the only restricting revision restores the document's own scope set;
     ejecting one of two revisions that require the same scope keeps it; a non-owner is refused; a
     reader without the input's scope never learns the input's identity from the reason; restoring
     within the window re-adds the scopes; a sweep never deletes bytes whose export is not durable;
     an exported bundle re-imports with identical original bytes and hashes; an oversized revision
     stays archived.
4. **Switch reads, keep the oracle.** Single-document reads, lists and log visibility move to the
   document rule. The closure rule stays only for pending documents and as the test oracle.

## Open questions

1. **Processing policy and typed content.** `explicit_only` and typed content hide revisions from
   legacy readers. Are these document-level too, or can one document mix typed and untyped revisions?
2. **Public and global-read documents.** Today a document is generally readable only if every
   dependency is public. Confirm that the required-scope set expresses this as a flag, and how
   contained scopes and global read combine with it.

Showing the reason and the export destination were open questions; both are now part of decision 3.
