# Public capability gaps

These are platform follow-ups for affected capabilities of the external
Network Privacy Watch integration. This Application uses only public SDK contracts;
deploying the collector does not resolve a missing server capability.

## Scheduled completion requires known Relay causation

Automatic scan intake can discover the project's opaque `SCHEDULE_DUE` stream
through `relay.topics` and use its exact EGRESS grant. That does not establish
authority for a downstream effect. A server-emitted occurrence with absent or
unknown `causation` can be collected and its evidence uploaded, but the public
`relay.publish` completion is refused with "Relay parent has no remaining effect
budget". The collector retains the evidence in `uploaded` and stops for attention;
it must not replace the schedule parent, invent a fresh root, erase the receipt,
or resend an uncertain effect.

The general platform capability needed is known, immutable ancestry for a newly
published independent scheduled occurrence, so supported consumers can derive a
bounded descendant through the public SDK. The adapter cannot assign or repair
server-owned ancestry. This needs a separately authorized scheduling/Relay task,
not an SDK wrapper or an adapter-side bypass. Its design must distinguish fresh
occurrences from retained legacy events, preserve current authority and depth
limits, and define supported recovery for evidence already retained under a
refused parent. New publication ancestry alone does not repair that older receipt.

Until this capability is deployed and retained work is explicitly settled,
automatic collection is not verified end to end. A ready SDK connection or an
enabled schedule is insufficient proof.

## Application-owned background output

Resolved in source by [ADR 0072](../../docs/decisions/0072-application-inbox-ownership-and-attention.md).
The packaged manifest opts into an inspectable project inbox. Schedule receipts
and successful housekeeping stay there; failed work, human questions and the
configured investigation/assessment results reach current human RBAC Managers.
Collector stop alerts use the public `inbox.publish` contract with exact receipt
recovery. No direct SQL, second scheduler or automatic acknowledgement of the
person's inbox is used. Deploy compatible server, Application and collector
versions before relying on this behavior; existing inbox history is unchanged.

## Scoped document chapter-outline reads

Plowshare stores a derived hierarchy of document, chapters, sections, paragraphs
and chunks. `document.detail` returns chapter/section identifiers, titles and
summaries. Its server frame resolves the caller's Information scope and reads the
hierarchy through the permission-scoped document store.

Resolved in the current source contracts: Python `DocumentDetailRequest` and the
shared TypeScript operation graph both accept an information scope. Project reads
still require scoped READ and token ceilings. The collector explicitly chooses
`includeGlobal:false` for project-only discovery; other authenticated service
readers can include current GLOBAL_READ knowledge. An Application-owned credential
may resolve omission to its registered project through the existing server routing.

This is source-contract evidence, not a claim that an older deployed server has
been upgraded. No independent chapter permission or creation API is needed.

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

## Finalising agent-written reports with the service credential

Resolved by observation, not a platform change. On 12 November 2026 the
collector's project-scoped service credential finalised a device assessment
report written by its own `assess_network_device` run on a live server, using
`information.finalise`. A first attempt made seconds after the report was
written was refused with "a final report must complete processing first";
that is a processing wait, not an authority refusal, and the dashboard kept
the report in review without resending. Accepting again after processing
finished succeeded. The dashboard now reads the revision's processing steps
through `information.status` and offers Accept only once all five content
stages of its current generation are ready.

## Finding reports about one device

There is no catalogue query for "reports about device X". The collector finds
an assessment by its stable name and refuses to start a new run when the
catalogue is ambiguous, so it never creates a second report resource for the
same device.
