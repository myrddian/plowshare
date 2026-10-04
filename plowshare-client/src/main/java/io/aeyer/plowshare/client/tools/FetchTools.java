package io.aeyer.plowshare.client.tools;

import static io.aeyer.plowshare.client.tools.MemoryTools.oneLine;
import static io.aeyer.plowshare.client.tools.Schemas.integer;
import static io.aeyer.plowshare.client.tools.Schemas.object;
import static io.aeyer.plowshare.client.tools.Schemas.string;

import io.aeyer.plowshare.client.ServerClient;
import io.aeyer.plowshare.client.mcp.ToolRegistry;
import io.aeyer.plowshare.protocol.fetch.FetchQuoting;
import io.aeyer.plowshare.protocol.fetch.FetchWindow;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One tool, {@code fetch}, exporting {@code POST /v1/fetch} to a foreign harness over MCP — the
 * same capability {@code FetchTool} already gives this server's own agents in-process, on design
 * spec §10's order: internal first, export last.
 *
 * <h2>{@code FetchWindow} verbatim, on the reasoning it was placed for</h2>
 *
 * <p>{@link FetchWindow}'s own javadoc explains why it lives in {@code plowshare-protocol} rather
 * than beside {@code FetchService}: this class is the reason. {@code plowshare-client} depends on
 * {@code plowshare-protocol} and nothing of the server, so a record defined server-side would be a
 * type this class had no way to name — {@code SearchPage}'s own history is the precedent that got
 * the placement right the first time here.
 *
 * <h2>No handle, on {@code search}'s own precedent</h2>
 *
 * <p>{@code fetch(url)} answers the first window of a page; re-issuing the same {@code url} with
 * {@code offset} set to the previous answer's {@code nextOffset} answers the next one. Nothing here
 * mints an id for a caller to write down — the same shape {@link SearchTools} already argues for a
 * page of search results, and for the identical reason: a handle is one more thing a model has to
 * remember correctly across calls, when the page's own {@code url} and the offset it was just told
 * already say everything needed to keep reading.
 *
 * <h2>Quote the body, flatten the title — {@code FetchTool}'s rule, rendered identically here</h2>
 *
 * <p>{@code FetchTool} (this server's in-process agent tool, built one task earlier) argues the
 * rule in full: the body is prose with paragraph structure a caller paging through a long document
 * depends on, so it is quoted line by line with {@code "> "} rather than flattened onto one line —
 * flattening would destroy exactly the structure {@code FetchService.read}'s block-boundary
 * preference exists to produce. The title is a single-line field the same way a filename is, so it
 * is flattened instead: quoting a body stops a body <em>line</em> from forging a line at column
 * zero, and does nothing to stop a title carrying a newline from forging a second line of this
 * renderer's own heading, which only flattening prevents.
 *
 * <p>{@link #render} is {@code FetchTool.render} rewritten against this module's own helper for the
 * title — {@link MemoryTools#oneLine} in place of {@code ToolArguments.oneLine} — but <b>not</b>
 * against a second copy of the body's quoting. An earlier version of this class reused {@code
 * MemoryTools.quote} for that, on the reasoning that the two modules share no code and must not:
 * {@code plowshare-client} cannot depend on {@code plowshare-server}, so each side kept its own
 * implementation and this javadoc asserted the two shapes could not drift apart. They already had:
 * {@code MemoryTools.quote} strips the text and splits on {@code \R}, the server's original quoting
 * did neither, and {@code FetchService.window}'s boundary-preferring cut routinely hands back a
 * slice ending in the very {@code "\n\n"} it cut on — a case none of the six fixtures on either
 * side happened to exercise, so the two renderers disagreed in silence on exactly the common path.
 * {@link FetchQuoting#quote}, in {@code plowshare-protocol} beside {@link FetchWindow} itself, is
 * the fix: both this class and the server's {@code FetchTool} call the one implementation, so "must
 * not drift" is a fact about the dependency graph rather than a claim about two authors'
 * discipline. {@code FetchToolsTest.a_body_line_cannot_forge_a_line_at_column_zero} and its
 * siblings still pin the shape directly — a shared implementation is not a reason to stop testing
 * it, only a reason a regression would now have to break both sides of the wire identically rather
 * than only one.
 *
 * <h2>{@code cli.Commands} calls {@link #render} directly, and that is the one place on this
 * surface where a CLI verb reuses a tool's renderer</h2>
 *
 * <p>{@code Commands}' own class comment argues at length why the terminal does not otherwise reuse
 * a tool's handler or its rendering: an MCP answer's prose is written for a model holding that
 * menu, and a sentence like {@code agent_run}'s "ask agent_poll, then agent_result" names tools a
 * person at this terminal does not have. That reasoning does not apply here. {@code fetch} is the
 * CLI verb's own name as well as the tool's, so the very sentence this renders — "call fetch again
 * with offset N to keep reading" — is correct advice at a shell prompt too; and a page's title and
 * body are the same untrusted bytes wherever they surface, so the one part of {@code fetch} that
 * has to look identical to a terminal and to a model is exactly the part {@link #render} produces.
 * Reusing it, rather than keeping a second copy of the quoting rule that could quietly drift from
 * this one, is what makes "a person at the terminal and a model over MCP see the same shape" a fact
 * about the code instead of a sentence in two javadocs that happen to agree today.
 *
 * @see SearchTools for the sibling this class is built beside: one tool, one description, no policy
 *     about which provider or which fetcher answered ever reaching the model
 */
public final class FetchTools {

  /**
   * The offset sent when a caller omits one — the start of the page, and the only sane default: the
   * server counts characters from zero and refuses a negative offset outright. Public for {@link
   * SearchTools#DEFAULT_PAGE_SIZE}'s own reason: {@code cli.Commands} reads this rather than
   * declaring a second zero that could, in principle, stop agreeing with this one.
   */
  public static final int DEFAULT_OFFSET = 0;

  private final ServerClient server;

  public FetchTools(ServerClient server) {
    this.server = server;
  }

  /** Add the one tool this class offers. */
  public void registerOn(ToolRegistry registry) {
    registry.register("fetch", FETCH_DESCRIPTION, fetchSchema(), this::fetch);
  }

  // --- the description -----------------------------------------------------

  /**
   * The whole of what a model is told about this tool — {@code FetchTool}'s own description,
   * unchanged in substance: a foreign harness and this server's own agents are told the identical
   * thing about the identical capability.
   */
  static final String FETCH_DESCRIPTION =
      """
            Read a web page by URL and get back its title and readable text, a \
            window at a time. Takes a moment — a page is fetched once and then \
            served from a shared buffer, so reading the same url again costs \
            nothing further while it stays live.

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

  // --- the schema ------------------------------------------------------------

  /*
   * LinkedHashMap and never Map.of, on this package's own standing rule: a
   * model reads these fields in the order given, and Map.of preserves no
   * order at all.
   */
  static Map<String, Object> fetchSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("url", string("The page to read, as a full http:// or https:// URL."));
    properties.put(
        "offset",
        integer(
            "Where to start reading, in characters into the page's stored text. Omit for"
                + " the start of the page. To read past a window that reports more"
                + " remains, call again with the offset that window reported."));
    return object(properties, List.of("url"));
  }

  // --- the handler -------------------------------------------------------------

  /** {@code fetch} — one window of one page, or the refusal explaining why not. */
  public Object fetch(Map<String, Object> args) {
    String url = required(args, "url");
    int offset = integerOrDefault(args, "offset", DEFAULT_OFFSET);

    try {
      return render(server.fetch(url, offset));
    } catch (IOException unreachable) {
      String uncertainty = MemoryTools.uncertainty(unreachable);
      if (uncertainty != null)
        throw new MemoryTools.ServerUnreachableException(uncertainty, unreachable);
      // Thrown rather than rendered, on SearchTools' own reasoning:
      // StdioTransport turns a thrown handler into a result carrying
      // isError, which is what tells a model this is infrastructure and
      // not an answer -- a page that could not be read is a very
      // different fact from a page never asked for.
      throw new IllegalStateException(
          "could not reach the Plowshare server at "
              + server.baseUrl()
              + " to fetch — "
              + describe(unreachable)
              + ". This says nothing about the page itself:"
              + " it was never asked for. Check the server is running, then try"
              + " again.",
          unreachable);
    }
  }

  // --- rendering ---------------------------------------------------------------

  /**
   * One window: the title (flattened) and where it sits in the page, or a refusal, rendered as-is —
   * {@code FetchTool.render}'s own shape. The title is flattened with this module's own {@link
   * MemoryTools#oneLine} because {@code plowshare-client} cannot depend on {@code plowshare-server}
   * to share that one line; the body's quoting is {@link FetchQuoting#quote}, shared rather than
   * copied — see the class comment's second section for why a second copy of that one was the
   * actual defect a review found here.
   *
   * <p>{@link FetchWindow#refusal()} is already model-facing prose composed by the server, and
   * returning it unquoted is deliberate rather than an inconsistency with the body rule below:
   * quoting exists to keep an untrusted <em>page</em> from forging structure, and a refusal carries
   * no page text at all.
   *
   * <p>Public rather than package-private, unlike {@code SearchTools.render}: {@code cli.Commands}
   * is a different package and calls this method directly — see the class comment's last section
   * for why that one reuse is not the drift the rest of that class's own javadoc warns against.
   */
  public static String render(FetchWindow window) {
    if (window.refusal() != null) {
      return window.refusal();
    }
    StringBuilder out = new StringBuilder();
    out.append(oneLine(window.title())).append(" — ").append(window.url()).append('\n');
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

  // --- arguments -----------------------------------------------------------------

  private static String required(Map<String, Object> args, String name) {
    Object value = args.get(name);
    String text = value == null ? null : value.toString();
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("'" + name + "' is required and must not be empty");
    }
    return text;
  }

  /**
   * An optional integer argument, or {@code fallback} when it is absent — {@link SearchTools}' own
   * helper, kept as a second copy on {@code Schemas}' own reasoning for why this family's small
   * repeated shapes stay duplicated rather than shared.
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
      throw new IllegalArgumentException("'" + name + "' should be a whole number, not " + value);
    }
  }

  /** The message, or the exception's own type when it carries none. */
  private static String describe(Throwable t) {
    String message = t.getMessage();
    return message == null || message.isBlank() ? t.toString() : message;
  }
}
