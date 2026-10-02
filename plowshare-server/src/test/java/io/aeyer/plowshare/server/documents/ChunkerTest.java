package io.aeyer.plowshare.server.documents;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.llm.tokens.RatioTokenizer;
import io.aeyer.plowshare.server.llm.tokens.TokenCount;
import io.aeyer.plowshare.server.llm.tokens.Tokenizer;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The chunker, and above all its ceiling.
 *
 * <p>Anchor's {@code Chunker} is the class this one replaces, and the two
 * defects the survey found in it are both asserted here rather than described:
 * its overflow branch emits a chunk of <em>any</em> size, and its unit is a
 * whitespace word count whose own comment reasons about its error direction
 * with the sign inverted. {@link #no_chunk_is_ever_larger_than_the_ceiling} and
 * {@link #a_run_with_no_sentence_break_at_all_is_still_bounded} are what would
 * fail if either came back.
 */
class ChunkerTest {

    private static final Tokenizer TOKENIZER =
            new RatioTokenizer(RatioTokenizer.DEFAULT_CHARACTERS_PER_TOKEN);

    private static final int MAX = ShippedChunking.MAX;

    private static final int TARGET = ShippedChunking.TARGET;

    private static final Chunking SHIPPED = new Chunking(TOKENIZER, TARGET, MAX);

    private static int tokens(String text) {
        return TOKENIZER.count(text).tokens();
    }

    private static int utf8(String text) {
        return text.getBytes(UTF_8).length;
    }

    // --- the ordinary case ---------------------------------------------------

    @Test
    void a_paragraph_that_fits_is_one_chunk() {
        List<Chunker.Chunk> chunks = Chunker.chunk(
                "The retry budget is four. It has been four since the timeout change.", SHIPPED);

        assertEquals(1, chunks.size());
        assertEquals("The retry budget is four. It has been four since the timeout change.",
                chunks.get(0).text());
        assertFalse(chunks.get(0).splitMidSentence());
    }

    @Test
    void nothing_to_chunk_is_no_chunks() {
        assertEquals(List.of(), Chunker.chunk(null, SHIPPED));
        assertEquals(List.of(), Chunker.chunk("", SHIPPED));
        assertEquals(List.of(), Chunker.chunk("   \n  ", SHIPPED));
    }

    /**
     * Packing stops at a sentence boundary, and the whole paragraph survives.
     *
     * <p>Both halves matter. Anchor's chunker packs greedily so that a chunk is
     * never half a claim, and a chunker that met the target by dropping the
     * remainder would satisfy the size assertion alone.
     */
    @Test
    void sentences_are_packed_up_to_the_target_and_none_is_lost() {
        String sentence = "This sentence is exactly the sort of prose a paper is made of. ";
        String paragraph = sentence.repeat(60);

        List<Chunker.Chunk> chunks = Chunker.chunk(paragraph, SHIPPED);

        assertTrue(chunks.size() > 1, "60 sentences should not have fitted in one chunk");
        for (Chunker.Chunk chunk : chunks) {
            assertTrue(tokens(chunk.text()) <= TARGET,
                    "a chunk packed out of whole sentences ran past the target: "
                            + tokens(chunk.text()));
            assertFalse(chunk.splitMidSentence());
        }
        String rejoined = String.join(" ", chunks.stream().map(Chunker.Chunk::text).toList());
        assertEquals(paragraph.trim().replaceAll("\\s+", " "), rejoined.replaceAll("\\s+", " "));
    }

    // --- the ceiling ---------------------------------------------------------

    /**
     * The defect that costs a whole document.
     *
     * <p>Anchor's overflow branch is {@code chunks.add(new Chunk(sentence,
     * sentenceTokens))} with no bound at all, and {@code
     * EmbeddingService.embedAll} then sends every chunk of the document as one
     * HTTP request — so one re-flowed table fails the batch after the whole
     * summarisation cascade has been paid for.
     */
    @Test
    void no_chunk_is_ever_larger_than_the_ceiling() {
        String oneEnormousSentence = "word ".repeat(4000).trim();

        List<Chunker.Chunk> chunks = Chunker.chunk(oneEnormousSentence, SHIPPED);

        assertTrue(chunks.size() > 1, "an oversized sentence has to be broken up");
        for (Chunker.Chunk chunk : chunks) {
            assertTrue(tokens(chunk.text()) <= MAX,
                    "a chunk of " + tokens(chunk.text()) + " tokens is past the ceiling of " + MAX);
        }
    }

    /**
     * A PDF table, a reference list or an equation block re-flowed into one
     * line has no sentence break in it at all, and {@code BreakIterator}
     * returns it whole. That is the input the ceiling exists for.
     */
    @Test
    void a_run_with_no_sentence_break_at_all_is_still_bounded() {
        String table = "col".repeat(3000);

        List<Chunker.Chunk> chunks = Chunker.chunk(table, SHIPPED);

        assertTrue(chunks.size() > 1);
        for (Chunker.Chunk chunk : chunks) {
            assertTrue(tokens(chunk.text()) <= MAX);
            assertTrue(chunk.splitMidSentence(),
                    "a chunk the chunker cut rather than the prose must say so");
        }
        assertEquals(table, String.join("", chunks.stream().map(Chunker.Chunk::text).toList()));
    }

    /** A chunk whose boundaries are the chunker's and not the prose's says so,
     *  and a chunk whose boundaries are the prose's does not. That flag is the
     *  whole difference between a recorded cut and a silent truncation. */
    @Test
    void only_a_chunk_the_chunker_cut_is_flagged() {
        List<Chunker.Chunk> whole = Chunker.chunk("One. Two. Three.", SHIPPED);
        assertFalse(whole.get(0).splitMidSentence());

        List<Chunker.Chunk> cut = Chunker.chunk("x".repeat(8000), SHIPPED);
        assertTrue(cut.size() > 1, "8000 characters is past the ceiling and has to be cut");
        assertTrue(cut.stream().allMatch(Chunker.Chunk::splitMidSentence));
    }

    /**
     * The cut lands on a space when one is near enough to the ceiling, so an
     * ordinary long run is broken between words rather than through one.
     *
     * <p>Not a correctness property — the ceiling holds either way — but a
     * chunk ending mid-word embeds a token sequence that occurs nowhere in the
     * document.
     */
    @Test
    void an_oversized_run_is_cut_at_a_space_when_there_is_one() {
        List<Chunker.Chunk> chunks = Chunker.chunk("word ".repeat(2000).trim(), SHIPPED);

        assertTrue(chunks.size() > 1, "the run is past the ceiling, so this is about the cut");
        for (int i = 0; i < chunks.size() - 1; i++) {
            assertTrue(chunks.get(i).text().endsWith("word"),
                    "a cut landed inside a word: '" + tail(chunks.get(i).text()) + "'");
        }
    }

    // --- the unit ------------------------------------------------------------

    /**
     * The unit is whatever the configured {@link Tokenizer} counts, and never a
     * length this class works out for itself.
     *
     * <p>The tokenizer here charges ten tokens a character, which no shipped
     * one does, so a chunker that measured bytes or characters would pack this
     * paragraph into one chunk and cut nothing. Measured through the
     * interface, the twelve-character sentences each cost 120 and only one
     * fits under a target of 150.
     */
    @Test
    void the_bounds_are_counted_by_the_tokenizer_it_was_given() {
        Tokenizer expensive = new Tokenizer() {
            @Override
            public TokenCount count(String text) {
                return TokenCount.estimated(text.length() * 10, "ten a character, for a test");
            }

            @Override
            public String describe() {
                return "ten a character";
            }
        };

        List<Chunker.Chunk> chunks = Chunker.chunk(
                "One is here. Two is here. Six is here.", new Chunking(expensive, 150, 200));

        assertEquals(List.of("One is here.", "Two is here.", "Six is here."),
                chunks.stream().map(Chunker.Chunk::text).toList());
    }

    /**
     * A script with no spaces is measured rather than counted as one word.
     *
     * <p>A CJK line is one whitespace "word" and Anchor's estimator therefore
     * scores it 1 against a target of 300. Here it is measured by the
     * tokenizer, and the ceiling holds.
     */
    @Test
    void a_script_with_no_spaces_is_measured_rather_than_counted_as_one_word() {
        String cjk = "文書".repeat(4000);

        List<Chunker.Chunk> chunks = Chunker.chunk(cjk, SHIPPED);

        assertTrue(chunks.size() > 1,
                "a whitespace-word count would have made this one chunk of " + cjk.length()
                        + " characters");
        for (Chunker.Chunk chunk : chunks) {
            assertTrue(tokens(chunk.text()) <= MAX);
        }
    }

    /**
     * A cut never lands inside a character — including one outside the Basic
     * Multilingual Plane, which Java holds as two {@code char}s. Cutting at a
     * {@code char} index would split the pair; cutting by code point does not.
     */
    @Test
    void a_cut_never_lands_inside_a_character() {
        String surrogates = "𝄞".repeat(4000);

        List<Chunker.Chunk> chunks = Chunker.chunk(surrogates, SHIPPED);

        assertTrue(chunks.size() > 1);
        for (Chunker.Chunk chunk : chunks) {
            assertEquals(chunk.text(), new String(chunk.text().getBytes(UTF_8), UTF_8),
                    "a chunk did not survive a UTF-8 round trip, so a cut split a character");
            assertTrue(tokens(chunk.text()) <= MAX);
        }
        assertEquals(surrogates,
                String.join("", chunks.stream().map(Chunker.Chunk::text).toList()));
    }

    /** {@code bytes} is what the row stores as {@code byte_size}: the chunk's
     *  UTF-8 length, measured and not estimated. */
    @Test
    void the_reported_size_is_the_measured_size() {
        for (Chunker.Chunk chunk : Chunker.chunk("Alpha. Beta. Gamma. 文書.", SHIPPED)) {
            assertEquals(utf8(chunk.text()), chunk.bytes());
        }
    }

    // --- the bounds themselves -----------------------------------------------

    /**
     * A target above the ceiling is refused, and refused where the bounds are
     * made rather than discovered at the embed call.
     *
     * <p>{@code DocumentsConfig} refuses the same pair at boot. This is the
     * second half of that: no {@link Chunking} can hold a pair it could not
     * honour, whoever builds it.
     */
    @Test
    void a_target_above_the_ceiling_is_refused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new Chunking(TOKENIZER, 4096, 2048));
        assertTrue(refused.getMessage().contains("4096"));
        assertTrue(refused.getMessage().contains("2048"));
    }

    @Test
    void a_non_positive_bound_is_refused() {
        assertThrows(IllegalArgumentException.class, () -> new Chunking(TOKENIZER, 0, 2048));
        assertThrows(IllegalArgumentException.class, () -> new Chunking(TOKENIZER, 16, 0));
    }

    @Test
    void bounds_without_a_tokenizer_are_refused() {
        assertThrows(NullPointerException.class, () -> new Chunking(null, 400, 1536));
    }

    private static String tail(String text) {
        return text.length() <= 20 ? text : text.substring(text.length() - 20);
    }
}
