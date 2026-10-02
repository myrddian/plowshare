package io.aeyer.plowshare.protocol;

import java.time.Instant;

/**
 * Where a memory came from: when it was formed, which agent formed it, and the
 * situation it was formed in.
 *
 * <p>{@code where} is prose, not an identifier — "proj/payments — during the
 * mTLS migration" rather than a path. A curator deciding whether a claim still
 * holds needs the circumstance, and a circumstance does not fit in a foreign
 * key.
 *
 * @param at when the memory was formed; always UTC, because {@link Instant} has
 *     no other option and Excalibur normalises to UTC on the way out
 * @param by the agent that formed it
 * @param where the situation it was formed in, in prose
 */
public record Provenance(Instant at, String by, String where) {}
