package io.aeyer.plowshare.client.files;

import io.aeyer.plowshare.protocol.ImageFormat;
import java.io.IOException;

/**
 * How a picture this client just read gets a name on the server.
 *
 * <h2>Why this is an interface and not {@code ServerClient}</h2>
 *
 * <p>{@link ClientEnforcer} answers file requests. It has no business holding
 * the archive API — memories, documents, conversations, projects — and {@code
 * ChannelClient} in this same package already declines it, taking a base URL
 * string rather than a client object. This is the one thing the enforcer needs
 * from the far end, expressed as the one thing.
 *
 * <p>It also puts the <em>status</em> in this package rather than the archive's
 * exception. The three refusals below have three different remedies, and the
 * class that writes a sentence a model can act on is {@link ClientEnforcer}; a
 * seam that handed over prose would have moved that decision to whoever wired
 * it, where a test of the sentences would not be a test of the reader.
 *
 * <h2>{@link #NONE} is the default, and it is the same shape as {@code
 * ImageStore.NONE} on the server</h2>
 *
 * <p>A client wired with nothing names nothing, and a PNG is refused as not
 * UTF-8 text exactly as it was before any of this existed. That is what makes
 * the change additive rather than a new answer every session gets: {@code
 * ConvertedTextStaysOffStdoutTest} and every test that builds a bare enforcer go
 * on measuring what they measured.
 */
@FunctionalInterface
public interface ImageUploads {

    /**
     * Names nothing, ever.
     *
     * <p>Null and not a throw: {@link ClientEnforcer} reads null as "this build
     * does not name pictures" and falls through to the decoder it always had,
     * which is the one behaviour a deployment with no uploader must keep.
     */
    ImageUploads NONE = (filename, bytes) -> null;

    /**
     * Upload these bytes and answer with the id that names them.
     *
     * @param filename what to record the picture as — the path as the caller
     *     wrote it, so that a person looking at a directory of {@code img_}
     *     files can tell which is which. May be null
     * @param bytes the picture. <b>The caller has already asked {@link
     *     ImageFormat}</b> and does not send what it knows will be refused: an
     *     upload is a user's file leaving their machine, and letting the
     *     server's {@code 415} decide would ship arbitrary binaries to find out
     * @return the id, or {@code null} where this build names no pictures
     * @throws Unnameable the server answered and said no
     * @throws IOException the server was not reached at all. <b>Separate from
     *     {@link Unnameable} for {@code ServerClient}'s standing reason</b>: a
     *     413 is a fact about the file and an unreachable server is a fact about
     *     the network, and a reader told the second about the first goes off
     *     restarting something that is fine
     */
    String name(String filename, byte[] bytes) throws IOException, Unnameable;

    /**
     * The server answered, and would not hold this picture.
     *
     * <p><b>Checked</b>, which is {@code ServerClient}'s rule for {@code
     * IOException} applied one layer down: the caller that owes a model a
     * sentence about this is {@link ClientEnforcer}, and a compile error is what
     * makes failing to say anything impossible rather than quiet.
     */
    final class Unnameable extends Exception {

        private final int status;

        /**
         * @param status what the server answered — {@code 415}, {@code 413} and
         *     {@code 400} are the three {@code ImageController} separates, and
         *     anything else is a server this client cannot interpret
         * @param detail the server's own sentence, which is the half of the
         *     message this client must not write for it
         */
        public Unnameable(int status, String detail) {
            super(detail);
            this.status = status;
        }

        /** Which refusal it was. The remedies differ, so the number is carried
         *  rather than the reader parsing the prose for it. */
        public int status() {
            return status;
        }
    }
}
