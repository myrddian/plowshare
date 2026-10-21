# Mutation integrity and file-channel recovery

Status: Track A accepted; Track B aspirational, with no approved implementation or delivery schedule.

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

## Aspirational Track B: durable file-channel evidence

Track B describes a possible future direction for reducing manual reconciliation
after client disconnects or provider restarts. Track A remains independently useful.
Pursuing Track B would require evidence that interrupted operations and recovery
costs justify the additional protocol, storage and lifecycle complexity, followed
by a separately scoped design and implementation approval. The approach below
records candidate mechanisms and safety constraints for that future discussion.

Relay tools already have server-owned intent, provider execution journals,
fingerprint conflicts and read-only invocation reconciliation. Prefer that public
capability for work already modeled as an authorized external tool. It does not
currently provide receipts for ordinary session-bound FileProvider mutations.
Redirecting filesystem operations through tools would change authority and workspace
binding and would require a separate design decision.

An eventual design could retain transport correlation and add a durable operation
UUID before dispatch. Its identity would need to bind the authenticated actor,
project/home, stable provider identity and workspace generation independently of
socket/session IDs. A request fingerprint would cover operation, original path
selectors, workspace, purpose, create-only flag, exact bytes and any preconditions.
Conflicting identity reuse would have to refuse before effects.

One candidate approach would give the filesystem provider an exclusive durable
effect journal in explicitly configured private storage outside model-writable
roots. The server's existing execution owner would retain dispatch intent, binding
and acknowledged evidence through a specialist repository. Existing execution
owners would continue to own scheduling. ToolJournal durability and exclusivity
contracts could be reused where compatible; its existing tool schema does not
already represent filesystem operations.

Such a journal would need to persist execution intent before effects. Duplicate
execution requests would return retained state without running again. Durable
provider ownership would need to prevent concurrent processes sharing the journal.
Lease expiry alone cannot fence a filesystem syscall or authorize another
execution. Reconciliation would be limited to the original provider/workspace
binding; restart during execution would leave the outcome UNKNOWN. A different
root, replacement provider or missing journal would supply no success or
non-execution evidence.

Filesystem effects and receipt persistence are separate commits. A crash after
replacement but before the journal commit would remain UNKNOWN. Staging markers
and hash observations might aid investigation; matching bytes do not prove
causation. Track B would offer retained evidence and duplicate suppression, with
no guarantee of exactly-once effects, automatic rollback or automatic replay.

A future read-only status operation could report retained state/result evidence
or explicit absence of evidence without executing work. Missing/expired receipts
would not mean not executed. Cancellation before execution might establish
rejection; after execution starts it would be best effort and could leave effects.
Reconciliation would need authoritative reads and operator review without changing
historical unknown outcomes into invented receipts.

Any eventual design would need bounded admission and retention. It would need to
preserve unresolved operations and deduplication evidence for accepted identity
lifetimes, and reject retired identity epochs so cleanup could not make old
identities executable again. Status and dispatch would recheck current authority;
a fingerprint grants no access. Protocol support would need explicit negotiation,
with older providers retaining their current uncertain-outcome behavior.

Before implementation could be accepted, validation would need failure injection
before intent, after intent, after filesystem effects, after receipt commit and
before response; conflicting and concurrent duplicate identities; reconnect and
provider restart; journal loss, workspace rebinding, cancellation, retention and
cross-account refusal. No file-channel frames, receipts or automatic recovery are
added by this aspirational direction.
