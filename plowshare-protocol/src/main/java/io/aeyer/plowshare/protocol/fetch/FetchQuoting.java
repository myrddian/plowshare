package io.aeyer.plowshare.protocol.fetch;

/**
 * The one way a {@link FetchWindow}'s body is quoted, shared by the server's
 * in-process agent tool and the client's MCP tool so that "the two must not
 * drift" is an architectural fact rather than a sentence in two javadocs that
 * happened to agree on the day they were written.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>Before this class, the server's {@code FetchTool} quoted with a plain
 * {@code text.replace("\n", "\n> ")} and the client's {@code FetchTools}
 * quoted by reusing {@code MemoryTools.quote}, which strips the text first and
 * splits on {@code \R} rather than the literal two characters {@code "\n"}.
 * Both were reasoned about carefully and both were wrong to keep separate:
 * {@code FetchService} (server-side, not reachable from this module) prefers
 * to cut a window on a block boundary, and that cut lands two characters past
 * the boundary it found, so the slice it hands back routinely <b>ends with the
 * very {@code "\n\n"} it cut on</b>. A non-stripping quote renders that as two
 * trailing quoted blank lines (a line holding only {@code "> "}, twice); a
 * stripping one does not. Six fixtures on either side of the wire passed
 * anyway, because none of them happened to end a body in a blank line — which
 * is exactly how two implementations that "must not drift" drifted in
 * silence. Sharing the one implementation is what makes that impossible
 * instead of merely unlikely a second time.
 *
 * <h2>The shared behaviour strips, and that is the deliberate choice</h2>
 *
 * <p>A window's body is stripped of leading and trailing whitespace before it
 * is split into lines. The alternative — quote the exact bytes {@code
 * FetchService.read} handed back, blank lines and all — was rejected because
 * a boundary cut's trailing blank line is not content: it is where {@code
 * FetchService.window} happened to end the slice, and rendering it as a
 * quoted empty line teaches a reader nothing about the page. <b>Quoting still
 * applies per remaining line</b>, which is the guarantee that actually
 * matters: column zero stays unreachable by page content whether or not the
 * text was stripped first, so stripping trims noise without weakening the one
 * property this class exists to hold.
 *
 * <h2>Split on the literal {@code "\n"}, not {@code \R}</h2>
 *
 * <p>{@code MemoryTools.quote} (client-side) and its sibling {@code oneLine}
 * split on {@code \R} because a memory's body is typed by a person or written
 * by a model — text this system did not produce and cannot bound the shape
 * of, so it has to defend against CR, CRLF, and the wider Unicode line-break
 * set as well as bare LF. A fetched page's text is not that text. {@code
 * PageExtractor.extract} (server-side; not on this module's classpath, so
 * named here rather than linked) collapses every run of whitespace — space,
 * tab, {@code \r}, an interior {@code \n} — to a single space <em>within each
 * block</em>, via a plain {@code \s+} pattern, before blocks are ever joined;
 * the only newlines that reach {@link FetchWindow#text()} are the literal
 * two-character {@code "\n\n"} sequences {@code String.join} inserts between
 * blocks. A {@code \r} cannot survive to this point — it is consumed by that
 * per-block collapse before any block is joined to another — so there is
 * nothing here for a {@code \R}-based splitter to catch that a literal
 * {@code "\n"} split does not already catch, and reaching for the wider
 * pattern would borrow a defense built for a different, less-constrained
 * threat model without saying so. If a caller ever hands this class text that
 * has not passed through that normalisation, this reasoning no longer holds
 * and this method should be revisited rather than trusted by extension.
 */
public final class FetchQuoting {

    private FetchQuoting() {
    }

    /**
     * {@code text}, stripped, then quoted line by line with {@code "> "} — see
     * the class comment for why it strips first and why it splits on the
     * literal {@code "\n"} rather than a broader line-break pattern.
     *
     * @param text a fetched page's window of prose, exactly as {@link
     *     FetchWindow#text()} carries it — never a page's raw, unextracted
     *     markup, and never text from a different source this reasoning has
     *     not been checked against
     */
    public static String quote(String text) {
        String stripped = text.strip();
        String[] lines = stripped.split("\n", -1);
        StringBuilder out = new StringBuilder(stripped.length() + 16);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append("> ").append(lines[i]);
        }
        return out.toString();
    }
}
