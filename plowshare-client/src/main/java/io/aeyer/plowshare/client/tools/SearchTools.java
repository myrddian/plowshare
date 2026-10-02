package io.aeyer.plowshare.client.tools;

import static io.aeyer.plowshare.client.tools.MemoryTools.oneLine;
import static io.aeyer.plowshare.client.tools.Schemas.integer;
import static io.aeyer.plowshare.client.tools.Schemas.object;
import static io.aeyer.plowshare.client.tools.Schemas.string;

import io.aeyer.plowshare.client.ServerClient;
import io.aeyer.plowshare.client.mcp.ToolRegistry;
import io.aeyer.plowshare.protocol.search.Hit;
import io.aeyer.plowshare.protocol.search.SearchPage;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One tool, {@code search}, and the whole of what it tells a model about how an
 * answer was found: nothing.
 *
 * <h2>One tool, and no provider name ever reaches the model</h2>
 *
 * <p>{@code POST /v1/search} sits behind a ladder of providers an operator
 * registers separately, and this class is the reason a model never learns which
 * one answered. The design spec's amended words: no provider name reaches the
 * model <em>as the source of a result</em> — not in the tool name, not in the
 * schema, not in a result field. (The unqualified version of that sentence was
 * always false in one direction: an exhausted ladder's refusal names every rung,
 * on purpose, because that is how an operator's mistyped ladder entry reaches
 * anybody at all. See {@link SearchPage}'s own javadoc.) What this class holds
 * to is the part that is about this class: nothing it declares or renders on the
 * success path names a provider.
 *
 * <p>Compare {@code MemoryTools}, which argues at length about scribes, digests
 * and unsearchable memories; this class has one behaviour to hide and the
 * discipline is silence. There is exactly one tool name ({@link
 * #registerOn}), one description ({@link #SEARCH_DESCRIPTION}), and the schema
 * that description is bound to ({@link #searchSchema()}) — grep any of the
 * three for a vendor's name and there is nothing to find, and {@code
 * SearchToolsTest} pins that as a fact about the built artifact rather than an
 * intention.
 *
 * <p>The reason given twice over in the spec is worth restating here because it
 * is what stops a future edit re-introducing the leak: a model that saw which
 * rung answered would learn to ask for one by name, which defeats the reason a
 * ladder exists instead of a provider parameter, and it would make {@code
 * Capabilities}' one-capability-one-tool register describe a surface that
 * actually varies with whatever is deployed this week.
 *
 * <h2>Every provider-supplied string is flattened</h2>
 *
 * <p>{@link Hit#url}, {@link Hit#title} and {@link Hit#snippet} are words a web
 * page contained, carried through a third-party HTTP endpoint this project does
 * not run, straight into a model's context. That is the least trusted text
 * anywhere in this slice — more so than a memory's body, which at least passed
 * through an agent this deployment runs — and {@link #render} puts every one of
 * the three through {@link MemoryTools#oneLine} before it reaches column zero of
 * the rendered answer. {@code MemoryTools}' own rule applies verbatim: <b>every
 * line at column zero is one this renderer wrote.</b> A page whose {@code
 * <title>} contained a line break could otherwise open a line of its own inside
 * this tool's answer, indistinguishable from a second result or from this
 * renderer's own prose — {@code SearchToolsTest} pins exactly that attack with a
 * title carrying a newline and asserts the forged line never appears.
 *
 * <p>Not quoted, unlike a memory's body: a search result has no field that is
 * expected to span lines, so there is nothing here for quoting to preserve —
 * flattening is strictly the right operation, not a compromise this class
 * accepts because a fuller treatment would cost more code.
 *
 * <h2>Defaults exist because the server will not invent them</h2>
 *
 * <p>{@code SearchController.SearchRequest} takes {@code pageSize}, {@code max}
 * and {@code page} as primitive {@code int}s and {@code SearchService.validate}
 * refuses anything below one — there is no null and no server-side default to
 * fall back on. A model that already gave a good {@code query} should not have
 * a whole call refused for omitting three numbers it had no strong opinion
 * about, so {@link #DEFAULT_PAGE_SIZE}, {@link #DEFAULT_MAX} and {@link
 * #DEFAULT_PAGE} are supplied here instead of at the schema, where a JSON Schema
 * {@code default} is only ever a hint a caller may ignore.
 *
 * <p>{@link #DEFAULT_MAX} is not simply {@link #DEFAULT_PAGE_SIZE} repeated.
 * {@code max} is part of what identifies the server's stored result set — {@code
 * SearchService}'s own javadoc: "a caller asking for fifty results bought a
 * different set than one asking for twenty-five of the same terms" — so a
 * caller who wants to read a second page of a search it did not ask for a
 * ceiling on needs that ceiling to already allow more than one page's worth.
 * Three page sizes' worth of headroom is picked once here rather than left to a
 * model to reconstruct, because getting it wrong on page one is invisible until
 * page two comes back refused.
 *
 * @see MemoryTools for the fuller argument this class's docs lean on rather
 *     than repeat: why an unreachable server is thrown rather than rendered,
 *     and why every single-line slot goes through {@link MemoryTools#oneLine}
 */
public final class SearchTools {

    /**
     * How many hits a page holds when {@code page_size} is omitted.
     *
     * <p>Ten is not argued from a benchmark; it is the smallest number that
     * reads as "a page" rather than "one result" or "everything", which is the
     * only property this default actually needs — a model that wants a
     * different shape says so.
     *
     * <p><b>Public, and {@code cli.Commands} reads it rather than declaring its
     * own copy.</b> A person at this terminal and a model through MCP asking
     * the same bare {@code search} have to be offered the same default, and
     * two constants that merely claim to agree is not the same fact as one
     * constant that cannot disagree with itself — this module already refuses
     * that weaker guarantee elsewhere, in {@link
     * io.aeyer.plowshare.client.Capabilities#toolsAreDeclared} and {@code
     * commandsAreDeclared}, which fail at class-load on drift rather than
     * documenting an invariant nothing checks.
     */
    public static final int DEFAULT_PAGE_SIZE = 10;

    /**
     * The ceiling sent when {@code max} is omitted — three pages' worth of
     * {@link #DEFAULT_PAGE_SIZE}, so a caller that did not think about paging on
     * its first call is not immediately refused on its second. See the class
     * javadoc for why this is not simply {@link #DEFAULT_PAGE_SIZE} repeated.
     *
     * <p>Public for {@link #DEFAULT_PAGE_SIZE}'s own reason: one number {@code
     * cli.Commands} reads rather than a second one that could quietly drift
     * from it.
     */
    public static final int DEFAULT_MAX = 30;

    /**
     * The page read when {@code page} is omitted — the only sane default,
     * since the server counts pages from one and refuses zero.
     *
     * <p>Public for {@link #DEFAULT_PAGE_SIZE}'s own reason.
     */
    public static final int DEFAULT_PAGE = 1;

    private final ServerClient server;

    public SearchTools(ServerClient server) {
        this.server = server;
    }

    /** Add the one tool this class offers. */
    public void registerOn(ToolRegistry registry) {
        registry.register("search", SEARCH_DESCRIPTION, searchSchema(), this::search);
    }

    // --- the description -----------------------------------------------------

    /**
     * The whole of what a model is told about this tool.
     *
     * <p>Says what {@code search} does and what its numbers mean; says nothing
     * about how an answer is produced. {@code SearchToolsTest} greps this
     * constant — lower-cased — for four vendor names as a standing regression
     * check, not because those four are the only ones that could ever appear:
     * the rule is "no provider, ever", and the test is a tripwire for the
     * ordinary way that rule breaks, which is somebody explaining a real
     * incident by naming the rung that had it.
     */
    static final String SEARCH_DESCRIPTION = """
            Search for information from outside this project and get back a page \
            of results — a URL, a title and a short snippet for each. Use this for \
            anything the archive and the corpus do not already hold: a current \
            fact, an outside reference, documentation for something this project \
            does not define.

            `max` is the most results worth looking for — a ceiling, not a target. \
            A query with three good answers against a `max` of fifty has not come \
            up short; that is a fact about the query. `page_size` is how many you \
            want handed back right now.

            To read a later page of the SAME search, call this again with the \
            exact same `query` and `max` and a higher `page`. There is no handle \
            to write down: a later page is addressed by re-sending the query it \
            belongs to, so changing `query` or `max` between calls starts a \
            different search from page one instead of continuing this one. \
            Asking for `page` 1 again is a NEW search — it looks for fresh \
            results rather than repeating what it said before, and it costs \
            what the first one cost.

            A search can come back with nothing, or refuse outright with a \
            sentence saying what to try instead. Either way you get text back — \
            never an error you have to guess the meaning of.""";

    // --- the schema ------------------------------------------------------------

    /*
     * Built from a LinkedHashMap and never Map.of, on MemoryTools' own
     * argument: the model reads these fields in the order they are declared,
     * and Map.of has no order to preserve.
     */
    static Map<String, Object> searchSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", string(
                "What to search for, in plain language or as search terms."));
        properties.put("page_size", integer(
                "How many results to return on this page. Omit for the default of "
                        + DEFAULT_PAGE_SIZE + "."));
        properties.put("max", integer(
                "The most results worth looking for — a ceiling, not a target. Send the SAME"
                        + " value on every page of one search: a page is addressed by its"
                        + " query and max, and a different max is a different search. Omit for"
                        + " the default of " + DEFAULT_MAX + "."));
        properties.put("page", integer(
                "Which page to read, counting from one. A page after the first only works"
                        + " against the exact query and max that produced page one; asking for"
                        + " page 1 again searches again rather than repeating the last answer."
                        + " Omit for page one."));
        return object(properties, List.of("query"));
    }

    // --- the handler -------------------------------------------------------------

    /** {@code search} — one page of results, or the refusal explaining why not. */
    public Object search(Map<String, Object> args) {
        String query = required(args, "query");
        int pageSize = integerOrDefault(args, "page_size", DEFAULT_PAGE_SIZE);
        int max = integerOrDefault(args, "max", DEFAULT_MAX);
        int page = integerOrDefault(args, "page", DEFAULT_PAGE);

        try {
            return render(server.search(query, pageSize, max, page));
        } catch (IOException unreachable) {
            // Thrown rather than rendered, on MemoryTools.ask's own reasoning:
            // StdioTransport turns a thrown handler into a result carrying
            // isError, which is what tells a model this is infrastructure and
            // not an answer. Returning the same sentence as ordinary content
            // would leave "the search found nothing" and "the search was never
            // asked" reading identically.
            throw new IllegalStateException(
                    "could not reach the Plowshare server at " + server.baseUrl() + " to search — "
                            + describe(unreachable) + ". This says nothing about the search"
                            + " itself: it was never asked. Check the server is running, then try"
                            + " again.",
                    unreachable);
        }
    }

    // --- rendering ---------------------------------------------------------------

    /**
     * One page of results, rendered so that <b>every line at column zero is one
     * this renderer wrote</b> — {@code MemoryTools}' rule, and the reason it
     * applies at full strength here is in the class javadoc: a hit's url, title
     * and snippet are the least trusted strings anywhere in this slice.
     */
    static String render(SearchPage page) {
        if (page.refusal() != null) {
            // Composed by the server, and it CAN quote a provider — which is
            // exactly why it is flattened. SearchLadder.messageOrDefault
            // embeds a failing provider's own message verbatim in the note it
            // writes for that rung, so a refusal reaching this line may carry
            // text that came off a process this deployment does not run. This
            // comment used to say the sentence was never a provider's, and
            // that stated reason would have invited a later edit to remove the
            // belt as redundant. The sentence's SHAPE is the server's -- the
            // rungs it names and the order it names them in -- and the strings
            // inside it are not.
            return "SEARCH FAILED — " + oneLine(page.refusal());
        }
        if (page.hits().isEmpty()) {
            // Page.total() distinguishes an empty search from an empty page:
            // asking for page 9 of a search with 3 results is not "nothing was
            // found", and conflating the two would send a caller back to
            // rephrase a query that already worked.
            return page.total() == 0
                    ? "no results for this search."
                    : "nothing on page " + page.page() + " of this search — it holds "
                            + page.total() + (page.total() == 1 ? " result" : " results")
                            + " in all.";
        }

        StringBuilder out = new StringBuilder();
        out.append(page.hits().size()).append(" of ").append(page.total())
                .append(page.total() == 1 ? " result" : " results").append(", page ")
                .append(page.page())
                .append(page.hasMore() ? " — more after this" : " — no more after this")
                .append(":\n");
        for (Hit hit : page.hits()) {
            out.append('\n').append(oneLine(hit.title())).append('\n')
                    .append(oneLine(hit.url())).append('\n')
                    .append(oneLine(hit.snippet())).append('\n');
        }
        return out.toString();
    }

    // --- arguments -----------------------------------------------------------------

    private static String required(Map<String, Object> args, String name) {
        Object value = args.get(name);
        String text = value == null ? null : value.toString();
        if (text == null || text.isBlank()) {
            // Names the argument: a refusal that only says "invalid arguments"
            // costs the model a guess, and it usually guesses the same way
            // twice.
            throw new IllegalArgumentException("'" + name + "' is required and must not be empty");
        }
        return text;
    }

    /**
     * An optional integer argument, or {@code fallback} when it is absent.
     *
     * <p>Accepts a numeric string as well as a number, on {@code MemoryTools}'
     * own observation: models send numbers as strings often enough that
     * refusing one would spend a whole turn teaching a schema that was already
     * given.
     */
    private static int integerOrDefault(Map<String, Object> args, String name, int fallback) {
        Object value = args.get(name);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(
                    "'" + name + "' should be a whole number, not " + value);
        }
    }

    /** The message, or the exception's own type when it carries none — a
     *  ConnectException with a null message would otherwise arrive as the word
     *  "null" in the middle of the sentence above. */
    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.toString() : message;
    }
}
