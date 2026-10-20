# Mutation integrity and file-channel recovery

Status: Track A accepted; Track B proposed, with no protocol implementation.

## Context and scope

The source audit used commit `2cf4db4c2d72e9f99e43e343a38ff603278e8329`.
Implementation starts from public main `7544f92f9c85355e70c58566fbc7231234d0b573`.
This is an authorized platform improvement: ordinary file tools, private Messaging
and every Information tag editor need these guarantees independently of any
particular adapter. Excalibur remains a public SDK consumer. Refreshing on its
client cannot enforce concurrency against other clients and imports.

## Accepted Track A boundaries

Reuse filesystem providers, Information resource locks and command receipts,
Messaging transactions and firings, authenticated execution attribution, and Relay
delivery. No generic mutation executor or second durable runtime is introduced.
Broker acknowledgement is delivery progress; the execution owner establishes the
business outcome.

Ordinary file replacement stages complete bytes and requires atomic publication
where supported. Create-only remains exclusive creation. Edits keep their
no-create write path: ordinary rename could recreate a concurrently deleted file.
Participating filesystem mutations serialize within each process, across per-run
providers. External processes and commands do not participate. Replacement changes
inode identity, so hard-link aliases refuse. ACLs, ownership and extended metadata
require a native preserving copy: macOS `cp -Pp` or Linux GNU
`cp --no-dereference --preserve=all,xattr`, selected from PATH or explicitly through
`PLOWSHARE_FILE_COPY_PROGRAM`. Unsupported platforms, copy failures and metadata
diagnostics refuse; standard Node/Java copying is not an accepted metadata-stripping
fallback. [GNU's preservation implementation](https://github.com/coreutils/coreutils/blob/master/src/cp.c)
distinguishes requiring xattrs from merely requesting `all`; diagnostics also
refuse publication so a warning cannot silently weaken the replacement's security.
Atomic visibility, file flushing and directory durability are distinct. Neither
a hash recheck nor this process-local serialization provides external-writer CAS.

Messaging fingerprints retain original request selectors, exact body, flags,
lifetime and relative timeout. Identical retries reuse the existing message,
route and deadline; changed arguments conflict. Attributed identities include
run/turn/step ownership. Legacy records lacking verifiable request evidence remain
readable but cannot authorize blind resubmission or a mid-turn identity upgrade.
Restart recovery does not replay interrupted handlers.

Resource-owned `tagsVersion` advances for actual tag changes, including legacy
replacement and import. `information.tags.replace` requires the displayed version
and reuses existing authorization, hooks, command fingerprints, application
transaction and receipts. Content revisions and processing generations do not
version tags. The old unconditional operation remains compatible; clients never
silently downgrade a guarded write. Expected-version replacement detects lost
updates. Atomic add/remove is a possible later general operation for explicit
deltas; deriving a replacement delta from stale state is not conflict detection.
The existing tags hook stages remain unchanged. Post-control refusal can occur
after an applied checkpoint; it is not universal rollback.

## Track B proposal: durable file-channel evidence

Relay tools already have server-owned intent, provider execution journals,
fingerprint conflicts and read-only invocation reconciliation. Prefer that public
capability for work already modeled as an authorized external tool. It does not
currently provide receipts for ordinary session-bound FileProvider mutations.
Silently redirecting filesystem operations through tools would change authority
and workspace binding and is not part of Track A.

If separately approved, retain transport correlation and add a durable operation
UUID before dispatch. Bind it to authenticated actor, project/home, stable
provider identity and workspace generation, independent of socket/session IDs.
Fingerprint operation, original path selectors, workspace, purpose, create-only
flag, exact bytes and any preconditions. Conflicting reuse refuses before effects.

The filesystem provider owns an exclusive durable effect journal in explicitly
configured private storage outside model-writable roots. The server's existing
execution owner retains dispatch intent, binding and acknowledged evidence through
a specialist repository. This is evidence storage, not another scheduler. Reuse
ToolJournal durability and exclusivity contracts where compatible; do not pretend
its existing tool schema already represents filesystem operations.

Persist execution intent before effects. Duplicate execution requests return
retained state and never run again. A durable provider ownership fence prevents
concurrent processes sharing the journal. Lease expiry alone cannot fence a
filesystem syscall or authorize another execution. Reconnect can inspect only
the original provider/workspace binding; restart during execution becomes UNKNOWN.
A different root, replacement provider or missing journal supplies no success or
non-execution evidence.

Filesystem effects and receipt persistence are separate commits. A crash after
replacement but before the journal commit remains UNKNOWN. Staging markers and
hash observations may aid investigation; matching bytes do not prove causation.
Do not promise exactly-once effects, automatic rollback or automatic replay.

A proposed read-only status operation reports retained state/result evidence or
explicit absence of evidence. It never executes work. Missing/expired receipts
do not mean not executed. Cancellation before execution may establish rejection;
after execution starts it is best effort and may leave effects. Reconciliation
uses authoritative reads and operator review without changing historical unknown
outcomes into invented receipts.

Bound admission and retention. Preserve unresolved operations and deduplication
evidence for accepted identity lifetimes; reject retired identity epochs rather
than making old identities executable after cleanup. Status and dispatch recheck
current authority. A fingerprint grants no access. Negotiate protocol support
explicitly; older providers retain their current uncertain-outcome behavior.

Acceptance would require failure injection before intent, after intent, after
filesystem effects, after receipt commit and before response; conflicting and
concurrent duplicate identities; reconnect and provider restart; journal loss,
workspace rebinding, cancellation, retention and cross-account refusal. No file
channel frames, receipts or automatic recovery are added by this decision.
