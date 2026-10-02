package io.aeyer.plowshare.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The four formats both halves of the wire agree on, in the module that now owns
 * them.
 *
 * <h2>Why this file exists at all</h2>
 *
 * <p>{@link ImageFormat} was {@code io.aeyer.plowshare.server.images.ImageFormat}
 * and its behaviour was measured only through the things that used it — a store,
 * a controller, a file read. That was tolerable while one module used it. It is
 * not now: <b>the client asks this class whether to put a user's file on the
 * network, and the server asks it whether to keep what arrived</b>, so the set is
 * a shared fence and belongs with {@code FileAccess} and {@code GlobSpellings}
 * rather than inside one side's implementation.
 *
 * <p>What that move must not do is change an answer, so the signatures are
 * asserted here directly and per format. A move that quietly dropped WebP, or
 * broadened a prefix, would leave every server test green — they exercise PNG
 * and JPEG — and would show up first as a client uploading something the server
 * refuses, which is the exact failure the shared definition exists to remove.
 */
class ImageFormatTest {

    private static byte[] bytes(int... values) {
        byte[] made = new byte[values.length];
        for (int at = 0; at < values.length; at++) {
            made[at] = (byte) values[at];
        }
        return made;
    }

    /**
     * The four signatures, each one the fixed prefix its specification defines.
     *
     * <p>Asserted as four rather than as a loop over {@code values()}: what is
     * being pinned is the mapping from a particular byte string to a particular
     * format, and a loop would only restate whatever the enum happens to say.
     */
    @Test
    void the_four_formats_are_read_out_of_the_first_bytes_and_not_out_of_a_name() {
        assertEquals(ImageFormat.PNG,
                ImageFormat.of(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0)));
        assertEquals(ImageFormat.JPEG, ImageFormat.of(bytes(0xFF, 0xD8, 0xFF, 0xE0)));
        assertEquals(ImageFormat.GIF, ImageFormat.of("GIF89a".getBytes(StandardCharsets.UTF_8)));
        assertEquals(ImageFormat.WEBP, ImageFormat.of(bytes(
                'R', 'I', 'F', 'F', 0x1A, 0, 0, 0, 'W', 'E', 'B', 'P')));
    }

    /**
     * A JPEG's third byte is a marker and varies, so it is not part of the test.
     *
     * <p>0xE0 is JFIF, 0xE1 is Exif, 0xDB goes straight to a quantisation table.
     * A prefix test that pinned the fourth byte would refuse most of the photos
     * a person actually has, which is the kind of narrowing a move can introduce
     * without any other test noticing.
     */
    @Test
    void a_jpeg_is_recognised_whichever_marker_follows_the_start_of_image() {
        assertEquals(ImageFormat.JPEG, ImageFormat.of(bytes(0xFF, 0xD8, 0xFF, 0xE1, 0x00)));
        assertEquals(ImageFormat.JPEG, ImageFormat.of(bytes(0xFF, 0xD8, 0xFF, 0xDB, 0x00)));
    }

    /**
     * A RIFF container that is not WebP is not an image here.
     *
     * <p>The one signature that is not a single prefix: the four length bytes at
     * offset 4 are skipped and {@code WEBP} is read at 8. A WAV file is RIFF
     * too, and a check that stopped at the first four bytes would name one.
     */
    @Test
    void a_riff_container_that_is_not_webp_is_not_a_picture() {
        assertNull(ImageFormat.of(bytes('R', 'I', 'F', 'F', 0x1A, 0, 0, 0, 'W', 'A', 'V', 'E')));
        assertNull(ImageFormat.of(bytes('R', 'I', 'F', 'F', 0x1A, 0, 0, 0)));
    }

    /**
     * The formats a general-purpose sniffer would call pictures and this does
     * not.
     *
     * <p>BMP, TIFF, SVG and HEIC — the set {@link ImageFormat}'s javadoc names
     * and refuses, because they are what an OpenAI-compatible {@code image_url}
     * part is <em>not</em> documented to take. A build that widened the
     * allow-list would mint ids whose only possible use fails at the vision
     * call, a hop away from anything that could explain it.
     */
    @Test
    void an_image_a_vision_endpoint_cannot_be_shown_is_not_one_of_these() {
        assertNull(ImageFormat.of(bytes('B', 'M', 0x36, 0, 0, 0)));
        assertNull(ImageFormat.of(bytes('I', 'I', 0x2A, 0)));
        assertNull(ImageFormat.of("<svg xmlns=".getBytes(StandardCharsets.UTF_8)));
        assertNull(ImageFormat.of(bytes(0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c')));
    }

    /** Nothing, and less than a signature, are ordinary answers rather than a
     *  crash: every file read on either side of the wire reaches this, empty
     *  ones included. */
    @Test
    void nothing_and_almost_nothing_are_answered_and_not_thrown_at() {
        assertNull(ImageFormat.of(null));
        assertNull(ImageFormat.of(new byte[0]));
        assertNull(ImageFormat.of(bytes(0x89, 'P')));
    }

    /**
     * What a refusal lists, and what a record spells.
     *
     * <p>Both are read across the wire — {@code accepted()} into a 415 and into
     * the client's own sentence about one, {@code declared()} into a record file
     * and an API response — so they are pinned rather than left to
     * {@code toString}. {@code jpg} against {@code jpeg} is the one place the
     * two spellings differ and it is deliberate: the extension is a filename and
     * the declaration is a name.
     */
    @Test
    void the_spellings_a_refusal_and_a_record_use_are_the_ones_both_halves_read() {
        assertEquals("image/png, image/jpeg, image/gif, image/webp", ImageFormat.accepted());
        assertEquals("jpeg", ImageFormat.JPEG.declared());
        assertEquals("jpg", ImageFormat.JPEG.extension());
        assertEquals("image/webp", ImageFormat.WEBP.mediaType());
        for (ImageFormat format : ImageFormat.values()) {
            assertTrue(ImageFormat.accepted().contains(format.mediaType()),
                    format + " is accepted and is not in the list a refusal shows");
        }
    }
}
