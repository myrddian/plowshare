package io.aeyer.plowshare.client.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.FileResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The adaptor's own claims: what it converts, what it holds, and when it stops
 * believing what it holds.
 *
 * <h2>Why most of this is a counting fake and not a PDF</h2>
 *
 * <p>A buffer's only claim is that it did <b>not</b> do the work the second time,
 * and no assertion about the returned text can see that — the same string comes
 * back either way. So the converter here counts its calls, and the fixture is
 * two bytes rather than a document. {@code ClientEnforcerTest} drives real PDFs
 * through the real read path; this file is about the machinery in front of it,
 * and using a real extractor here would make every one of these tests also a
 * test of PDFBox.
 */
class ConversionsTest {

    /**
     * A format whose signature is one byte and whose conversion is its own
     * argument reversed, counting every call it is actually made to do.
     *
     * <p>Reversed rather than echoed so that a test cannot pass by the text
     * having been carried through unconverted.
     */
    private static final class Counted implements Converter {

        private final AtomicInteger conversions = new AtomicInteger();
        private final String name;
        private final byte magic;

        Counted(String name, byte magic) {
            this.name = name;
            this.magic = magic;
        }

        @Override
        public String format() {
            return name;
        }

        @Override
        public boolean recognises(byte[] bytes) {
            return bytes.length > 0 && bytes[0] == magic;
        }

        @Override
        public String toText(byte[] bytes) {
            conversions.incrementAndGet();
            return new StringBuilder(new String(bytes, StandardCharsets.UTF_8))
                    .reverse().toString();
        }

        int done() {
            return conversions.get();
        }
    }

    private static byte[] mine(String tail) {
        return ("Z" + tail).getBytes(StandardCharsets.UTF_8);
    }

    // --- what this client says it can do -------------------------------------

    @Test
    void the_formats_this_build_declares_are_the_ones_it_has_a_converter_for() {
        // Pinned, so that adding a converter without deciding what to CALL the
        // format fails here rather than reaching a refusal a person reads. This
        // is also the list a presence would declare on the file channel's
        // upgrade -- see the class note on why it is not on the wire yet.
        assertEquals(List.of("PDF"), new Conversions().formats());
    }

    @Test
    void bytes_no_converter_claims_are_not_this_class_s_business() {
        Conversions conversions = new Conversions();

        // A PNG. Null and not a refusal: the caller goes on to do what it did
        // before this class existed, which is the strict decode that turns a
        // PNG away with the sentence it has always been turned away with.
        assertNull(conversions.of(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r'}));
    }

    @Test
    void an_empty_file_claims_nothing() {
        assertNull(new Conversions().of(new byte[0]));
    }

    @Test
    void the_first_converter_that_claims_the_bytes_is_the_one_that_runs() {
        Counted first = new Counted("first", (byte) 'Z');
        Counted second = new Counted("second", (byte) 'Z');
        Conversions conversions = new Conversions(List.of(first, second), 1024);

        assertEquals("first", conversions.of(mine("ab")).format());
        assertEquals(0, second.done(), "a second claimant on the same bytes is not consulted");
    }

    // --- the buffer ----------------------------------------------------------

    @Test
    void a_document_read_twice_is_converted_once() {
        Counted converter = new Counted("counted", (byte) 'Z');
        Conversions conversions = new Conversions(List.of(converter), 1024);

        Conversion first = conversions.of(mine("hello"));
        Conversion again = conversions.of(mine("hello"));

        assertEquals(first.text(), again.text());
        assertEquals(1, converter.done(),
                "the whole of what a buffer claims: the second window of a document does"
                        + " not convert it again");
    }

    @Test
    void two_paths_that_are_one_document_share_one_conversion() {
        Counted converter = new Counted("counted", (byte) 'Z');
        Conversions conversions = new Conversions(List.of(converter), 1024);

        // Two distinct arrays with identical content: the same report copied
        // into a second worktree, or reached through a hard link. The key is
        // what the file IS and not where it sits, so this is one conversion --
        // which a path-keyed buffer could not do and is the smaller half of why
        // the key is the content.
        conversions.of("Zthe same report".getBytes(StandardCharsets.UTF_8));
        conversions.of("Zthe same report".getBytes(StandardCharsets.UTF_8));

        assertEquals(1, converter.done());
    }

    @Test
    void a_document_edited_under_the_buffer_is_converted_again() {
        Counted converter = new Counted("counted", (byte) 'Z');
        Conversions conversions = new Conversions(List.of(converter), 1024);
        conversions.of(mine("first"));

        Conversion after = conversions.of(mine("second"));

        assertEquals(2, converter.done());
        assertEquals("dnocesZ", after.text());
    }

    @Test
    void the_key_is_the_hash_of_the_source_and_never_of_the_text() throws Exception {
        Counted converter = new Counted("counted", (byte) 'Z');
        Conversions conversions = new Conversions(List.of(converter), 1024);
        byte[] source = mine("a document");

        Conversion made = conversions.of(source);

        assertEquals(sha256(source), made.sourceSha256(),
                "TextExtraction's javadoc asks this seam for the SHA-256 of the ORIGINAL"
                        + " bytes, and it is the same number the buffer keys on -- computed"
                        + " once rather than twice");
        assertNotEquals(sha256(made.text().getBytes(StandardCharsets.UTF_8)),
                made.sourceSha256(),
                "a key over the extraction would change when the extractor changed and"
                        + " could never notice that the file had");
        assertTrue(conversions.holds(made.sourceSha256()));
    }

    @Test
    void a_document_that_could_not_be_converted_is_not_remembered_as_a_failure() {
        AtomicInteger attempts = new AtomicInteger();
        Converter always = new Converter() {
            @Override
            public String format() {
                return "brittle";
            }

            @Override
            public boolean recognises(byte[] bytes) {
                return bytes.length > 0 && bytes[0] == 'Z';
            }

            @Override
            public String toText(byte[] bytes) {
                attempts.incrementAndGet();
                throw new Failed(format(), FileResult.ENCRYPTED);
            }
        };
        Conversions conversions = new Conversions(List.of(always), 1024);

        assertThrows(Converter.Failed.class, () -> conversions.of(mine("locked")));
        assertThrows(Converter.Failed.class, () -> conversions.of(mine("locked")));

        assertEquals(2, attempts.get(),
                "a buffer of failures would be a document that stays broken after somebody"
                        + " fixed it, which is the staleness this key exists to prevent"
                        + " wearing its other face");
    }

    @Test
    void the_failure_names_the_format_so_a_refusal_can_say_the_client_reads_it() {
        Converter locked = new Converter() {
            @Override
            public String format() {
                return "PDF";
            }

            @Override
            public boolean recognises(byte[] bytes) {
                return true;
            }

            @Override
            public String toText(byte[] bytes) {
                throw new Failed(format(), FileResult.ENCRYPTED);
            }
        };

        Converter.Failed failed = assertThrows(Converter.Failed.class,
                () -> new Conversions(List.of(locked), 1024).of(mine("x")));

        assertEquals("PDF", failed.format());
        assertEquals(FileResult.ENCRYPTED, failed.reason(),
                "and why, as a reason the server words -- never this client's own sentence");
    }

    @Test
    void whatever_a_converter_calls_a_line_ending_a_caller_gets_a_newline() {
        Converter mixed = new Converter() {
            @Override
            public String format() {
                return "mixed";
            }

            @Override
            public boolean recognises(byte[] bytes) {
                return true;
            }

            @Override
            public String toText(byte[] bytes) {
                return "a\r\nb\rc\nd";
            }
        };

        Conversion made = new Conversions(List.of(mixed), 1024).of(mine("x"));

        assertEquals("a\nb\nc\nd", made.text(),
                "a caller's lines must not depend on which library, or which platform's"
                        + " default, produced them");
    }

    // --- eviction ------------------------------------------------------------

    @Test
    void the_least_recently_read_conversion_is_the_one_evicted() {
        Counted converter = new Counted("counted", (byte) 'Z');
        // Room for two ten-character conversions and not three.
        Conversions conversions = new Conversions(List.of(converter), 25);

        Conversion first = conversions.of(mine("aaaaaaaaa"));
        Conversion second = conversions.of(mine("bbbbbbbbb"));
        // Reading the first again makes the SECOND the oldest, which is the
        // whole difference between a least-recently-used buffer and a queue.
        conversions.of(mine("aaaaaaaaa"));
        Conversion third = conversions.of(mine("ccccccccc"));

        assertTrue(conversions.holds(first.sourceSha256()));
        assertTrue(conversions.holds(third.sourceSha256()));
        assertFalse(conversions.holds(second.sourceSha256()));
    }

    @Test
    void an_evicted_document_is_converted_again_when_it_is_asked_for_again() {
        Counted converter = new Counted("counted", (byte) 'Z');
        Conversions conversions = new Conversions(List.of(converter), 12);

        conversions.of(mine("aaaaaaaaa"));
        conversions.of(mine("bbbbbbbbb"));
        conversions.of(mine("aaaaaaaaa"));

        // The owner's own words about this buffer: "if its evicted - it has to
        // do that whole conversion again". A correct answer at a cost, and never
        // a stale one.
        assertEquals(3, converter.done());
    }

    @Test
    void a_conversion_too_large_for_the_buffer_is_served_and_not_held() {
        Counted converter = new Counted("counted", (byte) 'Z');
        Conversions conversions = new Conversions(List.of(converter), 8);

        Conversion outsized = conversions.of(mine("past the allowance"));

        assertEquals("ecnawolla eht tsapZ", outsized.text(),
                "it was converted and handed back in full, not truncated to what fits");
        assertFalse(conversions.holds(outsized.sourceSha256()),
                "holding it would evict everything else to make room for something the"
                        + " next call evicts again, which is a cache that makes this client"
                        + " slower than having none");
        assertEquals(0, conversions.heldChars());
    }

    @Test
    void a_buffer_with_no_room_at_all_still_serves_every_read() {
        Counted converter = new Counted("counted", (byte) 'Z');
        Conversions conversions = new Conversions(List.of(converter), 0);

        assertEquals("ollehZ", conversions.of(mine("hello")).text());
        assertEquals("ollehZ", conversions.of(mine("hello")).text());
        assertEquals(2, converter.done(), "every read converts, and every read is correct");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                .toLowerCase(Locale.ROOT);
    }
}
