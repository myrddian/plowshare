# Repair a retained report checkpoint

Status accepted for implementation.

A failed scripted report can retain the paid analyst and reviewer answers
while its pinned parser rejects the review. Ordinary Resume consumes the
same receipt under the same script. Starting another run through the SDK
loses the original checkpoint and repeats completed work. An SDK client also
cannot install a source transition in the durable command journal.

The platform provides an owner-only operation that adopts a reviewed,
already installed replacement at a completed delegate checkpoint. It
requires the same source origin and manifest, the existing operation
authority, and both source hashes. It previews the next command without
dispatching it. The claim records the original source and pins the
replacement atomically. The completed prefix remains immutable.

The continuation has a report-specific effect fence. It allows monotonic
changes to existing locked stages, one draft over the exact evidence
revisions already read, and finish after a positive draft receipt. The
ordinary tool authority and hooks still apply. Effective hook arguments
remain subject to the fence. The same boundary survives ordinary recovery
and process restart. A distinct wire operation prevents old servers from
interpreting repair as an ordinary Resume.

Tests cover the persisted source and receipt transaction, repeated and
stale requests, preservation of completed results, and refusal of
delegation, scans, substituted inputs, and a second draft attempt.
