package io.aeyer.plowshare.server.ws;

/**
 * The two fields a paged read's payload carries, under the names its endpoint's
 * query string already used.
 *
 * <h2>Why a record exists at all for two optional numbers</h2>
 *
 * <p>{@link Payloads#as} binds the request record an endpoint already binds,
 * and a {@code GET} has none: its inputs are query parameters, which Spring
 * reads straight into method arguments. A handler for such an endpoint
 * therefore has to name that shape somewhere, and there are only two places it
 * can go — a record here, or a hand-read off the raw map.
 *
 * <p><b>A hand-read is the worse of the two, and not by a little.</b> {@code
 * payload.get("limit")} is an {@code Object} that is an {@code Integer} for
 * {@code {"limit": 2}} and a {@code String} for {@code {"limit": "2"}}, and
 * every handler that read one would be deciding for itself which of those a
 * client may send — fifty handlers, fifty answers. Binding through {@link
 * Payloads#as} hands both spellings to Jackson, which coerces them the same way
 * the HTTP surface's {@code @RequestParam Integer} already does, and refuses
 * what is neither as the {@link io.aeyer.plowshare.server.faults.CallerFault}
 * that class's javadoc describes. <b>This is the shape the breadth plan's other
 * tasks should copy</b> for a {@code GET}: a record naming the query
 * parameters, bound through {@link Payloads#as}, with the values it carries
 * handed to the same {@code requests} type the controller hands them to.
 *
 * <h2>Shared rather than declared twice</h2>
 *
 * <p>{@code conversation.chat} and {@code conversation.trajectory} take exactly
 * these two fields, and their endpoints take exactly these two query
 * parameters — one shape, so one record. What each does with them is {@link
 * io.aeyer.plowshare.server.requests.RequestedWindow}'s, which is where the
 * bounds and the cap live for both surfaces.
 *
 * @param offset how many entries to pass over, or null for the beginning
 * @param limit how many this page may hold, or null for as many as the server
 *     sends
 */
public record Paged(Integer offset, Integer limit) {
}
