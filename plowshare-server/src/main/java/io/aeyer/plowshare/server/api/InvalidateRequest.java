package io.aeyer.plowshare.server.api;

/**
 * The body of {@code POST /v1/memories/{id}/invalidate}.
 *
 * <p>Both fields are validated for blank, not just {@code null}, in {@link
 * MemoryController#invalidate} rather than in a compact constructor here: per
 * {@link io.aeyer.plowshare.protocol.Invalidation}'s own javadoc, "a tombstone
 * without a reason is just an absence, and an absence teaches nobody" — the
 * same is true of {@code by} without an account of who invalidated it, and an
 * empty string passes a {@code null} check while still being that absence.
 *
 * @param reason why the memory stopped being true, in prose
 * @param by the agent or human invalidating it
 */
public record InvalidateRequest(String reason, String by) {}
