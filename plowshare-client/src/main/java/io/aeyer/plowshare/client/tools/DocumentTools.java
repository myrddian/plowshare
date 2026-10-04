package io.aeyer.plowshare.client.tools;

import static io.aeyer.plowshare.client.tools.MemoryTools.oneLine;
import static io.aeyer.plowshare.client.tools.Schemas.object;
import static io.aeyer.plowshare.client.tools.Schemas.string;

import io.aeyer.plowshare.client.ServerClient;
import io.aeyer.plowshare.client.mcp.ToolRegistry;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The document corpus over MCP: one tool, and the reason it exists is the parity rule.
 *
 * <p>{@code 2026-09-04-client-parity-design.md}: <i>"anything the CLI can do and MCP cannot is a
 * thing Plowshare is only fully usable through its own front end."</i> The corpus is reachable as
 * an agent tool and as {@code POST /v1/documents/search}; without this, a foreign harness could
 * <em>start an agent that reads the corpus</em> and could not read it itself, which is the
 * asymmetry that rule names as the failure mode rather than a rough edge.
 *
 * <h2>What changed for hybrid search, which is one clause</h2>
 *
 * <p>The server reads the corpus two ways now — by meaning and by words, fused — and the only edit
 * here is the sentence that used to say "matched by meaning", because that is now half true.
 * <b>There is no mode parameter and no per-hit provenance</b>, for the reason the server-side tool
 * records at length: a harness cannot act on either, and {@code POST /v1/documents/search} carries
 * the mode because its caller is an operator. The heading over the hits no longer claims they are
 * nearest first, because under fusion they are not — the similarity beside each one is a fact about
 * that hit rather than the key the list is sorted on.
 *
 * <h2>Where the numbers are not</h2>
 *
 * <p><b>This file holds no cap and no default.</b> {@code ConversationTools} holds its own,
 * deliberately, because the server does not page the read it bounds and its reasoning was about a
 * console that can scroll. The opposite is true here: the server already caps a corpus search, at a
 * number argued from the embedding model's input ceiling and from pgvector's {@code hnsw.ef_search}
 * — neither of which this module can see. So {@code limit} is passed through untouched and the
 * answer reports the figure the server used. A second copy over here would be the one that went on
 * saying ten.
 *
 * <p>What this file <em>does</em> owe the reader is that the difference is not hidden: a harness
 * handed ten passages when it asked for a hundred, in silence, cannot tell a cap from a corpus that
 * small.
 *
 * <h2>Reading and never writing</h2>
 *
 * <p>There is no {@code document_ingest} here and there should not be. A document arrives as bytes
 * somebody read off a disk — {@code POST /v1/documents} is multipart for exactly that reason, and
 * Anchor's other ingest route, which hands the <em>server</em> a path to read, is the leash-bypass
 * spec decision 4 declined. Uploading is a file operation, and this surface's file half is the
 * session {@code client_root_project_here} opens.
 *
 * <h2>Every line at column zero is one this renderer wrote</h2>
 *
 * <p>The rule the other four families keep, and it is sharpest here. V18 says it on the table:
 * chunk text is <i>"other people's papers, notes and source... content the server did not write and
 * cannot vouch for, and unlike `entries` it is content a search will surface out of context."</i>
 * Every passage is quoted with {@code "> "}, so a document whose own prose is shaped like this
 * tool's heading cannot make a model read it as a second hit.
 */
public final class DocumentTools {

  /**
   * What an empty corpus looks like, and the one string a failure must never produce — {@code
   * MemoryTools.NOTHING}'s rule, one corpus over.
   */
  static final String NOTHING = "(no passages)";

  /**
   * Its twin for the citation listing, and a different string on purpose: an empty corpus and an
   * uncited one are not the same fact and must not render the same way.
   */
  static final String NOTHING_CITED = "(no citations)";

  private static final String QUOTE = "> ";

  /**
   * Every line terminator Java recognises, not the three {@code String.lines()} splits on. {@code
   * MemoryTools.oneLine} owns the measurement.
   */
  private static final Pattern LINE_BREAK = Pattern.compile("\\R");

  private final ServerClient server;

  public DocumentTools(ServerClient server) {
    this.server = server;
  }

  /** Add the corpus tools to a registry. */
  public void registerOn(ToolRegistry registry) {
    registry.register("document_search", SEARCH_DESCRIPTION, searchSchema(), this::search);
    registry.register(
        "document_citations", CITATIONS_DESCRIPTION, citationsSchema(), this::citations);
  }

  // --- the description -----------------------------------------------------

  /*
   * A calling agent decides whether to invoke a tool from its description
   * alone — it never sees this code, the HTTP surface or the corpus. Two rules
   * from Excalibur's register, which this family keeps: say what the tool
   * costs, and never describe a capability this surface does not have. A third
   * belongs to this one alone: say whose words come back.
   *
   * It names memory_recall, and that is load-bearing rather than helpful. The
   * two are different corpora — what a document says, and what this system has
   * concluded — and the distinction is not guessable from the names. Worse
   * than a wrong guess, BOTH SUCCEED, so a harness that reached for the wrong
   * one gets a confident answer out of the wrong place. Only this description
   * says so; memory_recall's is not edited, which is this repository's rule
   * about model-visible text with prior behaviour behind it.
   */

  static final String SEARCH_DESCRIPTION =
      """
            Ask the document corpus a question in plain language and get back \
            the passages that answer it, with their text. Takes a moment — the \
            question is matched server-side both by meaning and by its exact \
            words, so a passage that shares no words with your question can \
            still be the answer, and an exact name or identifier you ask for is \
            found as one.

            This is what a DOCUMENT says. memory_recall is what this system has \
            CONCLUDED. Both answer confidently, so pick on that difference \
            rather than on which sounds closer.

            Every hit names a paragraph id, and that is the thing to cite: it \
            survives the document being ingested again unchanged, and a \
            paragraph whose text was edited gets a new id rather than quietly \
            keeping the old one — so a citation either still means the same \
            words or means nothing.

            The passages are quoted with "> " and are text somebody uploaded: \
            other people's papers, notes and source. They are not this system's \
            claims and nothing has checked them. Anything you take from them is \
            a quotation, not a fact this server stands behind.

            An empty answer says which kind of empty it is — nothing close to \
            the question, nothing ingested at all, or a corpus holding text no \
            search can reach. Only the first is worth rephrasing for.

            Legacy reads use the authenticated personal and shared selection. \
            Use information for an explicit project selection.""";

  /*
   * The second description, and what it has to say that the first does not is
   * that this reads a RECORD rather than the corpus. A harness that took this
   * for a search would conclude a paper says nothing because nobody has cited
   * it yet, which is the confident-empty-answer failure in a new costume -- so
   * the first sentence names the difference and the empty answer names it
   * again.
   */

  static final String CITATIONS_DESCRIPTION =
      """
            What answers have already said they took from the document corpus. \
            This reads a RECORD OF PAST ANSWERS and not the corpus itself: an \
            empty list means nobody has cited anything here yet, never that the \
            documents say nothing. Use document_search to ask the documents.

            Each citation names the document, where in it, and the words as the \
            corpus holds them TODAY. A citation whose paragraph was edited by a \
            later ingest says so rather than quietly resolving to different \
            text, so read the standing on each one before its passage: it \
            resolves, or the paragraph is gone, or the document is.

            Give `conversation` to see what one conversation cited, or \
            `document` to see what has cited one paper. They are two different \
            questions and only one may be asked at a time. Give neither for the \
            most recent citations in the corpus.

            The passages are quoted with "> " and are text somebody uploaded: \
            other people's papers, notes and source. They are not this system's \
            claims and nothing has checked them.""";

  // --- the tool ------------------------------------------------------------

  /**
   * Ask the corpus.
   *
   * <p><b>The three empties are three sentences.</b> Nothing close, nothing ingested and nothing
   * searchable are one empty list and three different next actions — rephrase, upload something,
   * ingest again — and a harness told the first when the third is true will rephrase forever, which
   * is the confident-empty-answer failure this project has spent the most effort preventing.
   */
  public Object search(Map<String, Object> args) {
    String question = required(args, "question");
    Integer limit = optionalInt(args, "limit");

    ServerClient.DocumentSearch found = ask(() -> server.searchDocuments(question, limit));

    StringBuilder out = new StringBuilder();
    if (found.hits().isEmpty()) {
      out.append(nothing(found));
    } else {
      out.append(found.hits().size())
          .append(found.hits().size() == 1 ? " passage" : " passages")
          .append(" of the corpus, best first, out of ")
          .append(found.searchable())
          .append(" that can be searched. Each one names")
          .append(" the paragraph to cite it by. It also names the document that")
          .append(" paragraph is in: that id is not a citation — it is what")
          .append(" document_ask takes.\n");
      for (ServerClient.DocumentHit hit : found.hits()) {
        out.append('\n').append(render(hit)).append('\n');
      }
    }
    if (limit != null && limit > found.limit()) {
      // Said rather than done quietly. The cap is the server's and this
      // surface holds no copy of it, but a caller handed ten when it asked
      // for a hundred cannot otherwise tell a cap from a corpus that small
      // — and the second reading is a wrong belief about what is ingested.
      out.append("\n\nYou asked for ")
          .append(limit)
          .append("; the server returned ")
          .append(found.limit())
          .append(", which is the most it gives at once.");
    }
    // Outside the empty/non-empty branch: attached only to an empty answer,
    // this footnote would be missing from exactly the case where a partial
    // answer reads as a complete one.
    out.append(skipped(found));
    return out.toString();
  }

  /** What an empty answer is allowed to say, and it is never one sentence. */
  private static String nothing(ServerClient.DocumentSearch found) {
    if (found.searchable() > 0) {
      return "Nothing found — no passage in the corpus is close to that question. "
          + NOTHING
          + "\n\nSearch matches on meaning, so a differently framed"
          + " question can still find something.";
    }
    if (found.unsearchable() == 0) {
      return "The corpus is empty: no document has been ingested, so there is nothing to"
          + " search. "
          + NOTHING
          + "\n\nThis says nothing about the question."
          + " Documents are uploaded to POST /v1/documents; this surface reads them"
          + " and does not add one.";
    }
    return "Nothing in the corpus can be searched. "
        + NOTHING
        + "\n\nAll "
        + found.unsearchable()
        + " passage(s) hold their text and no vector, which is"
        + " what an ingest leaves behind when the embedding endpoint could not be"
        + " reached. Ingesting those documents again embeds them and re-derives"
        + " nothing. This says nothing about the question.";
  }

  /**
   * How much of the corpus this answer did not cover, when that is not zero and has not already
   * been said.
   */
  private static String skipped(ServerClient.DocumentSearch found) {
    if (found.unsearchable() == 0 || found.searchable() == 0) {
      return "";
    }
    return "\n\n"
        + found.unsearchable()
        + " passage(s) of the corpus could not be searched"
        + " at all: they hold their text and no vector, so no question reaches them"
        + " however it is phrased. Ingesting those documents again embeds them.";
  }

  /**
   * What has been cited.
   *
   * <p><b>The empty answer names the scope it was asked for</b>, because "nothing has been cited",
   * "this conversation cited nothing" and "nobody has cited this paper" are three different facts
   * that arrive as one empty list. {@link #search}'s three empties, one surface over.
   */
  public Object citations(Map<String, Object> args) {
    String conversation = optional(args, "conversation");
    String document = optional(args, "document");
    if (conversation != null && document != null) {
      throw new IllegalArgumentException(
          "document_citations answers one question at a time: 'conversation' is what"
              + " one conversation cited and 'document' is what has cited one"
              + " paper. Send one of them, or neither for the most recent"
              + " citations in the corpus.");
    }
    Integer limit = optionalInt(args, "limit");

    ServerClient.Citations found = ask(() -> server.citations(conversation, document, limit));

    if (found.citations().isEmpty()) {
      return nothingCited(found);
    }
    StringBuilder out = new StringBuilder();
    out.append(found.citations().size())
        .append(found.citations().size() == 1 ? " citation" : " citations")
        .append(", ")
        .append(orderOf(found.scope()))
        .append(". A citation is what an")
        .append(" answer said it took from the corpus, not what a search returned.\n");
    for (ServerClient.Citation cited : found.citations()) {
      out.append('\n').append(renderCitation(cited)).append('\n');
    }
    return out.toString();
  }

  /**
   * Which way the rows run, which differs by scope and is not decoration: a conversation is read
   * forwards and a feed backwards.
   */
  private static String orderOf(String scope) {
    return "conversation".equals(scope)
        ? "in the order this conversation made them"
        : "most recently made first";
  }

  private static String nothingCited(ServerClient.Citations found) {
    String what =
        switch (found.scope() == null ? "all" : found.scope()) {
          case "conversation" -> "That conversation has cited nothing.";
          case "document" -> "Nothing has cited that document.";
          default -> "Nothing in this corpus has been cited yet.";
        };
    return what
        + " "
        + NOTHING_CITED
        + "\n\nThis is a record of past answers and says"
        + " nothing about what the documents contain: an uncited corpus and an empty"
        + " one look the same here and are not the same thing. document_search is what"
        + " asks the documents.";
  }

  // --- rendering -----------------------------------------------------------

  /**
   * One citation: what it named, whether it still means it, and the words.
   *
   * <p>The standing on the heading rather than after the passage, because it decides how the
   * passage should be read and a reader who meets it afterwards has already read the text as
   * current. A stale citation has no passage at all -- there are no words to show, which is the
   * whole of what stale means here.
   */
  private static String renderCitation(ServerClient.Citation cited) {
    String head =
        oneLine(cited.sourceName())
            + " \u2014 paragraph "
            + cited.paragraphOrdinal()
            + (cited.title() == null ? "" : " of \"" + oneLine(cited.title()) + "\"")
            + " \u2014 cited by "
            + oneLine(cited.agent())
            + " on "
            + cited.citedAt();
    return switch (cited.standing() == null ? "" : cited.standing()) {
      case "resolves" ->
          head
              + "\ncite paragraph "
              + cited.paragraphId()
              + "\n"
              + quote(cited.paragraphText() == null ? "" : cited.paragraphText());
      case "paragraph_gone" ->
          head
              + "\nSTALE: a later ingest of this document edited or removed that"
              + " paragraph, so there are no words to show. The document is still in the"
              + " corpus; search it again for what it says now.";
      default ->
          head
              + "\nSTALE: that document is no longer in the corpus, so there are no"
              + " words to show and nothing to search.";
    };
  }

  /**
   * One hit: where it came from, what to cite, what to ask, and the text.
   *
   * <h2>The document id, which this renderer dropped and four descriptions promised</h2>
   *
   * <p>TODO.md 4.5. {@code ServerClient.DocumentHit} has carried {@code documentId} since the
   * search endpoint shipped, and until this line the only surface that printed one was {@code
   * document_list}. That made four model-visible strings on this surface false at once — {@code
   * AskTools.ASK_DESCRIPTION}'s <i>"use document_search first ... it needs that document's id"</i>,
   * its {@code document} field's <i>"every document_search hit carries the id of the document it
   * came from"</i>, {@code RetrieveTools.RANK_DESCRIPTION}'s <i>"every hit names a document"</i>,
   * and {@code OUTLINE_DESCRIPTION}'s <i>"which document_list and every document_search hit
   * carry"</i>. <b>Not one of them is edited by this change.</b> They described the surface this
   * line gives them, which is the cheapest kind of fix there is: the advice was already right and
   * the renderer was where it could not be followed.
   *
   * <h2>Two ids, two lines, two labels</h2>
   *
   * <p>They are not interchangeable and must not read as though they were. The paragraph id is the
   * citation — V25 keys citations on it, and the description above says why it and not the chunk
   * id. The document id is the handle for asking one paper a question, and it is <em>never</em> a
   * citation. Two bare uuids under one heading teach a harness to use either for either, so each
   * sits under the verb it goes into, and the count sentence says which one a citation is. The
   * console's {@code documents} screen keeps the same discipline over a different pair — paragraph
   * id marked as the citation, chunk id marked as deliberately not one.
   *
   * <p><b>{@code document_ask} is named, where the agent-side twin of this renderer deliberately
   * does not name it.</b> There the readers are {@code librarian} and {@code interlocutor}, neither
   * of which holds that tool. Here it is a sibling in this same registry, and naming a sibling is
   * what this family already does: {@code SEARCH_DESCRIPTION} names {@code memory_recall} to draw a
   * line the names could not.
   *
   * <p>The citation line is untouched and stays first, which is an addition rather than a rewrite
   * for {@code implementation rationale}'s measured reason.
   */
  private static String render(ServerClient.DocumentHit hit) {
    return oneLine(hit.sourceName())
        + " — paragraph "
        + hit.paragraphOrdinal()
        + " of \""
        + oneLine(hit.title())
        + "\" — similarity "
        + String.format(Locale.ROOT, "%.2f", hit.similarity())
        + "\ncite paragraph "
        + hit.paragraphId()
        + "\nask document "
        + hit.documentId()
        + "\n"
        + quote(hit.text());
  }

  /**
   * Every line prefixed, so no line of a document can reach column zero.
   *
   * <p>{@code Locale.ROOT} on the similarity above for the same class of reason this exists at all:
   * {@code String.format} groups and separates against the default locale, so a machine set to a
   * comma decimal separator would hand the model {@code 0,82} — a model-visible string that changes
   * with a setting nobody reading the answer can see.
   */
  private static String quote(String text) {
    StringBuilder quoted = new StringBuilder();
    for (String line : LINE_BREAK.split(text, -1)) {
      if (quoted.length() > 0) {
        quoted.append('\n');
      }
      quoted.append(QUOTE).append(line);
    }
    return quoted.toString();
  }

  // --- the schema ----------------------------------------------------------

  /*
   * LinkedHashMap and never Map.of, for ToolRegistry.Tool's measured reason:
   * Map.of has no iteration order to preserve, so a schema built that way is
   * emitted shuffled and differently shuffled between runs. The model reads
   * these fields in order.
   */
  private static Map<String, Object> citationsSchema() {
    Map<String, Object> limit = new LinkedHashMap<>();
    limit.put("type", "integer");
    limit.put(
        "description",
        "How many citations to return at most. Omit for the server's default; a larger"
            + " number than it allows is capped.");

    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "conversation", string("Only what this conversation cited. Not with 'document'."));
    properties.put(
        "document",
        string("Only what has cited this document, by its id. Not with" + " 'conversation'."));
    properties.put("limit", limit);
    return object(properties, List.of());
  }

  private static Map<String, Object> searchSchema() {
    Map<String, Object> limit = new LinkedHashMap<>();
    limit.put("type", "integer");
    // The number is not spelled here on purpose: the cap lives on the
    // server and a copy of it in this description would be the copy that
    // stopped being true. The answer says which figure was used.
    limit.put(
        "description",
        "How many passages to return at most. Omit for the server's default; a larger"
            + " number than it allows is capped, and the answer says so.");

    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("question", string("What you want to know, in plain language."));
    properties.put("limit", limit);
    return object(properties, List.of("question"));
  }

  // --- arguments -----------------------------------------------------------

  /**
   * An optional string, or null. Blank is null: a harness that sent an empty filter meant no
   * filter, and refusing would spend a turn saying so.
   */
  private static String optional(Map<String, Object> args, String name) {
    Object value = args == null ? null : args.get(name);
    return value instanceof String text && !text.isBlank() ? text.strip() : null;
  }

  private static String required(Map<String, Object> args, String name) {
    Object value = args == null ? null : args.get(name);
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException(
          "document_search needs a '"
              + name
              + "': one sentence saying what you want"
              + " to know. Nothing was searched, which is not the same as nothing"
              + " being found.");
    }
    return text.strip();
  }

  /**
   * An optional whole number, or null.
   *
   * <p>A number sent as a string is accepted, matching {@code ConversationTools} and the server's
   * own {@code ToolArguments.optionalInt}: a model does it often enough that refusing would cost a
   * turn to learn nothing. <b>The range is not checked here</b> — the server caps the top and
   * refuses a non-positive one with the sentence that explains why, and a second opinion on either
   * would be this module inventing a policy the corpus owns.
   */
  private static Integer optionalInt(Map<String, Object> args, String name) {
    Object value = args == null ? null : args.get(name);
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String text && !text.isBlank()) {
      try {
        return Integer.valueOf(text.trim());
      } catch (NumberFormatException notANumber) {
        throw new IllegalArgumentException(
            "document_search could not read '"
                + name
                + "': it must be a whole"
                + " number, not "
                + text
                + ". Leave it out for the server's"
                + " default.");
      }
    }
    throw new IllegalArgumentException(
        "document_search could not read '"
            + name
            + "': it must be a whole number."
            + " Leave it out for the server's default.");
  }

  /**
   * One call, with an unreachable server said as prose rather than thrown.
   *
   * <p>{@code ConversationTools} declines to share this and so does this file, for {@code Schemas}'
   * stated reason: what a reader needs to know differs per surface. Here it is that a search
   * changed nothing at all, so retrying is free — and, above everything, that this is not an empty
   * corpus. "Nothing is in the corpus" and "I could not ask" are indistinguishable once they render
   * the same way, and only the first is a conclusion an agent acts on.
   */
  private <T> T ask(Call<T> call) {
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
              + ". Nothing was searched and nothing was changed, so this is not an"
              + " empty corpus and trying again costs nothing. Check the server is"
              + " running.",
          unreachable);
    }
  }

  private static String describe(Throwable failure) {
    String message = failure.getMessage();
    return message == null || message.isBlank() ? failure.toString() : message;
  }

  @FunctionalInterface
  private interface Call<T> {
    T get() throws IOException;
  }
}
