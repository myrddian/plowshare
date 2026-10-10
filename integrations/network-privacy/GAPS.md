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
