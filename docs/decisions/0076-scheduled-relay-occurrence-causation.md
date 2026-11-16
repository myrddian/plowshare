# ADR 0076: Scheduled Relay occurrences are independent causation roots

Status: accepted. The user separately authorized fixing core Relay/scheduling and
recovering retained scan work without resending unknown effects on 2026-11-16.

## Problem and decision

The scheduler emitted independent `SCHEDULE_DUE` publications without causation
metadata. SDK `relay.publish` requires retained, known parent ancestry for every
derived effect, so a correctly authorized consumer could upload its result but
could not publish completion referencing that scheduled occurrence.

Every newly published occurrence now carries a depth-zero root with its existing
deterministic occurrence ID. This is a platform contract for all SDK consumers
of schedules, independent of Privacy Watch. No operation, DTO or client contract
changes, and no new authority is granted.

For retained occurrences, `ScheduledRelayOccurrences.legacyRoot` recognizes only
the scheduler's typed payload on its matching scope-derived topic, with the exact
canonical event identity, occurrence timestamp and absent correlation/parent
references. It applies only when causation metadata is absent. Explicit unknown
or exhausted metadata is never replaced. The SDK port uses this proof from its
already-authorized retained parent snapshot, without another database read.

## Alternatives and boundaries

SDK-only alternatives would omit the parent, create another independent request
or alter the retained journal. Those lose causation or could reset effect limits
and duplicate work. More grants cannot fix missing ancestry. A broad rule that
turns all missing metadata into roots would erase unknown histories. Updating
retained rows would change immutable receipts and complicate deduplication.

The compatibility check remains in the scheduler's package so other subsystems
do not reproduce its private topic or event-identity recipes. It performs no IO,
publishes nothing and does not access application-specific state.

## Lifecycle and recovery

Current project membership, exact parent EGRESS and destination INGRESS grants,
project identity rechecks and the configured effect-depth limit still apply.
The derived effect keeps the occurrence as root and consumes one hop. Publisher,
accounting, cancellation, retention, queue and cursor ownership are unchanged.

Recovery first reads receipts under their original identities. A collector scan
whose completion was explicitly refused remains `uploaded`: its existing evidence
and upload revision can finish publication under the original completion ID.
An unknown publication remains `publishing` and can settle only against a positive
matching retained receipt. Absence never permits a resend. Neither case requires
recollecting devices or reuploading evidence.

## Compatibility and verification

There is no schema migration or historical event rewrite. The event-ID encoding
is unchanged. Rolling the server back preserves stored receipts but reinstates
the refusal for older occurrences missing metadata; keep unresolved work fenced.

Mocked scheduler and port tests pin the old identity encoding, new root metadata,
legacy recognition in project/server scopes, malformed and derived inputs,
unknown/exhausted histories and current grants. Collector tests verify explicitly
refused completion recovery reuses one collection and upload, while lost replies
require read-only reconciliation and an absent receipt keeps the fence. Live
recovery must separately verify the original scan/revision and its single exact
completion publication. No PostgreSQL-specific behavior changes.
