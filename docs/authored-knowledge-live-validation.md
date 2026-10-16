# Authored knowledge: deployed workflow validation

This is an operational validation record, not a deployment instruction. Credentials,
server addresses, fixture contents and runtime identifiers remain outside source.

## Result: 17 October 2026

The deployed server identified public source commit
`a8a0f8b5256c694c527237748b83ac354eda3f00`. Validation used the current Node SDK,
a dedicated Application service identity, an explicit named project with
`includeShared:false`, and synthetic information. A separate named project provided
an existing document outside the service identity's authority. No Personal writes
or direct database access were used. The server was not restarted or reconfigured.

| Check | Observed result |
| --- | --- |
| Application deployment and service credential | Dedicated Application and contributor identity admitted through public operations |
| Ingestion | Real extraction, derivation, embedding, summarisation and summary embedding reached ready |
| Schema administration | Manager created and activated an exact project-scoped contract |
| Invalid classification | Required metadata failure refused without creating a revision |
| Agent classification | Metadata-only revision preserved body hashes, reused all five prepared projections, authorised no stages and spent zero processing allowance |
| Discovery | Tag and exact type intersection returned the current classified head |
| Consumption | Agent read retained source windows and recorded an exact Unicode quote with returned UTF-16 offsets |
| Provenance | Report retained the original runbook, classified revision and service source as inputs; omitted an unread decoy; cited the retained evidence and owning conversation |
| Scope boundary | Personal, Shared, includeShared and ungranted-project selections refused; a foreign revision remained unavailable even when addressed through the granted project |
| Portable export | Readable hash and original bytes verified; report bundle included required input closure and evidence |
| Edited import | Explicit destination and type mapping admitted a new head; prior revision remained readable; BOM and CRLF survived byte-for-byte |
| Import processing | New imported revisions used explicit_only, had no attempted processing stages and spent zero processing allowance |
| Recovery | A fresh connection recovered the same committed import result using the retained exact request; no additional revision was created |
| Invalid/stale imports | SDK rejected mismatched bytes/hash; server refused a stale expected head and preserved current selection |
| Live dependency access | Withdrawing a synthetic cited input closed report, evidence and export access; restoring the input restored report access |
| Credential cleanup | Temporary validation token revoked; a fresh connection using it was denied |

An initial real-model run confused schema-version and document-revision arguments
and was cancelled. After reconciliation confirmed no classification had landed,
a second bounded run supplied exact initial tool payloads and completed with
`ANSWERED` in nine model calls. That proves the guided journey, not reliable
unprompted tool selection. Its final prose also labelled the evidence identifier
imprecisely; the validation used retained tool results and records as authority.
Ordinary agent reports retained their producer conversation; their optional
orchestration definition hash was absent, as the current implementation specifies.
Exclusion was checked separately: it changes discovery, not retained read access;
withdrawal was used for the unavailable-input assertions.

## Remaining boundary

The deployed capability reported `navigation.writesEnabled:false`.
[The rollout setting](authored-navigation.md#enable-writes) was left unchanged while
the operator considers its policy consequences. Relationship creation/removal,
live versus pinned relationship traversal, saved-view writes and relationship
restoration during import were **not validated on this deployment**. Prior source
and fixture coverage is not evidence that those deployed paths passed.

The rollout switch controls operation availability across the server. It does not
create relationships or grant document access. Reference visibility and propagation
remain separate policies. The default baseline hides unreadable references and
propagates required target restrictions to referring documents; incurred obligations
can survive unlinking. Turning writes off does not release those obligations.
Review and initialise the intended project policy before creating its first links.

## Regression found

`agent.define` wrote a definition into an ordinary project data tier even for an
Application, then failed to resolve the new name because Applications load their
source package. The mismatch was introduced by `0c2e57c7`, which moved Application
resolution to the registered root without adapting this write path.

The source fix checks the existing Application-resource interface before writing
and refuses with the owning deployment workflow. It preserves retained source
ownership rather than adding an out-of-band package editor. Mock-based regression
tests cover new files, overwrite attempts and unavailable Application boundaries;
existing ordinary/global definition behavior remains covered. The validation
agent was admitted successfully through the owning Application deployment API.

The regression fix is source work and is **not deployed** by this validation.
The original failed definition request may leave an inert file in the old data
tier; the fix prevents future writes and does not migrate such files automatically.

## Repeat safely

Configure the server origin, private operator credential reference, FileStore and
fresh named fixture project explicitly. Keep request UUIDs, exact submissions,
service secrets and results in a private durable record before sending effects.
Use public SDK operations and retain uncertain outcomes for reconciliation; never
mint a replacement request to repeat an uncertain mutation. Bound model calls and
turns, grant only the information tools, and distinguish model mistakes from
server failures. Revoke the validation credential when the run finishes. Keep
positive and negative checks separate, and report disabled features as untested.
