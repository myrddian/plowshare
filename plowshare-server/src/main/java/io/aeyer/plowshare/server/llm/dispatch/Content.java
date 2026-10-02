package io.aeyer.plowshare.server.llm.dispatch;

import java.util.Objects;

/**
 * One part of what a message says: words, or a picture.
 *
 * <h2>Why a message is parts rather than a string</h2>
 *
 * <p>{@link ChatMessage} carried a {@code String} and {@code
 * OpenAiTransport.wire} put it on the wire as a scalar {@code content}. That is
 * the whole of the OpenAI contract for a text turn and remains so — see {@link
 * ChatMessage#content()}, which is what still produces that scalar byte for
 * byte. It has no room for the other shape the same field takes: an array of
 * typed parts, which is how an image reaches a model at all.
 *
 * <p>So a message holds a list of these, and a message that holds one {@link
 * Text} is the message this server has always sent.
 *
 * <h2>{@link Image} holds a {@code data:} URI, and that is the containment
 * decision</h2>
 *
 * <p>The OpenAI {@code image_url} part will take an {@code https://} URL and an
 * endpoint will fetch it. <b>Nothing in this server ever writes one.</b> A
 * transport handed a URL a caller supplied — and a caller here can be a task
 * string, which is as far outside as this system has — would be a server-side
 * request forgery surface: the process connecting to an address somebody else
 * chose, from inside the network the inference nodes are on.
 *
 * <p>The control is that {@link Image} cannot be constructed with anything else.
 * It is not a rule in a comment that a transport has to remember; the type
 * refuses. {@code ImageStore.dataUri} is the only thing in this server that
 * produces the string it accepts, and that method reads bytes out of a
 * directory named by a project id, addressed by a UID that matched a hexadecimal
 * pattern. There is no path from a caller's string to an outbound connection.
 *
 * <h2>KNOWN GAP: an image is not counted</h2>
 *
 * <p>{@link Image#text()} is the empty string, so every character count in this
 * server counts a picture as nothing. That is right for what those counts are
 * for — they are counting words, and a stand-in sentence would be words the
 * model was never sent — and it is <b>wrong as a size estimate</b>: a 145-byte
 * PNG is hundreds of tokens, because a vision encoder emits a fixed number of
 * embeddings per tile whatever the bytes compressed to.
 *
 * <p>So "no tokenizer emits more tokens than its input has bytes", which {@code
 * LlmConfig.requireEmbeddingMaxInputTokens} records and which the compaction
 * bound leans on, does not hold here. That javadoc carries the full account of
 * what it costs and why it is recorded rather than guessed at.
 *
 * <p><b>The UID rides along beside the URI</b>, and it is not decoration: it is
 * what a log line, a refusal or a token report can name. A base64 payload is
 * unquotable — it is the one value in this system that must never be echoed into
 * a message, a log or an entry — so without the UID an image would be a thing
 * nothing could refer to.
 *
 * <p><b>And the model is now one of the things that refers to it.</b> That
 * paragraph listed a log line, a refusal and a token report, which was the whole
 * of it while looking was all a run could do with a picture. {@code
 * JobRuntime.opening} says the ids out loud on the utterance and {@code
 * agent_run} takes them back, so this field is what a delegating agent writes
 * down to have a callee shown the same picture. Nothing about the type changed;
 * what changed is that the id is read by somebody who cannot be shown the bytes
 * a second time, which is the reason it had to be a name and not an index.
 */
public sealed interface Content {

    /** What this part contributes to {@link ChatMessage#content()}, which is
     *  the harness's view of a message: words for words, and nothing for a
     *  picture, because there is no text in one. */
    String text();

    /** Words. The only kind of part that existed before images did, and the
     *  kind every message in this server still holds exactly one of. */
    record Text(String text) implements Content {
        public Text {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * A picture the server has already fetched out of its own store.
     *
     * @param uid the image's id, so that everything except the endpoint can
     *     name this part without holding the bytes
     * @param dataUri {@code data:image/png;base64,…}. Refused if it is anything
     *     else — see the type comment for why that refusal is the whole
     *     control
     */
    record Image(String uid, String dataUri) implements Content {
        public Image {
            Objects.requireNonNull(uid, "uid");
            Objects.requireNonNull(dataUri, "dataUri");
            if (!dataUri.startsWith("data:")) {
                throw new IllegalArgumentException(
                        "an image part carries the bytes as a data: URI and never a URL to"
                                + " fetch; '" + summarised(dataUri) + "' is not one. A transport"
                                + " that resolved a caller-supplied URL would be choosing what"
                                + " this process connects to on somebody else's say-so");
            }
        }

        /** The empty string: an image has no text, and the alternative -- a
         *  stand-in sentence -- would put words in a message the model was never
         *  sent, which every character count in this server would then be
         *  counting. */
        @Override
        public String text() {
            return "";
        }

        /** What a log line says about this part. <b>Never the payload</b>: a
         *  base64 image in a log is a log nobody can read and a picture stored
         *  in a second place nobody meant to store it. */
        @Override
        public String toString() {
            return "Image[" + uid + "]";
        }

        private static String summarised(String uri) {
            return uri.length() <= 40 ? uri : uri.substring(0, 40) + "…";
        }
    }
}
