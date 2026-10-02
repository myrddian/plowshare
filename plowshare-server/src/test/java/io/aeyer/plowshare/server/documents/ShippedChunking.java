package io.aeyer.plowshare.server.documents;

import io.aeyer.plowshare.server.llm.tokens.RatioTokenizer;

/**
 * The chunk bounds {@code application.yml} ships, for fixtures that derive a
 * document and are not about chunking.
 *
 * <p>One spelling of them, so a fixture cannot drift from the shipped numbers
 * by restating them. {@link ChunkerTest} is about chunking and builds its own.
 */
final class ShippedChunking {

    /** {@code plowshare.documents.chunk-target-tokens}. */
    static final int TARGET = 400;

    /** {@code plowshare.llm.embedding-max-input-tokens}. */
    static final int MAX = 1536;

    static final Chunking SHIPPED = new Chunking(
            new RatioTokenizer(RatioTokenizer.DEFAULT_CHARACTERS_PER_TOKEN), TARGET, MAX);

    private ShippedChunking() {
    }
}
