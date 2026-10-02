package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.search.Hit;
import io.aeyer.plowshare.protocol.search.SearchPage;
import io.aeyer.plowshare.server.agents.ToolArguments.BadArguments;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import io.aeyer.plowshare.server.search.SearchLadder;
import io.aeyer.plowshare.server.search.SearchService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code search} — one page of results from outside this project, for an
 * agent of this server rather than only for a foreign harness through MCP or a
 * person through the CLI.
 *
 * <h2>The hole {@link FetchTool}'s own javadoc names, closed the same way</h2>
 *
 * <p>The previous slice shipped {@code search} as an MCP tool ({@code
 * io.aeyer.plowshare.client.tools.SearchTools}) and a CLI verb and never built
 * the agent tool, so a foreign harness driving Plowshare could search and
 * Plowshare's own agents could not — the same hazard {@link FetchTool}'s class
 * comment names for {@code fetch}, one task earlier. This class is that fix
 * for {@code search}, and it is {@link SearchService#search} verbatim,
 * rendered — there is nothing for this class to add on top of that shape.
 *
 * <h2>No provider name may reach the model, and that rule has one amendment</h2>
 *
 * <p>Design spec §2, amended: no provider name reaches the model <em>as the
 * source of a result</em> — not in the tool name, not in the schema, not in
 * the description, not in a rendered hit. {@link #NAME}, {@link #DESCRIPTION}
 * and {@link #schemaMap} name no vendor, and {@code
 * the_description_and_schema_name_no_vendor} greps the built artifact for
 * three of them as a standing tripwire rather than as the only ones that could
 * ever appear — the rule is "no provider, ever", and the test exists to catch
 * the ordinary way that rule breaks, which is somebody explaining a real
 * incident by naming the rung that had it.
 *
 * <p><b>The amendment, and it is the reason {@link #render} does not scrub
 * {@link SearchPage#refusal} — but does flatten it.</b> {@link SearchPage}'s
 * own javadoc: an exhausted ladder's refusal names every rung and what became
 * of it, because at that moment there is no result to attribute, a model
 * reading which rung failed cannot learn a vendor <em>preference</em> it
 * could act on, and an operator whose {@code plowshare.search.ladder} names a
 * provider nobody registered has no other channel for finding out. So a
 * refusal keeps every name and every word {@link SearchService#search}
 * composed it with — nothing is deleted and nothing is reworded. What never
 * happens is a provider named as <em>the source of a hit</em>, which is the
 * narrower and true rule {@link #render} keeps: the success path below
 * carries only {@link SearchPage#hits} off the page.
 *
 * <p><b>Flattened, though, and this was the one review finding this class
 * shipped without.</b> {@link SearchLadder#run}'s own refusal is composed as
 * {@code providerKey + " failed: " + messageOrDefault(answer.message())} — a
 * failing provider's own message, quoted verbatim, and this class comment's
 * previous paragraph already names the consequence: <b>a registered provider
 * can put text of its choosing into a model's context by failing
 * deliberately.</b> {@code SearchTools.render} — the client-side MCP
 * renderer — flattens the identical value for the identical reason, and the
 * trade the previous slice accepted (a provider's own words, verbatim, inside
 * a refusal) was accepted specifically because both renderers flatten it,
 * never because either lets it stand as a free-form line. It matters more
 * here than on the MCP surface, not less: this text lands inside a running
 * agent's own conversation turn, where a forged line mimicking this
 * renderer's own formatting — a fake results header, or directive-looking
 * prose — is read by the same model that may act on its next tool call,
 * where the MCP surface's reader is a foreign harness one step further from
 * autonomous action. {@link ToolArguments#oneLine} is exactly the right tool
 * for this: it collapses a line break and removes no name and no text, so it
 * satisfies both requirements — no provider preference is learnable, and no
 * provider gets a free line — at once. {@code
 * a_refusal_carrying_a_newline_cannot_forge_a_line_of_its_own} pins it.
 *
 * <h2>Every provider-supplied string is flattened — url, title and snippet
 * alike</h2>
 *
 * <p>{@link Hit#url}, {@link Hit#title} and {@link Hit#snippet} are words a
 * web page contained, carried through a third-party HTTP endpoint this
 * project does not run, straight into a model's context — the least trusted
 * text anywhere on this surface, more so than a fetched page's title, which at
 * least names the one page an agent chose to read. {@link #render} puts every
 * one of the three through {@link ToolArguments#oneLine} before it reaches
 * column zero of the rendered answer, on {@link FetchTool}'s own rule: every
 * line at column zero is one this renderer wrote. A hit's title carrying a
 * newline could otherwise open a line of its own inside this tool's answer,
 * indistinguishable from a second result or from this renderer's own prose —
 * {@code a_title_carrying_a_newline_cannot_forge_a_line_of_its_own} pins
 * exactly that attack.
 *
 * <p><b>Not quoted, unlike a fetched page's body.</b> A search result has no
 * field expected to span lines — url, title and snippet are single-line
 * fields the same way a document's title is — so there is nothing here for
 * quoting to preserve; flattening is the right operation for all three, not a
 * cheaper stand-in for one.
 *
 * <h2>{@code ToolArguments.oneLine} is the shared flattener, and this class
 * does not keep a fourth copy</h2>
 *
 * <p>{@link FetchTool}'s own class comment records the history: {@code
 * MemoryTools} and {@code AskTool} each held a private copy, {@code
 * DocumentTools} held a second one, {@code FetchTool} copied it a third time
 * byte-for-byte, and a review caught the drift and moved the implementation to
 * {@link ToolArguments#oneLine}. This class is written against that shared
 * method from the start rather than adding a fourth copy of the six lines it
 * takes.
 *
 * <h2>Defaults exist because {@link SearchService#search} will not invent
 * them</h2>
 *
 * <p>{@code SearchController.SearchRequest} takes {@code pageSize}, {@code
 * max} and {@code page} as primitive {@code int}s and {@link
 * SearchService#search} refuses anything below one — there is no server-side
 * default to fall back on. A model that already gave a good {@code query}
 * should not have a whole call refused for omitting three numbers it had no
 * strong opinion about, so {@link #DEFAULT_PAGE_SIZE}, {@link #DEFAULT_MAX}
 * and {@link #DEFAULT_PAGE} are supplied here, on {@code
 * io.aeyer.plowshare.client.tools.SearchTools}' own reasoning and its own
 * numbers — the two surfaces answer a bare {@code search} the same way rather
 * than merely claiming to.
 *
 * <h2>One catch, {@link DocumentTools.Search}'s shape and not {@link
 * FetchTool}'s</h2>
 *
 * <p>{@code run} follows {@link DocumentTools.Search} and {@link FetchTool}
 * for the outer two lines — {@code argumentsJson} and {@code home} required
 * non-null outside the try, because a null there is this runtime's bug and
 * not the model's. It follows {@link FetchTool} rather than {@link
 * DocumentTools.Search} for the catch, though, and for {@link FetchTool}'s own
 * reason: {@link SearchService#search} does its own argument validation and
 * names the type it throws for exactly that — a blank {@code query}, or a
 * {@code max}, {@code page} or {@code pageSize} below one. Those are not
 * re-derived here. {@code SearchService}'s own class comment argues at length
 * why {@code page} and {@code pageSize} are refused rather than clamped —
 * clamping either would have this tool's {@code SearchPage} literally lying
 * about what it served — and duplicating that judgment here would be a second,
 * wordier copy that can drift from it. Catching the one exception it throws
 * for exactly this is cheaper and cannot.
 *
 * <p>{@code query} is the one exception: {@link ToolArguments#requireText}
 * refuses a blank or missing one before {@link SearchService#search} is ever
 * called, on {@link FetchTool#answer}'s own precedent for {@code url} — a
 * malformed <em>shape</em> of argument is this package's own concern, and
 * {@code a_blank_query_comes_back_as_a_message_rather_than_throwing} is
 * written, like {@code FetchToolTest}'s equivalent, against an unstubbed
 * collaborator that must never be called for it to hold.
 *
 * <h2>{@code home} is required and never read, {@link FetchTool}'s own reason</h2>
 *
 * <p>The search ladder and its stored result sets are server-wide by design —
 * {@link SearchService}'s own javadoc keys a stored set on {@code query} and
 * {@code max} alone, with no project in it — so there is no tier for this tool
 * to scope a call by. It is still a parameter of {@link #run} and still
 * required non-null, {@link FetchTool}'s own reason: the day a search grows a
 * scope, a tool that had quietly accepted a null {@code home} would start
 * answering from one.
 */
public final class SearchTool implements AgentTool {

    /** The name the model calls, the job log records, and {@code
     *  AgentRegistry.load} is told about. Names no provider. */
    public static final String NAME = "search";

    /** How many hits a page holds when {@code page_size} is omitted. {@code
     *  io.aeyer.plowshare.client.tools.SearchTools#DEFAULT_PAGE_SIZE}'s own
     *  number, so a bare {@code search} through either surface answers the
     *  same shape rather than merely claiming to. */
    static final int DEFAULT_PAGE_SIZE = 10;

    /** The ceiling sent when {@code max} is omitted — three pages' worth of
     *  {@link #DEFAULT_PAGE_SIZE}, so a caller that did not think about paging
     *  on its first call is not immediately refused on its second. {@code
     *  SearchTools#DEFAULT_MAX}'s own number and its own reason: {@code max}
     *  is part of what keys the server's stored result set, so a caller
     *  reading a second page of a search it did not ask a ceiling for needs
     *  that ceiling to already allow more than one page's worth. */
    static final int DEFAULT_MAX = 30;

    /** The page read when {@code page} is omitted — the only sane default,
     *  since the server counts pages from one and refuses zero. */
    static final int DEFAULT_PAGE = 1;

    private final SearchService search;
    private final ToolSchema schema;

    public SearchTool(SearchService search) {
        this.search = Objects.requireNonNull(search, "search");
        this.schema = new ToolSchema(NAME, DESCRIPTION, schemaMap());
    }

    private boolean scripted;
    SearchTool scripted() { var copy = new SearchTool(search); copy.scripted = true; return copy; }

    @Override
    public ToolSchema schema() {
        return schema;
    }

    @Override
    public String run(String argumentsJson, Home home) {
        // Outside the try: a null here is the runtime's bug and not the
        // model's, and the never-throw rule is about a caller's mistakes.
        Objects.requireNonNull(argumentsJson, "argumentsJson");
        // Required although it is never read -- see the class comment's
        // "home is required and never read" section.
        Objects.requireNonNull(home, "home");
        try {
            return answer(argumentsJson);
        } catch (BadArguments | CallerFault unusable) {
            // BadArguments is this package's own shape-of-arguments failure
            // (query missing or blank, or a number that will not parse);
            // CallerFault is SearchService.search's own edge
            // validation (max, page or pageSize below one) -- see the class
            // comment's "One catch" section for why both belong here and
            // neither is re-derived by this class instead.
            return unusable.getMessage();
        }
    }

    private String answer(String argumentsJson) {
        JsonNode args = ToolArguments.parse(argumentsJson, NAME, "{\"query\": \"plowshare mcp\"}");
        String query = ToolArguments.requireText(args, "query", NAME,
                "what to search for, in plain language or as search terms");
        int pageSize = ToolArguments.optionalInt(
                args, "page_size", DEFAULT_PAGE_SIZE, SearchTool::badPageSize);
        int max = ToolArguments.optionalInt(args, "max", DEFAULT_MAX, SearchTool::badMax);
        int page = ToolArguments.optionalInt(args, "page", DEFAULT_PAGE, SearchTool::badPage);
        var pageResult = search.search(query, pageSize, max, page);
        if (scripted) {
            try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(pageResult); }
            catch (java.io.IOException broken) { throw new IllegalStateException(broken); }
        }
        return render(pageResult);
    }

    private static BadArguments badPageSize(JsonNode value) {
        return new BadArguments(NAME + " could not read 'page_size': it must be a whole number of"
                + " results per page, not " + value + ". Leave it out for the default of "
                + DEFAULT_PAGE_SIZE + ".");
    }

    private static BadArguments badMax(JsonNode value) {
        return new BadArguments(NAME + " could not read 'max': it must be a whole number, the"
                + " most results worth looking for, not " + value + ". Leave it out for the"
                + " default of " + DEFAULT_MAX + ".");
    }

    private static BadArguments badPage(JsonNode value) {
        return new BadArguments(NAME + " could not read 'page': it must be a whole number"
                + " counting from one, not " + value + ". Leave it out for page one.");
    }

    // --- the description -----------------------------------------------------------

    /*
     * A calling model decides whether to invoke a tool from its description
     * alone -- it never sees this code or which provider answered. Says
     * nothing about how an answer is produced, the whole of the discipline
     * the class comment argues at length.
     */
    static final String DESCRIPTION = """
            Search for information from outside this project and get back a \
            page of results — a URL, a title and a short snippet for each. \
            Use it for anything memory and the document corpus do not already \
            hold: a current fact, an outside reference, documentation for \
            something this project does not define. Hand a result's URL to \
            fetch to read the whole page.

            'max' is the most results worth looking for — a ceiling, not a \
            target. A query with three good answers against a 'max' of fifty \
            has not come up short; that is a fact about the query. \
            'page_size' is how many results to hand back right now.

            To read a later page of the SAME search, call this again with \
            the exact same 'query' and 'max' and a higher 'page'. There is no \
            handle to write down: a later page is addressed by re-sending \
            the query it belongs to, so changing 'query' or 'max' between \
            calls starts a different search from page one instead of \
            continuing this one. Asking for 'page' 1 again is a NEW search — \
            it looks for fresh results rather than repeating what it said \
            before, and it costs what the first one cost.

            A search can come back with nothing, or refuse outright with a \
            sentence saying what to try instead. Either way you get text \
            back — never an error you have to guess the meaning of.""";

    // --- the schema ------------------------------------------------------------------

    /*
     * LinkedHashMap and never Map.of, matching every other tool's schema in
     * this package: Map.of has no iteration order to preserve, so a schema
     * built that way serialises its properties in a different order between
     * JVM runs, and a model reads these fields in the order given.
     */
    private static Map<String, Object> schemaMap() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", ToolArguments.string(
                "What to search for, in plain language or as search terms."));
        properties.put("page_size", ToolArguments.integer(
                "How many results to return on this page. Omit for the default of "
                        + DEFAULT_PAGE_SIZE + "."));
        properties.put("max", ToolArguments.integer(
                "The most results worth looking for — a ceiling, not a target. Send the SAME"
                        + " value on every page of one search: a page is addressed by its query"
                        + " and max, and a different max is a different search. Omit for the"
                        + " default of " + DEFAULT_MAX + "."));
        properties.put("page", ToolArguments.integer(
                "Which page to read, counting from one. A page after the first only works"
                        + " against the exact query and max that produced page one; asking for"
                        + " page 1 again searches again rather than repeating the last answer."
                        + " Omit for page one."));
        return ToolArguments.object(properties, List.of("query"));
    }

    // --- rendering ---------------------------------------------------------------------

    /**
     * One page: a count sentence, then every hit, or a refusal, flattened.
     *
     * <p>{@link SearchPage#refusal()} is already model-facing prose written by
     * {@link SearchService#search} — see the class comment's amendment section
     * for why every name and every word in it is kept rather than scrubbed,
     * and why it is put through {@link ToolArguments#oneLine} anyway: a
     * refusal can embed a failing provider's own message verbatim, and
     * flattening removes no name and no text while still stopping that
     * message from opening a line of its own. Scrubbing — deleting or
     * rewording a name or a word — is what never happens to either the
     * refusal or a hit; flattening is not scrubbing, and both get it.
     */
    private static String render(SearchPage page) {
        if (page.refusal() != null) {
            // Flattened, not scrubbed -- see the class comment's amendment
            // section. ToolArguments.oneLine collapses a line break; it
            // removes no name and no text, so an operator can still read
            // which rung failed and why.
            return "SEARCH FAILED — " + ToolArguments.oneLine(page.refusal());
        }
        if (page.hits().isEmpty()) {
            // page.total() tells an empty search apart from an empty page:
            // asking for page 9 of a search with 3 results is not "nothing
            // was found", and conflating the two would send a caller back to
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
            out.append('\n').append(render(hit)).append('\n');
        }
        return out.toString();
    }

    /** One hit, every field flattened onto one line before it reaches column
     *  zero — see the class comment's flattening section. */
    private static String render(Hit hit) {
        return ToolArguments.oneLine(hit.title()) + " — " + ToolArguments.oneLine(hit.url())
                + "\n" + ToolArguments.oneLine(hit.snippet());
    }
}
