package io.aeyer.plowshare.protocol;

/** Validated server notifications. Dropped hints are reconciled through durable operations. */
public sealed interface ServerPush
    permits AccountEvent, JobEvent, JobDelta, ConversationGrowth, Usage.Updated, Usage.Closed {}
