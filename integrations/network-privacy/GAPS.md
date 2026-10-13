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

The [separately authorized platform fix](../../docs/decisions/0015-account-scoped-orchestration-receipts.md)
resolves this gap without changing the public `orchestration.receipt` request.
The server resolves the authenticated account and request UUID to the retained
run's actual project, then checks current project/token read authority. It retains
account ownership and information visibility checks, and refuses global work for
service tokens. All SDK languages use the existing UUID-only receipt read.

Servers built before this fix refuse service receipt reads before reaching the
account-owned receipt. Upgrade the server and inspect current project/evidence
permissions before recovering a pending intent. The separate opaque `orc_…` run
ID is not the start request UUID; the dashboard preserves both identities.

An unknown or unusable start reply must keep its manual intent pending until the
exact account-owned receipt is read. Listing a similar run or matching creation
time does not prove admission. The collector never resubmits the start, replaces
its request UUID, borrows a human credential or confirms an intent from those
hints. Receipt inspection recovers existing admission without launching or
resuming work. Missing receipts keep Check retained work fenced.

## Single-pass Application service provisioning

Manifest execution-account inheritance removes repeated principal fields, but
current public provisioning still needs the project to exist before a scoped
service token can be created. The bootstrap helper therefore still performs two
reviewed deployments and retains both receipts. Packaged schedule ownership also
remains with its existing owner. The [Application identity lifecycle proposal](../../docs/decisions/0017-application-owned-service-provisioning.md)
describes the server-owned reservation, authorization and recovery work needed for
single-pass deployment. No account is silently minted or adopted by this integration.

## Zero-call native definition metadata

Native `privacy_tick` executes only deterministic todo/finish commands, as verified
by its sandbox tests. Its manifest still declares a model and a positive one-call
allowance because the current orchestration loader shares the positive model-call
validation with agent definitions. Setting it to zero is refused; it is not a
supported way to enforce a native-only definition today. A general native-only
metadata contract would remove the unnecessary model binding/allowance. This
Application does not request inference from its tick script.

## Schedule control for service-owned Applications

Resolved in source by [ADR 0020](../../docs/decisions/0020-application-schedule-operational-authority.md),
a separately authorized general platform fix. The server now admits the explicit
manifest execution account with current effective Contributor access, or a project
Manager, for operational control of that Application's schedules. Historical
registrar ownership is preserved. The worker gains no definition/deployment or
permission-management authority. The dashboard uses its scoped service connection
without requesting a human password. Deploy the server fix before updating it;
older servers still apply the registered-owner/Manager restriction.
