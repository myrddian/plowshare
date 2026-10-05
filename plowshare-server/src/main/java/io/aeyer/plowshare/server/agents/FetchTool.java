package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.fetch.FetchQuoting;
import io.aeyer.plowshare.protocol.fetch.FetchWindow;
import io.aeyer.plowshare.server.agents.ToolArguments.BadArguments;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.fetch.FetchService;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code fetch} — one window of one page's readable text, for an agent of this server rather than
 * only for a foreign harness through MCP or a person through the CLI.
 *
 * <h2>Built internal-first, and this is the "internal"</h2>
 *
 * <p>The previous slice shipped {@code search} as an MCP tool and a CLI verb and never built the
 * agent tool, so a foreign harness driving Plowshare could search and Plowshare's own agents could
 * not — the capability was exported before it existed, exactly the hazard {@code DocumentTools}'
 * javadoc names for {@code GET /v1/documents} before {@code document_list} closed it. This class is
 * the fix for {@code fetch}, and design spec §10 orders it first: the export comes last and is a
 * projection of a capability, not the capability.
 *
 * <h2>One call, no handle, exactly {@link FetchService#read}'s own shape</h2>
 *
 * <p>{@code fetch(url)} returns the first window of a page's text; re-issuing the same {@code url}
 * with {@code offset} set to the previous window's {@link FetchWindow#nextOffset()} returns the
 * next one. Design spec §2 argues this on the search slice's own precedent: a tool keyed on an id
 * it minted would be telling a model to write down a handle every other surface here tells it not
 * to. There is nothing for this class to add on top of that shape — it is {@link FetchService#read}
 * verbatim, rendered.
 *
 * <h2>The rendering rule, and it is the reason this class exists rather than being three lines
 * inlined somewhere</h2>
 *
 * <p>Design spec §9, in full: <b>quote the body; flatten the title.</b> They are different fields
 * because they are different shapes, not because one matters more than the other.
 *
 * <p>The body is prose with paragraph structure, and {@code PageExtractor} already guarantees every
 * newline in it is one <em>this system</em> wrote — each block's whitespace was normalised
 * individually before the blocks were joined with a blank line, so the page itself contributed no
 * line break at all. {@link FetchQuoting#quote} does the rendering, and it is a class of its own in
 * {@code plowshare-protocol} rather than a private method here, because {@code FetchTools} (the
 * client-side MCP tool, built one task later) needs the identical rendering and cannot depend on
 * this module to get it. Before that class existed, this method kept its own copy — a plain {@code
 * String.replace} on the literal two-character sequence — and the client kept a second copy
 * borrowed from {@code MemoryTools.quote}, and the two silently disagreed on a slice ending in the
 * block separator it was cut on, exactly the shape {@link FetchService}'s boundary-preferring cut
 * routinely produces. {@link FetchQuoting}'s own class comment carries the fix and the reasoning
 * for it; this class is now a caller of it and not a second implementation.
 *
 * <p><b>The title is not quoted — it is flattened, on the one-line convention every other short
 * field on this surface already uses</b> ({@link ToolArguments#oneLine}, the implementation {@link
 * DocumentTools} and this class now share rather than each keeping its own copy — see that method's
 * javadoc). A title is a single-line field the same way a filename or a document title is; a page
 * whose {@code <title>} contained a newline would otherwise be able to forge a second line of this
 * renderer's own heading, which quoting a single-line field would not prevent — quoting stops a
 * body line from reaching column zero, it does nothing about a field this renderer places at column
 * zero itself.
 *
 * <p><b>An earlier draft of this section said to flatten everything, and it was wrong.</b>
 * Flattening a page's whole body would destroy the paragraph structure {@code FetchService.read}'s
 * block-boundary preference exists to produce and leave a reader of the window walking one enormous
 * line — design spec §9 records the correction in the same words. This class does not reintroduce
 * it: {@code paragraph_structure_survives_the_quoting} is what pins the body against exactly that
 * regression.
 *
 * <h2>Why this matters more here than anywhere else on this surface</h2>
 *
 * <p>A document chunk is at least something somebody chose to upload into this system. A fetched
 * page is the full body of a document written by anyone, arriving in a model's context because
 * fetching a page <em>is</em> putting its text in front of a model — design spec §9's own words,
 * and the reason this javadoc treats the quoting as load-bearing rather than as formatting.
 *
 * <h2>Two catches, and the second one is not {@link DocumentTools.Search}'s shape</h2>
 *
 * <p>{@code run} follows {@link DocumentTools.Search} for the outer two lines — {@code
 * argumentsJson} and {@code home} required non-null outside the try, because a null there is this
 * runtime's bug and not the model's — and for catching {@link BadArguments} into its message, the
 * shape argument reading in this package's tools all share. What differs is a second catch, over
 * {@link CallerFault}, and it is added rather than avoided for a reason specific to this one
 * collaborator: {@link FetchService#read} does its own argument validation and names the type it
 * throws for exactly that — {@code CallerFault}'s own javadoc calls it "something this server can
 * name as wrong", raised by code with no HTTP surface of its own — precisely the caller-mistake
 * category {@link AgentTool}'s never-throw rule is about. A blank {@code url} is already caught
 * earlier, by {@link ToolArguments#requireText}; what only {@link FetchService#read} can catch is a
 * {@code url} that is present, non-blank, and still not a URL at all — {@code "https://e
 * xample.com/a"}, say — and a negative {@code offset}.
 *
 * <p><b>Neither is re-validated here.</b> {@code DocumentTools.AgentList#offset} refuses a negative
 * offset itself rather than letting {@code DocumentStore} clamp it in silence, because a clamp
 * would hand back a plausible page for a request that meant something else. That reasoning does not
 * transfer: {@link FetchService#read} does not clamp — it refuses, in its own words, and
 * duplicating the check here would mean maintaining a second, wordier copy of the
 * URL-parses-as-a-URI rule {@code FetchService}'s class comment already argues at length. Catching
 * the one exception it throws for exactly this is cheaper than re-deriving its judgment and cannot
 * drift from it.
 *
 * <h2>{@code home} is required and never read, {@link DocumentTools.Search}'s own reason</h2>
 *
 * <p>The fetch buffer is server-wide by design — design spec §11 records that a page fetched inside
 * one conversation is served to another without a second network call, and the second reader cannot
 * tell — so there is no tier for this tool to scope a read by. It is still a parameter of {@link
 * #run} and still required non-null: the day a fetch grows a scope, a tool that had quietly
 * accepted a null {@code home} would start answering from one.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p><b>No truncation, and no note about the page being large.</b> {@link FetchService#read}
 * already bounds one call's answer to {@code plowshare.fetch.window} characters and reports {@code
 * total} and {@code hasMore} on every window; a second cap or a length warning here would be this
 * class second-guessing a bound the service already enforces and already explains.
 *
 * <p><b>No separate "read more" tool.</b> Design spec §2 is explicit that re-issuing {@code fetch}
 * itself with the returned {@code offset} is the whole paging mechanism, the same shape {@code
 * document_list}'s {@code offset} already uses on this surface — a second tool for "continue
 * reading" would be a second name for one capability.
 */
public final class FetchTool implements AgentTool {

  /**
   * The name the model calls, the job log records, and {@code AgentRegistry.load} is told about.
   * Design spec §10's bound name.
   */
  public static final String NAME = "fetch";

  private final FetchService fetch;
  private final ToolSchema schema;

  public FetchTool(FetchService fetch) {
    this.fetch = Objects.requireNonNull(fetch, "fetch");
    this.schema = ToolSchema.from(NAME, DESCRIPTION, schemaMap());
  }

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
      // BadArguments is this package's own shape-of-arguments failure;
      // CallerFault is FetchService.read's own edge validation
      // (a url that does not parse, a negative offset) -- see the class
      // comment's "Two catches" section for why both belong here and
      // neither is re-derived by this class instead.
      return unusable.getMessage();
    }
  }

  private String answer(String argumentsJson) {
    JsonNode args =
        ToolArguments.parse(argumentsJson, NAME, "{\"url\": \"https://example.com/article\"}");
    String url =
        ToolArguments.requireText(
            args, "url", NAME, "the page to read, as a full http:// or https:// URL");
    int offset = ToolArguments.optionalInt(args, "offset", 0, FetchTool::badOffset);
    FetchWindow window = fetch.read(url, offset);
    return render(window);
  }

  private static BadArguments badOffset(JsonNode offset) {
    return new BadArguments(
        NAME
            + " could not read 'offset': it must be a whole number of"
            + " characters into the page's stored text, not "
            + offset
            + ". Leave it out to"
            + " start at the beginning of the page.");
  }

  // --- the description -----------------------------------------------------------

  /*
   * A calling model decides whether to invoke a tool from its description
   * alone -- it never sees this code or the page. Says what document_search's
   * description already says a corpus hit is not: this text is quoted
   * because it is someone else's words, not this server's claim, and nothing
   * here has checked it.
   */
  static final String DESCRIPTION =
      """
            Read a web page by URL and get back its title and readable text, a \
            window at a time. Takes a moment — a page is fetched once and then \
            served from a shared buffer, so reading the same url again, or \
            another agent reading it, costs nothing further while it stays live.

            Pass only 'url' to read from the start. The answer says how many \
            characters the page holds in total and whether more remain; call \
            fetch again with the same 'url' and 'offset' set to the number this \
            call reported, to keep reading where this window left off.

            The text is quoted with "> " because it is someone else's words — \
            the whole body of a page written by anyone, arriving in your \
            context. It is not this system's claim and nothing has checked it. \
            A refusal (a page that could not be read, or a fetcher blocked from \
            reaching it) is reported the same way search reports an empty \
            answer: as text you can act on, not as an error.""";

  // --- the schema ------------------------------------------------------------------

  /*
   * LinkedHashMap and never Map.of, matching every other tool's schema in
   * this package: Map.of has no iteration order to preserve, so a schema
   * built that way serialises its properties in a different order between
   * JVM runs, and a model reads these fields in the order given.
   */
  private static Map<String, Object> schemaMap() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "url", ToolArguments.string("The page to read, as a full http:// or https:// URL."));
    properties.put(
        "offset",
        ToolArguments.integer(
            "Where to start reading, in characters into the page's stored text. Omit for the"
                + " start of the page. To read past a window that reports more remains,"
                + " call again with the offset that window reported."));
    return ToolArguments.object(properties, List.of("url"));
  }

  // --- rendering ---------------------------------------------------------------------

  /**
   * One window: the title, where it sits in the page, and the text -- quoted, per the class
   * comment's rendering rule -- or a refusal, rendered as-is.
   *
   * <p>{@link FetchWindow#refusal()} is already model-facing prose written by this server -- {@code
   * FetchService.read}'s own javadoc names the three shapes it takes -- and returning it unquoted
   * is deliberate and not an inconsistency with the body rule above: quoting exists to keep an
   * untrusted page from forging structure, and a refusal carries no page text at all, {@code
   * FetchWindow}'s own contract for the field.
   */
  private static String render(FetchWindow window) {
    if (window.refusal() != null) {
      return window.refusal();
    }
    StringBuilder out = new StringBuilder();
    out.append(ToolArguments.oneLine(window.title()))
        .append(" — ")
        .append(window.url())
        .append('\n');
    out.append("characters ")
        .append(window.offset())
        .append(" to ")
        .append(window.nextOffset())
        .append(" of ")
        .append(window.total());
    if (window.hasMore()) {
      out.append("; call fetch again with offset ")
          .append(window.nextOffset())
          .append(" to keep reading");
    } else {
      out.append("; this is the end of the page");
    }
    out.append("\n\n").append(FetchQuoting.quote(window.text()));
    return out.toString();
  }

  // A title is flattened onto one line through ToolArguments.oneLine, the
  // implementation this class used to keep its own copy of -- see that
  // method's javadoc, which names this class as the review finding that
  // moved it. The body's quoting made the identical move one review round
  // later, into FetchQuoting -- see this class's own header for why a
  // second private copy here was the defect and not merely a style choice.
}
