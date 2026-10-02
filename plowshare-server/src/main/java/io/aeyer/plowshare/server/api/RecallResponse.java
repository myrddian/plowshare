package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.protocol.Memory;
import java.util.List;

/**
 * The body of {@code POST /v1/memories/recall}'s response.
 *
 * <p>Echoes the question and the limit actually applied back alongside the
 * hits, following Anchor's {@code RetrieveResponse} — a caller logging the
 * result then does not have to hold the request next to it to know what
 * produced it.
 *
 * @param question what was asked, echoed back
 * @param limit the limit actually applied
 * @param memories the hits, nearest first, project tier ahead of global
 * @param unsearchable how many live memories in the tiers this recall drew on
 *     have no embedding, and so were skipped by the vector query whatever the
 *     question was. <b>This is the field that stops an empty answer being a
 *     lie.</b> Without it, an archive holding one unembedded memory answers
 *     {@code {"memories": []}} — indistinguishable from an empty archive, and
 *     an agent told the archive is empty stops asking and writes back what was
 *     already there. Zero means the answer is complete.
 */
public record RecallResponse(
        String question, int limit, List<Memory> memories, int unsearchable) {}
