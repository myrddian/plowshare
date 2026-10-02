package io.aeyer.plowshare.server.llm.dispatch;

/**
 * Nothing serves what was asked for.
 *
 * <p><b>Never a quiet fallback to whatever happens to be loaded.</b> A silent
 * substitution produces worse answers with no signal, and there is no layer
 * above this one that could notice.
 */
public final class UnknownSpecifierException extends LlmException {

    public UnknownSpecifierException(String specifier, String available) {
        // `available` is what the pools describe of themselves — model and
        // class names only. Never a base URL and never a key: neither helps the
        // reader pick a specifier, and one of them must never be printed.
        super("no pool serves '" + specifier + "'; configured: " + available);
    }
}
