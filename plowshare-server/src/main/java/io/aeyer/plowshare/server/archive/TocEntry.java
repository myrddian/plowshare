package io.aeyer.plowshare.server.archive;

/**
 * One line of the index: what an agent is shown when it asks what the archive knows, before it asks
 * for any of it.
 *
 * <p>No body, deliberately. The index is read whole on every survey, so a body here is a body in
 * every prompt — Excalibur's {@code toc.py} calls this the librarian's attention budget, and it is
 * the reason the index exists as a separate projection rather than as a list of memories.
 *
 * @param id the memory to ask for by id, if the summary looks relevant
 * @param summary the claim, stated so it stands alone
 * @param scope prose saying when this memory is worth recalling
 * @param unsearchable true when this memory has no embedding, so {@code Archive#recall} cannot
 *     reach it however the question is phrased. It is still {@code active}, still listed here and
 *     still readable by id — a write made while the embedding endpoint was down keeps the memory
 *     and loses only the vector. This flag is what makes that state <em>visible</em>: without it
 *     the archive answers "nothing is close to that question" while holding the answer, and an
 *     agent told that reasonably tries another question, forever. Named for the problem rather than
 *     for the column so that a client bound against an older server, which sends no such field,
 *     reads the benign {@code false} rather than reporting every memory as broken.
 */
public record TocEntry(String id, String summary, String scope, boolean unsearchable) {}
