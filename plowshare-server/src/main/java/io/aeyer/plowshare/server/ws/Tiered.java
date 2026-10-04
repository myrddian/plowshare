package io.aeyer.plowshare.server.ws;

/**
 * The one field a request names its tier with, under the name its endpoint's query string already
 * used.
 *
 * <h2>Why a record exists at all for one optional string</h2>
 *
 * <p>{@link Paged}'s argument, which applies whole: {@link Payloads#as} binds the request record an
 * endpoint already binds, and a {@code GET} — or, here, a {@code POST} whose whole input is a query
 * parameter — has none. Binding through {@link Payloads#as} hands the value to Jackson, which
 * coerces it the same way the HTTP surface's {@code @RequestParam String} does, rather than leaving
 * each handler to decide for itself what {@code payload.get("project")} being an {@code Object}
 * means.
 *
 * <h2>Shared rather than declared twice</h2>
 *
 * <p>{@code memory.index}, {@code memory.reembed}, {@code proposal.list}, {@code
 * proposal.reconsider} and {@code agent.list} take exactly this field, and their endpoints take
 * exactly this query parameter — one shape, so one record, on {@link Paged}'s precedent. What each
 * does with it is a {@code requests} type's: {@link
 * io.aeyer.plowshare.server.requests.RequestedHome} for the four that read the archive, {@link
 * io.aeyer.plowshare.server.requests.RequestedProjectId#forListing} for {@code agent.list}, which
 * resolves a name to an id rather than to a home. Both carry the same reading of the two absent-ish
 * values — null is the global tier and a blank is refused rather than folded into it — which is why
 * one record spans them.
 *
 * <p><b>{@code agent.list} declared its own copy of this record and no longer does.</b> That
 * handler was written at Task 3, two tasks before this type existed, and a second one-field {@code
 * project} record is exactly the third copy this type was extracted to prevent.
 *
 * @param project the tier to answer from, or null for {@link
 *     io.aeyer.plowshare.protocol.Home#global()} — for {@code agent.list}, for the global {@code
 *     agents/} and {@code bots/} alone. A present-but-blank value is refused rather than read as
 *     global, which is the rule {@code Home.of} carries and neither surface may reinvent
 */
public record Tiered(String project) {}
