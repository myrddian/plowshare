package io.aeyer.plowshare.server.ws;

import java.util.List;

/**
 * The window a {@code conversation.trajectory} frame reads the log through.
 *
 * <p>{@link Paged}'s two fields plus where the reading starts and what it
 * reads, for {@link Paged}'s own reason: {@code conversation.trajectory} is the
 * one frame that reads after or before an ordinal, so its payload names a shape
 * {@link Paged} does not have, bound through {@link Payloads#as} the same way.
 *
 * @param offset how many entries to pass over, or null for the beginning
 * @param limit how many this page may hold, or null for as many as the server
 *     sends
 * @param after the highest ordinal already read, or null to read from the
 *     beginning
 * @param before read backwards, newest first, from below this ordinal, or null
 * @param tail true to read backwards from the log's end, or null
 * @param kinds the entry kinds to read, by wire name, or null for every kind
 * @param drawn true to leave out the answers that asked for tools — the ones a
 *     chat never draws — or null to read them
 */
public record LogWindow(Integer offset, Integer limit, Integer after, Integer before,
        Boolean tail, List<String> kinds, Boolean drawn) {
}
