# Decision 0032: Manual document controls have command authority

Status: separately authorized platform regression fix, 19 October 2026.

## General platform need and authorization

Owner-authored tags, tag groups, publication and availability controls are public
document operations used by every client. They need their existing stage hooks
without pretending to hold a background processing lease. Commit
`a3eb7710c1fe5e0c8bd8ecccb6527596d08b499b` added a processing-lease check when
creating the shared SYSTEM hook log. A fresh manual tag command has no such
lease and fails with `InformationLifecycle.StaleLease` before its effect.

The user explicitly approved fixing this server failure separately from the
external agile Application. This change belongs to the document-control
lifecycle; no ticket-specific behavior or integration dependency enters core.

## Alternatives and decision

The SDK can admit and revise typed documents but cannot repair a server control
gate. Processing a source merely to initialize its log adds unrelated work and
does not provide command authority. Alternate tag storage loses native facet
semantics. Weakening the worker lease check would widen background authority.

Manual gates therefore receive a typed control context with a server-owned live
validity fence. Its retained command attempt must match account, revision,
token, active state and expiry; the authenticated caller's current knowledge
mutation authority and exact resource/home are rechecked. Post gates can check
an applied command. Changes such as rebuild may advance generation themselves;
their command token, rather than a fabricated queue lease, owns this lifetime.

Each manual command creates an independent SYSTEM hook log and hook snapshot.
It retains the original caller's custody and input selection, owns a fresh
zero-call allowance and uses the existing model-only `DOCUMENT_PREPARATION` purpose
with a distinct `control:` input binding. No new SYSTEM purpose or model, tool or
embedding capability is added. It is never pinned as the background processing log.
Manual controls authorize no inference and never copy, reset or acquire the
revision's processing allowance. That identity already belongs to the pipeline
conversation; reusing it for a control log violates conversation ownership.
Background gates retain their existing source/generation/lease-token checks and
continue independently of former creator permissions. Existing processing logs
and accounting remain immutable.

## Lifecycle and compatibility

The owning command still decides when its effect and receipt commit. Pre/post
denials, safety reductions, uncertain delivery and explicit request recovery keep
their existing semantics. Authority loss or an expired/stolen/completed command
attempt stops control hooks. Scope and resource checks remain in typed services
and specialist repositories. No public protocol, SDK, schema or migration changes
are required. Existing in-process gate fixtures keep their callback methods;
the control adapter checks its fence before delegating.

Verification covers fresh manual logs, isolation from worker log pinning, caller
authority loss and exact command-attempt persistence. Existing processing lease
and independent SYSTEM authority tests remain applicable. Live acceptance must
exercise the public SDK operation and restart persistence separately.
