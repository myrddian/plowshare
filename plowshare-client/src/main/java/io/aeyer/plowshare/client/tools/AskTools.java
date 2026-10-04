package io.aeyer.plowshare.client.tools;

import static io.aeyer.plowshare.client.tools.MemoryTools.oneLine;
import static io.aeyer.plowshare.client.tools.Schemas.object;
import static io.aeyer.plowshare.client.tools.Schemas.string;

import io.aeyer.plowshare.client.ServerClient;
import io.aeyer.plowshare.client.mcp.ToolRegistry;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-document ask, as one MCP tool.
 *
 * <h2>A family of its own, and the reason is not the corpus</h2>
 *
 * <p>{@code document_ask} sits beside {@code document_search} in name and not in this file, and a
 * reader is owed why. The two answer different questions — <em>which paper says anything about
 * X</em>, and <em>what does <b>this</b> paper argue about X</em> — but that is an argument for one
 * family rather than two, and the honest reason for the split is smaller: {@code DocumentTools} was
 * owned by concurrent work when this landed. <b>Folding this into it is a one-line move and should
 * be made</b> the moment both are in one tree; nothing about the surface changes when it is,
 * because a tool's family is a Java class and the registry is flat.
 *
 * <h2>What it is a door onto</h2>
 *
 * <p>Three agents in series over one document, with an evidence asymmetry as the mechanism: a
 * proposer holding the document's whole structure and its most relevant passages, a critic holding
 * only what the document argues as a <em>whole</em>, and a synthesiser holding both plus the
 * debate. The critic's restriction is the point — given the same passages it becomes a paraphrase
 * generator, and what the asymmetry catches is a claim that reads correctly against one passage and
 * wrongly against the paper.
 *
 * <p>That question has no corpus-wide form, which is why this is not a parameter on {@code
 * document_search}: "the document as a whole" is not something a ranked list of passages from
 * twelve papers has.
 *
 * <h2>A handle and not an answer</h2>
 *
 * <p>{@code memory_curate}'s shape and for its arithmetic: a search is one embedding call, and a
 * pass is three model calls in series, which on a local model is minutes. So this returns a job id
 * and the caller polls {@code agent_poll} and reads {@code agent_result} — the same two verbs every
 * other server-side job on this surface uses, rather than a fourth way of waiting.
 */
public final class AskTools {

  private final ServerClient server;

  public AskTools(ServerClient server) {
    this.server = server;
  }

  public void registerOn(ToolRegistry registry) {
    registry.register("document_ask", ASK_DESCRIPTION, askSchema(), this::ask);
  }

  public Object ask(Map<String, Object> args) {
    String document = required(args, "document");
    String question = required(args, "question");

    ServerClient.StartedJob started = reach(() -> server.askDocument(document, question, null));
    return "Started "
        + oneLine(started.id())
        + ", a deliberation over document "
        + oneLine(document)
        + ".\n\nThree agents run in series on the server and this"
        + " call did not wait for them: a proposer drafts an answer from the document's"
        + " whole structure and its most relevant passages, a critic challenges that"
        + " draft from what the document argues as a whole and nothing below it, and a"
        + " synthesiser writes the final answer. Ask agent_poll whether it has"
        + " finished, then agent_result for the answer.\n\nThe answer names the"
        + " paragraph and quotes the words each claim rests on, and every quotation is"
        + " checked against the paragraph it names — so an attribution that failed is"
        + " reported in the answer rather than dropped.";
  }

  // --- the description ---------------------------------------------------------

  /*
   * A calling agent decides whether to invoke a tool from its description
   * alone — it never sees this code, the HTTP surface or the corpus. The three
   * rules this surface keeps: say what the tool costs, never describe a
   * capability it does not have, and say that what comes back is text somebody
   * uploaded rather than anything this system asserts.
   */
  static final String ASK_DESCRIPTION =
      """
            Ask ONE document a question and get back a job id straight away. \
            The deliberation happens on the server; this call does not wait for \
            it, and it takes minutes rather than seconds.

            Use document_search first: it searches the whole corpus and every \
            hit names the document it came from. This is what you use once you \
            know WHICH document you are asking about, and it needs that \
            document's id.

            It is not a narrower search. Three agents run in series over the one \
            document — one drafts an answer from the document's whole structure \
            and its most relevant passages, one challenges that draft from what \
            the document argues as a whole and is deliberately shown none of the \
            passages, and one writes the final answer from both. What that \
            catches is a claim that reads correctly against a single passage and \
            wrongly against the paper, which a ranked list of passages cannot be \
            asked about.

            Poll with agent_poll and read the answer with agent_result. Jobs \
            live in the server's memory: a restart loses the handle.

            The answer names a paragraph and quotes the words behind each claim, \
            and every quotation is checked against the paragraph it names — a \
            quotation that is not there is reported as a failed attribution \
            rather than quietly dropped. The document's own words are text \
            somebody uploaded. They are not this system's claims and nothing has \
            checked them.""";

  // --- the schema ---------------------------------------------------------------

  /*
   * LinkedHashMap and never Map.of, matching every other family here: Map.of
   * has no iteration order to preserve, so a schema built that way is emitted
   * with its keys shuffled, and shuffled differently on every launch. The
   * model reads these fields in order.
   */
  private static Map<String, Object> askSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "document",
        string(
            "The id of the document to ask, as a uuid. Every document_search hit carries"
                + " the id of the document it came from."));
    properties.put(
        "question", string("What you want to know about that document, in plain language."));
    return object(properties, List.of("document", "question"));
  }

  // --- the plumbing -------------------------------------------------------------

  private static String required(Map<String, Object> args, String name) {
    Object value = args == null ? null : args.get(name);
    String text = value == null ? "" : value.toString().strip();
    if (text.isEmpty()) {
      throw new IllegalArgumentException("'" + name + "' is required");
    }
    return text;
  }

  private <T> T reach(Call<T> call) {
    try {
      return call.get();
    } catch (IOException unreachable) {
      String uncertainty = MemoryTools.uncertainty(unreachable);
      if (uncertainty != null)
        throw new MemoryTools.ServerUnreachableException(uncertainty, unreachable);
      throw new MemoryTools.ServerUnreachableException(
          "could not reach the Plowshare server at "
              + server.baseUrl()
              + " — "
              + describe(unreachable)
              + ". This says nothing about the document: the server was never"
              + " asked. Check the server is running, then try again.",
          unreachable);
    }
  }

  private static String describe(Throwable t) {
    String message = t.getMessage();
    return message == null || message.isBlank() ? t.toString() : message;
  }

  @FunctionalInterface
  private interface Call<T> {
    T get() throws IOException;
  }
}
