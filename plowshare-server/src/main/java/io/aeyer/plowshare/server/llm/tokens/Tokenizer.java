package io.aeyer.plowshare.server.llm.tokens;

/**
 * How many tokens a string is.
 *
 * <h2>Why this is an interface and not a method</h2>
 *
 * <p>There is no tokenizer on the reference node — {@code /api/v0/tokenize} and
 * {@code /v1/tokenize} both answer "Unexpected endpoint or method" — so every
 * surface that needs a token count has so far either refused to answer
 * ({@code ContextView}'s four absences) or grown its own private stand-in
 * ({@code Chunker}'s UTF-8 byte ceiling). Two different answers to one
 * question, neither reusable, and no way to improve both at once.
 *
 * <p>This is the seam. A deployment that has a real tokenizer names it in
 * configuration and every caller starts getting measured counts without
 * changing; a deployment that does not gets the configured fallback, and the
 * {@link TokenCount#basis} on every answer says which it got. <b>The counts are
 * comparable across implementations and the claims they carry are not
 * interchangeable</b>, which is the property that makes an estimate safe to
 * render.
 *
 * <p>Implementations are expected to be cheap and side-effect free: this is
 * called on the read path, per component, per request.
 */
public interface Tokenizer {

    /** How many tokens {@code text} is, and on what basis. Never null. */
    TokenCount count(String text);

    /**
     * What this tokenizer is, in a person's words, for the line a client shows
     * beside a number it did not measure. Naming the mechanism is what stops an
     * estimate from reading as an oversight.
     */
    String describe();
}
