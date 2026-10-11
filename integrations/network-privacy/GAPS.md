# Public capability gaps

These are platform follow-ups, not requirements for deploying the external
Network Privacy Watch integration. This Application uses only public SDK contracts.

## Scoped document chapter-outline reads

Plowshare stores a derived hierarchy of document, chapters, sections, paragraphs
and chunks. `document.detail` returns chapter/section identifiers, titles and
summaries. Its server frame resolves the caller's Information scope and reads the
hierarchy through the permission-scoped document store.

However, the Python `DocumentDetailRequest` exposes only `document`, and the
TypeScript operation payload/schema also rejects an added `scope`. An Application
service token requires an explicit project Information scope with
`includeShared:false`; the omitted scope defaults to a personal selection. The
public typed clients therefore cannot express this scoped hierarchy read.

A separately scoped platform change should align the existing `document.detail`
request contract and generated SDKs with server scope selection. It should verify
project membership, service-token ceilings and foreign-document refusal across the
affected clients. Document authorization must govern the entire hierarchy; this
requires no independent chapter permissions or chapter creation API.

Network Privacy Watch proceeds through `information.upload`, `information.revise`,
`information.list` and `information.read`, all of which already expose project
scope. It creates Markdown device profiles with readable sections and retains new
revisions on edits. It does not call internal services, use a raw socket or HTTP
fallback, or alter core/SDK contracts to obtain a chapter outline. Source processing
owns the derived structure; the dashboard reads complete bounded profile text.

Code references:

- [Scope enforcement](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationAccess.java)
- [Scoped document frames](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/DocumentFrames.java)
- [Python request contracts](../../sdk/python/src/plowshare/contracts.py)
- [TypeScript request validation](../../sdk/typescript/src/operations/payload-validation.ts)

## Scoped schedule runtime inspection

`schedule.list` and `schedule.files` expose no typed project selection, and service
tokens cannot call either operation. Application source reads also require direct
FileStore grants that the execution identity does not receive. The collector cannot
inspect next-fire time or registration through its existing project-scoped token.
It reports this read as unavailable, while continuing independent scoped agent,
context-projection and orchestration health reads. It does not interpret an
unavailable read as a missing schedule or poll using human credentials.

An administrator can inspect schedules through the CLI, and the dashboard's
explicit schedule editor uses a temporary administrator login. A project-scoped
runtime inspection contract is a separate platform follow-up.

## Project-scoped orchestration start receipts

`orchestration.start` admits project work with an account-owned UUID request
identity. Its response contains a separate opaque `orc_…` run ID. The public
`orchestration.receipt` request exposes only `requestId`; it has no project
selection. The current server cannot resolve a service token's project ceiling
from that receipt identity, so it refuses the read before reaching the retained
account-owned receipt.

The dashboard can retain a successful start response, but after an unknown or
unusable reply its manual intent must remain pending. Listing a similar run or
matching creation time does not prove which request was admitted. The collector
never resubmits the start, replaces its request UUID, borrows a human credential
or marks an intent confirmed from those hints. Check and resume remains fenced
if it cannot obtain the exact account-owned receipt.

A separate platform task should authorize this receipt read against the retained
request's project and caller, preserving foreign-account and foreign-project
refusal. A bounded public SDK receipt read must recover the existing admission
without launching another run. No server, shared contract or SDK changes are
included in this integration fix.
