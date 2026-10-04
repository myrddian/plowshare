package io.aeyer.plowshare.client.tools;

import static io.aeyer.plowshare.client.tools.MemoryTools.oneLine;
import static io.aeyer.plowshare.client.tools.MemoryTools.quote;
import static io.aeyer.plowshare.client.tools.Schemas.integer;
import static io.aeyer.plowshare.client.tools.Schemas.object;
import static io.aeyer.plowshare.client.tools.Schemas.string;

import io.aeyer.plowshare.client.ServerClient;
import io.aeyer.plowshare.client.mcp.ToolRegistry;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>The corpus as something you can look at</b> — a passage with the paper around it, the list of
 * what is here, which paper is about a subject, and one document's outline.
 *
 * <h2>A family of its own, and {@code AskTools} says why</h2>
 *
 * <p>{@code DocumentTools} was owned by concurrent work when this landed, which is the same
 * accident that put {@code document_ask} in its own class. Folding all three families into one is a
 * three-line move and should be made the moment they are in one tree; nothing on the surface
 * changes when it is, because a tool's family is a Java class and the registry is flat.
 *
 * <h2>What these three add that the corpus did not have</h2>
 *
 * <p>{@code document_search} answers <em>which paragraph should I cite</em> and {@code
 * document_ask} answers <em>what does this paper argue</em>, and between them a caller still could
 * not do three ordinary things: see a passage's place in its document, find out what documents
 * exist, or look at one document's shape before spending minutes asking it. The first is Anchor's
 * {@code /retrieve} and its whole reason for building a hierarchy; the second and third are how a
 * person gets from "I uploaded a paper once" to an id.
 *
 * <p>{@code document_rank} is the fourth and arrived with V27's column. It is the cheap half of the
 * same problem the second and third solve: {@code document_search} finds documents by way of their
 * best passage, which is a paper winning on one striking sentence, and this compares against what
 * each paper is about as a whole. <b>It ranks by subject and not by agreement</b>, which its
 * description says twice because the number invites the other reading.
 *
 * <p><b>The second one is the parity rule biting.</b> Until it shipped, a foreign harness could
 * start the librarian over the corpus and could not ask the corpus what was in it — and neither
 * could Plowshare's own CLI. The one surface that could was a browser tab.
 *
 * <h2>Every line at column zero is one this renderer wrote</h2>
 *
 * <p>{@code DocumentTools}' rule, kept, because the same class of text arrives here: chunk text and
 * paragraph text are somebody's uploaded document and a paper's own prose can be shaped like a
 * heading, a JSON fragment or an instruction. Passages are quoted line by line and every
 * single-line field goes through {@code oneLine}.
 *
 * <p><b>A unit's name goes through {@link Structural} and never through a ternary.</b> The server
 * has already discarded the sentinel a parser-invented chapter carries, so what arrives is a null
 * title beside a {@code synthetic} flag; printing that null is what Anchor's shell does, and it
 * renders a document's outline as a column of the word "null".
 */
public final class RetrieveTools {

  /**
   * How many passages a retrieve asks for when the model names no number. Below the server's own
   * default deliberately: a model pays for what comes back out of its conversation's context, and
   * five passages with four tiers of summary each is already a large answer.
   */
  private static final int PASSAGES = 5;

  private final ServerClient server;

  public RetrieveTools(ServerClient server) {
    this.server = server;
  }

  public void registerOn(ToolRegistry registry) {
    registry.register("document_retrieve", RETRIEVE_DESCRIPTION, retrieveSchema(), this::retrieve);
    registry.register("document_list", LIST_DESCRIPTION, listSchema(), this::list);
    registry.register("document_rank", RANK_DESCRIPTION, rankSchema(), this::rank);
    registry.register("document_outline", OUTLINE_DESCRIPTION, outlineSchema(), this::outline);
  }

  // --- retrieve ------------------------------------------------------------------

  public Object retrieve(Map<String, Object> args) {
    String question = required(args, "question");
    String document = optional(args, "document");
    Integer limit = number(args, "limit");

    ServerClient.Retrieved found =
        reach(() -> server.retrieve(question, document, limit == null ? PASSAGES : limit));

    String scope =
        found.document() == null
            ? "the whole corpus"
            : "document " + oneLine(found.document().toString());
    if (found.hits().isEmpty()) {
      return "Nothing in "
          + scope
          + " is close to: "
          + oneLine(question)
          + "\n\nThat is an answer about this corpus and not about the question."
          + " document_list says what the corpus holds; a passage stored while the"
          + " embedding endpoint was down holds its text and no vector, and"
          + " document_search reports how many of those there are.";
    }
    StringBuilder out = new StringBuilder();
    out.append(found.hits().size())
        .append(found.hits().size() == 1 ? " passage from " : " passages from ")
        .append(scope)
        .append(" for: ")
        .append(oneLine(question));
    for (ServerClient.RetrievedHit hit : found.hits()) {
      out.append("\n\n").append(render(hit));
    }
    return out.toString();
  }

  /**
   * One passage and the four tiers above it.
   *
   * <p><b>The stack is printed from the document down</b>, ending at the passage, because that is
   * the order it has to be read in: a chunk read before the paper it sits in is the failure the
   * hierarchy was built to fix, and a renderer that put the text first would reproduce it in a tool
   * that exists to prevent it.
   */
  private static String render(ServerClient.RetrievedHit hit) {
    ServerClient.ChunkDetail chunk = hit.chunk();
    StringBuilder out = new StringBuilder();
    out.append(oneLine(chunk.sourceName()))
        .append(" — \"")
        .append(oneLine(chunk.title()))
        .append("\" — paragraph ")
        .append(chunk.paragraphOrdinal())
        .append(" — similarity ")
        // Locale.ROOT for DocumentTools' measured reason: String.format
        // separates against the default locale, so a machine set to a
        // comma decimal separator would hand the model 0,82 -- a
        // model-visible string that changes with a setting nobody
        // reading the answer can see.
        .append(String.format(Locale.ROOT, "%.2f", hit.score()));
    out.append("\ncite paragraph ").append(chunk.paragraphId());
    if (chunk.documentSummary() != null) {
      out.append("\nthe document argues: ").append(oneLine(chunk.documentSummary()));
    }
    if (chunk.chapter() != null) {
      out.append("\nin ").append(Structural.name(chunk.chapter()));
      if (chunk.chapter().summary() != null) {
        out.append(": ").append(oneLine(chunk.chapter().summary()));
      }
    }
    // Null section and null chapter are one state and Structural says so in
    // one phrase, so the two lines above and below would otherwise both
    // report the same absence in different words.
    out.append("\nin ").append(Structural.name(chunk.section()));
    if (chunk.section() != null && chunk.section().summary() != null) {
      out.append(": ").append(oneLine(chunk.section().summary()));
    }
    if (chunk.paragraphSummary() != null) {
      out.append("\nthe paragraph claims: ").append(oneLine(chunk.paragraphSummary()));
    }
    return out.append("\n").append(quote(chunk.text())).toString();
  }

  // --- list ----------------------------------------------------------------------

  public Object list(Map<String, Object> args) {
    String naming = optional(args, "naming");
    Integer limit = number(args, "limit");
    Integer offset = number(args, "offset");

    ServerClient.DocumentPage page = reach(() -> server.listDocuments(naming, limit, offset));

    if (page.documents().isEmpty()) {
      return page.naming() == null
          ? "The corpus holds no documents."
          : "No document in the corpus is named anything like: "
              + oneLine(page.naming())
              + "\n\nThe corpus holds "
              + page.total()
              + " that match that and "
              + "some that do not; call this again"
              + " without a naming to see everything.";
    }
    StringBuilder out = new StringBuilder();
    out.append(page.documents().size()).append(" of ").append(page.total());
    if (page.naming() != null) {
      out.append(" named like ").append(oneLine(page.naming()));
    }
    out.append(
        page.total() > page.documents().size()
            ? " — ask again with a larger limit or an offset for the rest."
            : " — that is all of them.");
    for (ServerClient.DocumentRow row : page.documents()) {
      out.append("\n\n").append(render(row));
    }
    return out.toString();
  }

  private static String render(ServerClient.DocumentRow row) {
    StringBuilder out = new StringBuilder();
    out.append(oneLine(row.sourceName()))
        .append(" — \"")
        .append(oneLine(row.title()))
        .append("\"")
        .append("\nid ")
        .append(row.documentId())
        .append("\n")
        .append(row.paragraphs())
        .append(" paragraphs in ")
        .append(row.sections())
        .append(" sections, ")
        .append(row.chapters())
        .append(" chapters, ")
        .append(row.chunks())
        .append(" searchable passages");
    if (row.summary() == null) {
      out.append(
          "\nnothing has summarised it yet, so an ask over it has no macro"
              + " evidence to reason from.");
    } else {
      out.append("\nit argues: ").append(oneLine(row.summary()));
    }
    return out.toString();
  }

  // --- rank --------------------------------------------------------------------

  public Object rank(Map<String, Object> args) {
    String question = required(args, "question");
    Integer limit = number(args, "limit");

    ServerClient.Ranking found = reach(() -> server.rankDocuments(question, limit));

    if (found.documents().isEmpty()) {
      return "No document in the corpus is about: "
          + oneLine(question)
          + "\n\n"
          + howMuchWasRanked(found)
          + "\n\nThat is an answer about this corpus and not about the question."
          + " document_list says what is here.";
    }
    StringBuilder out = new StringBuilder();
    out.append(found.documents().size())
        .append(found.documents().size() == 1 ? " document" : " documents")
        .append(" for: ")
        .append(oneLine(question))
        .append("\n")
        .append(howMuchWasRanked(found));
    for (ServerClient.RankedDocument row : found.documents()) {
      out.append("\n\n")
          .append(oneLine(row.sourceName()))
          .append(" — \"")
          .append(oneLine(row.title()))
          .append("\" — relevance ")
          .append(String.format(Locale.ROOT, "%.2f", row.score()))
          .append("\nid ")
          .append(row.documentId());
      if (row.summary() != null) {
        out.append("\nit argues: ").append(oneLine(row.summary()));
      }
    }
    return out.toString();
  }

  /**
   * How much of the corpus the ranking could see. Said on every answer and not only on an empty
   * one: a top result out of two rankable documents in a corpus of forty is a different fact from a
   * top result out of forty.
   */
  private static String howMuchWasRanked(ServerClient.Ranking found) {
    return found.unranked() == 0
        ? found.rankable() + " documents in the corpus could be ranked."
        : found.rankable()
            + " documents could be ranked and "
            + found.unranked()
            + " have a summary that has not been embedded yet, so they were not"
            + " compared whatever the question was.";
  }

  // --- outline -------------------------------------------------------------------

  public Object outline(Map<String, Object> args) {
    String document = required(args, "document");

    ServerClient.DocumentOutline outline = reach(() -> server.describeDocument(document));

    StringBuilder out = new StringBuilder();
    out.append(oneLine(outline.sourceName()))
        .append(" — \"")
        .append(oneLine(outline.title()))
        .append("\"");
    if (outline.summary() != null) {
      out.append("\nit argues: ").append(oneLine(outline.summary()));
    }
    // The document's own word for its parts, which is the reader V26's
    // top_level_label column was added for: a paper that says "Section"
    // throughout should never be told it has chapters.
    String parts =
        outline.vocabulary() == null
            ? "chapters"
            : outline.vocabulary().toLowerCase(Locale.ROOT) + "s";
    out.append("\n\n").append(outline.chapters().size()).append(" ").append(parts).append(":");
    for (ServerClient.ChapterOutline chapter : outline.chapters()) {
      out.append("\n\n").append(Structural.name(chapter.title(), chapter.synthetic()));
      if (chapter.summary() != null) {
        out.append("\n  ").append(oneLine(chapter.summary()));
      }
      for (ServerClient.Unit section : chapter.sections()) {
        out.append("\n  - ").append(Structural.name(section));
        if (section.summary() != null) {
          out.append("\n      ").append(oneLine(section.summary()));
        }
      }
    }
    return out.toString();
  }

  // --- the descriptions ----------------------------------------------------------

  /*
   * A calling agent decides whether to invoke a tool from its description
   * alone — it never sees this code, the HTTP surface or the corpus. The three
   * rules this surface keeps: say what the tool costs, never describe a
   * capability it does not have, and say that what comes back is text somebody
   * uploaded rather than anything this system asserts.
   */
  static final String RETRIEVE_DESCRIPTION =
      """
            Read passages of the corpus WITH the structure around them: each one \
            comes back under its document's argument, its chapter's and its \
            section's, and with what the paragraph itself claims.

            Use this instead of document_search when you need to know whether a \
            passage says what it appears to say. A chunk read on its own can be \
            a position the paper goes on to refute, a view it attributes to \
            somebody else, or a claim it qualifies three sections later, and a \
            ranked list of passages cannot tell you which. The summaries above \
            it are what can.

            Pass a document id to read one paper, or leave it out to read the \
            whole corpus. One embedding call and one index scan; it answers \
            straight away and costs no deliberation.

            Every passage names the paragraph to cite. The passage and every \
            summary above it are about text somebody uploaded — they are not \
            this system's claims and nothing has checked them.""";

  static final String LIST_DESCRIPTION =
      """
            List the documents in the corpus, newest first, with what each one \
            argues and how much of it there is.

            This is how you find a document's id, which document_ask and \
            document_outline both need. document_search finds documents a \
            question happens to reach; this one answers what is here at all.

            Pass a naming to narrow it — a piece of the filename it was filed \
            under, or of its title, in any case. Leave it out for everything. \
            The count of matches comes back beside the page, so you can tell a \
            corpus that holds nothing from a naming that matched nothing.

            Titles and summaries are drawn from documents somebody uploaded. \
            They are not this system's claims.""";

  static final String RANK_DESCRIPTION =
      """
            Rank whole documents by how close each one's own summary is to a \
            question. One comparison per document; it does not look inside them.

            This is the cheap way to find WHICH PAPER to work on. \
            document_search reads passages from across the corpus and every hit \
            names a document, but a document can win on one striking sentence; \
            this compares against what each document is about as a whole. Use it \
            to narrow, then document_outline to check the shape of a candidate, \
            then document_ask to actually ask it.

            IT RANKS BY SUBJECT AND NOT BY AGREEMENT. A paper that spends forty \
            pages demolishing a claim ranks high for that claim, because it is \
            about it. Nothing in this answer says what a document concludes.

            The answer says how many documents could be ranked and how many were \
            skipped because nothing has embedded their summary yet, so an empty \
            result is never ambiguous between "the corpus holds nothing like \
            this" and "the corpus is not ready".

            Summaries are a model's paraphrase of documents somebody uploaded. \
            They are not this system's claims.""";

  static final String OUTLINE_DESCRIPTION =
      """
            Show one document's structure: its chapters, the sections under \
            them, and what each of those covers. No passage text.

            Read this before document_ask. An ask is three model calls in \
            series over one paper and takes minutes; the outline is one read and \
            tells you whether this is the paper that would answer your question, \
            and which part of it to ask about.

            Some chapters and sections have no title, because the document \
            declared no heading there and the corpus grouped its text anyway. \
            Those come back as "(unnamed segment)". That is a fact about the \
            document and not a missing value.

            Needs the document's id, which document_list and every \
            document_search hit carry. Titles and summaries describe uploaded \
            text and are not this system's claims.""";

  // --- the schemas ---------------------------------------------------------------

  /*
   * LinkedHashMap and never Map.of, matching every other family here: Map.of
   * has no iteration order to preserve, so a schema built that way is emitted
   * with its keys shuffled, and shuffled differently on every launch. The
   * model reads these fields in order.
   */
  private static Map<String, Object> retrieveSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "question",
        string(
            "What you want to know, in plain language. Matched by meaning against the"
                + " corpus's passages."));
    properties.put(
        "document",
        string(
            "Optional. The id of one document to read, as a uuid. Leave it out to read the"
                + " whole corpus. Every document_list row and every document_search hit"
                + " carries an id."));
    properties.put(
        "limit",
        integer(
            "Optional. How many passages at most. Defaults to "
                + PASSAGES
                + "; each one"
                + " carries four levels of summary, so a large number is a large"
                + " answer."));
    return object(properties, List.of("question"));
  }

  private static Map<String, Object> listSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "naming",
        string(
            "Optional. Part of the name a document was filed under, or of its title. Case"
                + " does not matter. Leave it out for the whole corpus."));
    properties.put("limit", integer("Optional. How many documents at most."));
    properties.put(
        "offset", integer("Optional. How many to skip, for reading past the first page."));
    return object(properties, List.of());
  }

  private static Map<String, Object> rankSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "question",
        string(
            "What you want to find a paper about, in plain language. Matched against each"
                + " document's summary rather than its passages."));
    properties.put(
        "limit", integer("Optional. How many documents at most. Omit for the server's default."));
    return object(properties, List.of("question"));
  }

  private static Map<String, Object> outlineSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "document", string("The id of the document whose structure you want, as a uuid."));
    return object(properties, List.of("document"));
  }

  // --- the plumbing --------------------------------------------------------------

  private static String required(Map<String, Object> args, String name) {
    String text = optional(args, name);
    if (text == null) {
      throw new IllegalArgumentException("'" + name + "' is required");
    }
    return text;
  }

  /**
   * Absent and blank are one thing here, which is not true everywhere on this surface: an omitted
   * naming and an empty one both mean the whole corpus, and refusing the second would refuse a
   * harness that passed through an empty search box.
   */
  private static String optional(Map<String, Object> args, String name) {
    Object value = args == null ? null : args.get(name);
    String text = value == null ? "" : value.toString().strip();
    return text.isEmpty() ? null : text;
  }

  private static Integer number(Map<String, Object> args, String name) {
    Object value = args == null ? null : args.get(name);
    if (value == null) {
      return null;
    }
    if (value instanceof Number found) {
      return found.intValue();
    }
    try {
      return Integer.valueOf(value.toString().strip());
    } catch (NumberFormatException notANumber) {
      throw new IllegalArgumentException(
          "'" + name + "' is '" + value + "', which is not a number", notANumber);
    }
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
              + ". This says nothing about the corpus: the server was never"
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
