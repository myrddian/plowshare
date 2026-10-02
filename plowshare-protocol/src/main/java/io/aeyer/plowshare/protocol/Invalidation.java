package io.aeyer.plowshare.protocol;

import java.time.Instant;

/**
 * The tombstone written when a memory stops being true.
 *
 * <p>{@code reason} is the field that earns this type its existence. An
 * invalidated memory is kept, never deleted, precisely so that the next agent
 * to encounter the same evidence finds the recorded reason the fact stopped
 * holding instead of rediscovering the stale fact and writing it back in. A
 * tombstone without a reason is just an absence, and an absence teaches nobody.
 *
 * @param at when the memory was invalidated
 * @param by the agent or human that invalidated it
 * @param reason why it stopped being true, in prose
 */
public record Invalidation(Instant at, String by, String reason) {}
