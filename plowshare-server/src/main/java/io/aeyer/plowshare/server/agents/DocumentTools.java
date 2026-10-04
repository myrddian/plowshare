package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.ToolArguments.BadArguments;
import io.aeyer.plowshare.server.documents.DocumentStore;
import io.aeyer.plowshare.server.documents.RetrievalService;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The corpus as an agent reaches it: ask the documents a question, and ask what the documents are.
 *
 * <h2>Two tools, and the second is why the first stopped being enough</h2>
 *
 * <p>This file held one tool until 2026-09-06, and the sentence at the top of it said so. <b>What
 * that cost was measured rather than argued.</b> {@code implementation rationale} §11 records a
 * librarian spending its whole twelve-call allowance on {@link Search}, writing no prose at all and
 * re-issuing two searches verbatim, with every tool result coming back populated — and three of
 * seven runs in the three-way sweep ending {@code CALL_BUDGET} without an answer.
 *
 * <p>An agent that cannot ask <em>what is here</em> has exactly one instrument for finding out:
 * search, and search again differently. From inside a search, a weak hit and a document that was
 * never ingested are the same observation, so <b>rephrasing is the rational response to that
 * ambiguity rather than a malfunction</b>. {@link AgentList} removes the ambiguity; whether it
 * removes the loop is a question the sweep answers, and a null result there is a finding about that
 * reframing rather than a failure of this tool.
 *
 * <p><b>Nothing here is a new read.</b> {@code GET /v1/documents} has answered the existence
 * question since it shipped — its {@code q} is precisely "is the Woodall paper in here", and it
 * refuses a {@code limit} below one with the sentence this whole addition is about — and the
 * distinction lived in the HTTP layer, where nothing a model could call could say it. That is
 * {@code TODO.md} §4.4's hazard with a name: a capability reachable only by {@code curl} is
 * invisible to both assembly checks, because each one catches a tool that reaches nothing and
 * neither can catch a thing no tool reaches.
 *
 * <h2>What the model is told about hybrid search, which is one clause</h2>
 *
 * <p>The corpus is read two ways and fused — meaning and words, {@code
 * RetrievalService.Mode#HYBRID} — and the only thing that changes here is a sentence saying a
 * question is matched by both, because the old one said "by meaning" and that is now half true.
 * <b>Nothing tells the model which half found a hit, and there is no mode parameter.</b> Both were
 * considered and both are refusals with the same reason: a model cannot act on either. Choosing
 * between an HNSW index and a GIN index is a question about this server's plans that nothing in an
 * agent's context could answer, and a provenance label on a hit is a fact about retrieval internals
 * offered to something whose next move is to read the passage either way. {@code
 * SearchDocumentsRequest} carries the mode, because its caller is an operator.
 *
 * <p><b>And the fourth kind of empty is deliberately not said.</b> An empty answer already means
 * three things and says which; hybrid adds a fourth — matched on words and not on meaning, or the
 * reverse — and it is not worth a sentence. The word half is conjunctive: every content word of the
 * question must be in the chunk, so it is correctly silent for most well-formed questions. A
 * sentence reporting that silence would fire on nearly every empty answer, would be true every
 * time, and would teach a model nothing it could do anything about. The three that remain each have
 * a different next action, which is the test the fourth fails.
 *
 * <h2>One tool, where memory has two</h2>
 *
 * <p>{@code memory_recall} returns a shortlist and {@code memory_read} returns bodies, because a
 * recall that inlined every body would spend a 9b model's context on memories it was about to
 * discard. <b>The corpus does not split that way.</b> A chunk is already the smallest useful unit —
 * it is what got embedded — and a "shortlist" of chunks would be a list of ids and similarity
 * scores, which is nothing a model could choose between. So a hit carries its text, and there is no
 * second call.
 *
 * <p>What that costs is the reason {@code RetrievalService.MAX_HITS} is ten rather than twenty:
 * every hit is up to {@code plowshare.llm.embedding-max-input-tokens} of somebody's document.
 *
 * <h2>The ancestor stack, projected once per document</h2>
 *
 * <p>An answer introduces each document it drew from with what that document argues, before any
 * passage of it — {@code documents.summary}, V22's top level, which until 2026-09-04 was stored by
 * a ~220-call cascade and selected by no query on the retrieval path, so it was reachable from no
 * agent at all. {@link Search#arguments} carries the three decisions inside it: why it is here
 * rather than behind a second tool, why it is once per document where Anchor's {@code
 * findChunksForRetrieve} is once per chunk, and why the paragraph level is declined with a
 * measurement. It renders nothing at all for a corpus nothing has summarised, which is an ordinary
 * state and not a migration window.
 *
 * <h2>Every line at column zero is one this renderer wrote</h2>
 *
 * <p>V18 says it on the table itself, and it is the sharpest version of this rule anywhere in the
 * tree: <i>"Chunk text is whatever was uploaded: other people's papers, notes and source... content
 * the server did not write and cannot vouch for, and unlike `entries` it is content a search will
 * surface out of context."</i> So a chunk's text is quoted line by line with {@code "> "}, the
 * convention {@code MemoryTools} already uses for a memory's body, and a document whose own prose
 * is shaped like this tool's heading cannot make a model read it as a second hit.
 *
 * <h2>The tier is not an argument, and here there is not even a tier</h2>
 *
 * <p>Every other tool takes {@code home} as a parameter of {@link AgentTool#run} so that an agent
 * cannot name its own. This one takes it and <b>does not read it</b>: V18 stores no project column,
 * on the v1 design's line that "a memory has exactly one home; a document has none. Memory is
 * scoped because contradiction needs an owner. Documents are not because relevance does not." A
 * corpus reachable from every project is the decision.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p><b>No ingest tool.</b> A document arrives as bytes somebody read off a disk, under the
 * client's workspace leash, and {@code DocumentController}'s javadoc records why the server never
 * reads a path of its own. An agent tool that took a server-side path would be exactly the route
 * spec decision 4 declined.
 *
 * <p><b>No citation-resolving tool, and the reason has changed while the answer has not.</b> This
 * paragraph used to say that a resolver "would be a tool answering a question nobody can yet ask",
 * on the ground that nothing stored a citation. <b>Something does now</b> — V25's {@code
 * citations}, written by {@code documents.Citations} through the {@link Citing} seam — so that
 * ground is gone and the tool is still declined, on a narrower one: a hit already carries the
 * paragraph's text, so a citation is redeemed at the moment it is made, and a resolver would be a
 * second route to text this agent is already looking at. What it would cost is a fourth description
 * on {@code librarian}'s surface, which {@code implementation rationale} says no test in this suite
 * can evaluate.
 *
 * <p><b>No rule drawn between two hits.</b> A forty-hyphen rule sat here as an unused constant
 * until 2026-09-04, calling itself {@code MemoryTools.SEPARATOR}'s twin. It is not that tool's
 * twin: that rule is drawn by {@code memory_read}, which returns bodies and nothing else, and the
 * shape this answer has is {@code memory_recall}'s — a ranked list under a count sentence with
 * footnotes after it — which separates its entries with a blank line exactly as {@link
 * Search#answer} does. Nor would drawing one carry an invariant: {@code quote} prefixes every line
 * of a chunk, empty lines included, so a chunk emits no blank line and none at column zero, and the
 * blank line is already as unforgeable as a rule would be. What it would cost is about forty bytes
 * per boundary, {@link RetrievalService#MAX_HITS} of them, of a local model's context — spent on
 * something no reader of this answer splits on, because a hit arrives under a count sentence and
 * sometimes an argument block, so there is no one string to split on in the first place. Pinned by
 * {@code two_hits_are_separated_by_a_blank_line_and_no_drawn_rule} and {@code
 * an_uploaded_chunk_cannot_forge_the_boundary_between_two_hits}.
 *
 * <p>The listing that does exist is for a person and a foreign harness rather than for an agent of
 * this server: {@code GET /v1/documents/citations}, the {@code document_citations} MCP tool, and
 * {@code plowshare document citations}. <b>Nothing on this file's surface moved for any of it</b> —
 * no description was reworded, and an agent's prompt is byte-for-byte what it was.
 *
 * <p><b>And the listing reads and does nothing else.</b> No ingest, for the reason two paragraphs
 * up; <b>no delete</b>, because an agent that could remove a document from a corpus shared by every
 * project is a different design with a different argument, and the read that says what exists is
 * not the place to grow one. <b>No ranking either</b>: {@code POST /v1/documents/rank} compares a
 * question against each document's summary and is a real capability that is not this one — a
 * listing is ordered by something stable and boring, and relevance is {@link Search}'s job.
 *
 * <p>Both tools are immutable and hold one collaborator each — a {@link RetrievalService} and a
 * {@code DocumentStore} — so one instance of each serves every job.
 */
public final class DocumentTools {

  /**
   * The name the model calls, the job log records, and {@code AgentRegistry.load} is told about.
   * Spelled once.
   */
  public static final String SEARCH_NAME = "document_search";

  /**
   * {@link #SEARCH_NAME}'s twin, and the same spelling the client's MCP surface and {@code
   * plowshare document list} already use for the read underneath it. One capability with one name
   * on every front end.
   */
  public static final String LIST_NAME = "document_list";

  /**
   * How many documents a listing answers with when the model names none.
   *
   * <p><b>{@code DocumentController.DEFAULT_LISTED}'s number and deliberately not that
   * constant.</b> The two callers are not the same reader: an operator paging {@code GET
   * /v1/documents} pays for a page in bytes it scrolls past, and a model pays for one out of the
   * allowance it has to answer with. The design that added this tool leaves the agent's figure open
   * — a cap lower than the endpoint's, with the total always stated, may serve a model better — and
   * says it should be chosen against a corpus larger than the four documents here. A separate
   * constant is what makes that a one-line change rather than a change to the endpoint.
   */
  public static final int LISTED = 50;

  /**
   * What every line of a chunk is prefixed with. The quoting convention a model already reads as
   * somebody else's words.
   */
  private static final String QUOTE = "> ";

  private DocumentTools() {}

  /**
   * {@code document_search} — the passages of the corpus nearest a question.
   *
   * <p><b>An empty answer here is a claim about the corpus, so it has to be a true one, and there
   * are three of them.</b> Nothing close, nothing ingested and nothing searchable are one empty
   * list and three different next actions — rephrase the question, upload a document, ingest the
   * documents again — and an agent told the first when the third is true will rephrase forever,
   * which is exactly right of it.
   */
  public static final class Search implements AgentTool {

    private final RetrievalService retrieval;
    private final ToolSchema schema;

    public Search(RetrievalService retrieval) {
      this.retrieval = Objects.requireNonNull(retrieval, "retrieval");
      this.schema = new ToolSchema(SEARCH_NAME, SEARCH_DESCRIPTION, searchSchema());
    }

    AgentTool forRun(io.aeyer.plowshare.server.information.InformationAccess access, String owner) {
      return forRun(access, owner, null, null);
    }

    AgentTool forRun(
        io.aeyer.plowshare.server.information.InformationAccess access,
        String owner,
        io.aeyer.plowshare.server.information.InformationJobs policies,
        String log) {
      return new AgentTool() {
        @Override
        public ToolSchema schema() {
          return Search.this.schema;
        }

        @Override
        public String run(String arguments, Home home) {
          var context = access.forRun(owner, home);
          var reader = retrieval.scoped(access, context);
          if (policies != null) reader = reader.audited(policies.reads(log, context));
          return new Search(reader).run(arguments, home);
        }
      };
    }

    @Override
    public ToolSchema schema() {
      return schema;
    }

    @Override
    public String run(String argumentsJson, Home home) {
      return run(argumentsJson, home, UsageAttribution.LEGACY);
    }

    @Override
    public String run(String argumentsJson, Home home, UsageAttribution owner) {
      // Outside the try: a null here is the runtime's bug and not the
      // model's, and the never-throw rule is about a caller's mistakes.
      Objects.requireNonNull(argumentsJson, "argumentsJson");
      // Required although it is never read, and that is not ceremony: the
      // day the corpus grows a scope, a tool that had quietly accepted a
      // null home would start answering from one.
      Objects.requireNonNull(home, "home");
      try {
        return answer(argumentsJson, owner);
      } catch (BadArguments unusable) {
        // Every validation failure below lands here. Nothing else is
        // caught: an endpoint that could not embed the question is not a
        // mistake the model made and not one it can correct by calling
        // again, and rendering it as "nothing was found" would be a
        // confident empty answer over a question that was never asked.
        return unusable.getMessage();
      }
    }

    private String answer(String argumentsJson, UsageAttribution owner) {
      JsonNode args =
          ToolArguments.parse(
              argumentsJson, SEARCH_NAME, "{\"question\": \"how is the retry budget refilled\"}");
      String question =
          ToolArguments.requireText(
              args, "question", SEARCH_NAME, "one sentence saying what you want to know");

      RetrievalService.Found found =
          owner.status() == UsageAttribution.Status.LEGACY_UNATTRIBUTED
              ? retrieval.search(question, limit(args))
              : retrieval.search(question, limit(args), RetrievalService.Mode.HYBRID, owner);

      StringBuilder out = new StringBuilder();
      if (found.hits().isEmpty()) {
        out.append(nothing(found));
      } else {
        out.append(found.hits().size())
            .append(found.hits().size() == 1 ? " passage" : " passages")
            .append(" of the corpus, best first, out of ")
            .append(found.searchable())
            .append(" that can be searched. Each one names the paragraph to cite it")
            .append(" by. It also names the document that paragraph is in: that id")
            .append(" is not a citation — it is the handle for asking that one")
            .append(" document a question.\n");
        out.append(arguments(found.hits()));
        for (DocumentStore.Hit hit : found.hits()) {
          out.append('\n').append(render(hit)).append('\n');
        }
      }
      if (found.wasCapped()) {
        // Said rather than done quietly: a model handed ten when it
        // asked for a hundred cannot tell a cap from a corpus that
        // small, and the second reading is a wrong belief about what has
        // been ingested. MemoryTools.Recall's sentence.
        out.append("\n\nYou asked for ")
            .append(found.asked())
            .append("; ")
            .append(RetrievalService.MAX_HITS)
            .append(" is the most this tool returns at once.");
      }
      // Outside the empty/non-empty branch, which is the guard: attached
      // only to an empty answer, this footnote would be missing from the
      // case where a partial answer reads as a complete one.
      out.append(skipped(found));
      return out.toString();
    }

    /**
     * What each document below argues, once per document, or nothing at all.
     *
     * <h2>The ancestor-summary stack, and where it stops being Anchor's</h2>
     *
     * <p>Anchor's {@code findChunksForRetrieve} projects a chunk's ancestor summaries onto the
     * chunk, and it can afford to: its caller is {@code AskService}, which was handed one document
     * id, so the repetition is within one document. <b>Asked of a corpus, the same projection is a
     * different thing.</b> Five hits from one paper would carry one three-to-six-sentence summary
     * five times over, in front of a model whose {@link RetrievalService#MAX_HITS} is ten
     * <em>because</em> of how much of somebody else's document one answer may already hold. So the
     * stack is projected once per document, in the order the ranking first mentions each — the same
     * information, and the deduplication belongs to the corpus-wide question rather than being a
     * saving.
     *
     * <p><b>The document level only, and the paragraph level is declined with a measurement rather
     * than deferred.</b> {@code plowshare.documents.chunk-target-tokens} is 400 and a prose
     * paragraph is smaller than that, so a chunk is usually the whole paragraph — a paragraph
     * summary would then be a one-sentence compression of text already in front of the model in its
     * own words. Where a chunk really is a fragment, {@code DocumentStore.Hit.paragraphText} is
     * already on the hit and unrendered, and rendering <em>that</em> is the honest fix: a trade
     * against {@code MAX_HITS}'s byte budget, which is its own decision and not this one. The
     * document is the only level whose text appears nowhere in an answer.
     *
     * <p><b>Empty when nothing is summarised, and that is not a nicety.</b> {@code
     * documents.summary} is null for every document ingested before V22, for every one whose ingest
     * allowance ran out below the top of the cascade, and again the moment {@code
     * DocumentStore.write} re-ingests one. A heading over no summaries would be this renderer
     * making a claim about the corpus that no row made — and the silence is also what says that
     * adding this moved not one byte of what a model reads on a corpus holding none.
     *
     * <p><b>Quoted, exactly like the passages.</b> A summary is not this server's prose: {@code
     * document_summariser} wrote it while reading the same uploaded text, so it carries whatever
     * that text carried. Letting it reach column zero would put derived-from-untrusted content
     * where nothing this renderer wrote can be told from it, and derivation is not laundering.
     */
    private static String arguments(List<DocumentStore.Hit> hits) {
      // Insertion-ordered, so the documents arrive in the order the
      // ranking below first mentions them.
      Map<UUID, DocumentStore.Hit> byDocument = new LinkedHashMap<>();
      for (DocumentStore.Hit hit : hits) {
        if (hit.documentSummary() != null && !hit.documentSummary().isBlank()) {
          byDocument.putIfAbsent(hit.documentId(), hit);
        }
      }
      if (byDocument.isEmpty()) {
        return "";
      }
      StringBuilder out = new StringBuilder("\n").append(ARGUMENTS).append('\n');
      for (DocumentStore.Hit hit : byDocument.values()) {
        out.append('\n')
            .append(ToolArguments.oneLine(hit.sourceName()))
            .append(" — \"")
            .append(ToolArguments.oneLine(hit.title()))
            .append("\"\n")
            .append(quote(hit.documentSummary()))
            .append('\n');
      }
      return out.toString();
    }

    /**
     * What an empty answer is allowed to say, and it is never one sentence.
     *
     * <p>{@link RetrievalService.Found#corpusIsEmpty} tells the second and third apart from the
     * first; {@link RetrievalService.Found#unsearchable} tells them apart from each other. All
     * three are reachable and each has a different thing for the reader to do next.
     */
    private static String nothing(RetrievalService.Found found) {
      if (!found.corpusIsEmpty()) {
        return "Nothing found — no passage in the corpus is close to that question."
            + " Search matches on meaning, so a differently framed question can"
            + " still find something.";
      }
      if (found.unsearchable() == 0) {
        return "The corpus is empty: no document has been ingested, so there is nothing"
            + " to search. This says nothing about the question.";
      }
      return "Nothing in the corpus can be searched. All "
          + found.unsearchable()
          + " passage(s) hold their text and no vector, which is what an ingest"
          + " leaves behind when the embedding endpoint could not be reached."
          + " Ingesting those documents again embeds them and re-derives nothing."
          + " This says nothing about the question.";
    }

    /**
     * How much of the corpus this answer did not cover, when that is not zero.
     *
     * <p>{@code MemoryTools.Recall}'s footnote, and the reason is the same: the filter that
     * excludes an unembedded chunk is invisible from where the model stands, so an answer that did
     * not say this would let a partial search read as a complete one.
     */
    private static String skipped(RetrievalService.Found found) {
      if (found.unsearchable() == 0 || found.corpusIsEmpty()) {
        // corpusIsEmpty already said the number, in a sentence that
        // says what to do about it. Repeating it here would be the same
        // fact twice with two different framings.
        return "";
      }
      return "\n\n"
          + found.unsearchable()
          + " passage(s) of the corpus could not be"
          + " searched at all: they hold their text and no vector, so no question"
          + " reaches them however it is phrased. Ingesting those documents again"
          + " embeds them.";
    }

    /**
     * The {@code limit} argument, defaulted and floored.
     *
     * <p>The floor is here rather than left to {@link RetrievalService#search}, which raises an
     * {@code IllegalArgumentException} for the same input. That is the right answer for a Java
     * caller and the wrong one for this surface: it would end the run over a zero the model could
     * have corrected on its next turn, and the turn that produced it was already paid for. The
     * ceiling is not repeated here — the service caps and says which figure it used.
     */
    private static int limit(JsonNode args) {
      int value =
          ToolArguments.optionalInt(args, "limit", RetrievalService.DEFAULT_HITS, Search::badLimit);
      if (value <= 0) {
        throw new BadArguments(
            SEARCH_NAME
                + " was given a 'limit' of "
                + value
                + ". It must be 1 or more; asking for none is not the same as finding"
                + " none, and this tool will not report one as the other.");
      }
      return value;
    }

    private static BadArguments badLimit(JsonNode limit) {
      return new BadArguments(
          SEARCH_NAME
              + " could not read 'limit': it must be a whole"
              + " number from 1 to "
              + RetrievalService.MAX_HITS
              + ", not "
              + limit
              + ". Leave it out for the default of "
              + RetrievalService.DEFAULT_HITS
              + ".");
    }
  }

  /**
   * {@code document_list} — what the corpus holds.
   *
   * <p><b>Exposure rather than capability.</b> Every read this makes is one {@code GET
   * /v1/documents} already makes: {@code DocumentStore#page} and {@code DocumentStore#count}, the
   * same filter over the filed name and the title. What was missing was a surface a model could
   * reach it from.
   *
   * <p><b>It says the two things a listing usually leaves implicit</b>, and both are the point
   * rather than polish. <i>How many documents the corpus holds</i> — on every answer, including the
   * empty ones, because a model reading the absence of that number reads it as zero. And <i>that
   * the corpus was asked and does not contain this</i>, when a name matched nothing: {@link
   * #nothing} is where the three empties are told apart, and "No document matches 'woodall'; the
   * corpus holds 4 documents" is the sentence the whole tool exists to make sayable, in one call.
   *
   * <p>Every argument is optional, all three are the endpoint's, and both refusals are its refusals
   * — a {@code limit} below one and a negative {@code offset} are answered rather than clamped,
   * because clamping either would hand back a plausible page for a request that said something
   * else.
   *
   * <p>Held by {@code librarian} and {@code interlocutor}, the two agents that already hold {@link
   * Search}; {@code close_reader} is unchanged and nothing in its job needs to know what else
   * exists. Not held by {@code ask_proposer} or {@code ask_critic}, whose {@code tools: []} keeps
   * the deliberation's evidence asymmetry in the loader rather than in a prompt — a corpus listing
   * is still evidence they were not given.
   */
  public static final class AgentList implements AgentTool {

    private final DocumentStore documents;
    private final ToolSchema schema;

    public AgentList(DocumentStore documents) {
      this.documents = Objects.requireNonNull(documents, "documents");
      this.schema = new ToolSchema(LIST_NAME, LIST_DESCRIPTION, listSchema());
    }

    AgentTool forRun(io.aeyer.plowshare.server.information.InformationAccess access, String owner) {
      return forRun(access, owner, null, null);
    }

    AgentTool forRun(
        io.aeyer.plowshare.server.information.InformationAccess access,
        String owner,
        io.aeyer.plowshare.server.information.InformationJobs policies,
        String log) {
      return new AgentTool() {
        @Override
        public ToolSchema schema() {
          return AgentList.this.schema;
        }

        @Override
        public String run(String arguments, Home home) {
          var context = access.forRun(owner, home);
          var reader = documents.scoped(access, context);
          if (policies != null) reader = reader.audited(policies.reads(log, context));
          return new AgentList(reader).run(arguments, home);
        }
      };
    }

    @Override
    public ToolSchema schema() {
      return schema;
    }

    @Override
    public String run(String argumentsJson, Home home) {
      Objects.requireNonNull(argumentsJson, "argumentsJson");
      // Required although it is never read, for Search#run's reason: the
      // day the corpus grows a scope, a tool that had quietly accepted a
      // null home would start answering from one.
      Objects.requireNonNull(home, "home");
      try {
        return answer(argumentsJson);
      } catch (BadArguments unusable) {
        return unusable.getMessage();
      }
    }

    private String answer(String argumentsJson) {
      JsonNode args = ToolArguments.parse(argumentsJson, LIST_NAME, "{\"name\": \"woodall\"}");
      String naming = ToolArguments.optionalText(args, "name", AgentList::badName);
      int asked = limit(args);
      int offset = offset(args);
      // The ceiling is applied here rather than left to the store, which
      // clamps in silence -- the right contract for a Java caller and the
      // wrong one for a reader who cannot see the clamp. What the model
      // asked for is kept so the sentence below can name both figures.
      int most = Math.min(asked, DocumentStore.MOST_LISTED);

      List<DocumentStore.Listed> page = documents.page(naming, most, offset);
      int matching = documents.count(naming);
      // One read where nothing was named, because the two questions are
      // then the same question. The second is only paid for when a name
      // has made them different.
      int held = naming == null ? matching : documents.count(null);

      StringBuilder out = new StringBuilder();
      if (page.isEmpty()) {
        out.append(nothing(naming, matching, held, offset));
      } else {
        out.append(page.size())
            .append(" of ")
            .append(matching)
            .append(matching == 1 ? " document" : " documents");
        if (naming == null) {
          out.append(
              " in the corpus, newest first. Each names the id that asks that"
                  + " one document a question; it is not a citation.\n");
        } else {
          out.append(" named like ")
              .append(named(naming))
              .append(", out of ")
              .append(held)
              .append(
                  " the corpus holds, newest first. Each names"
                      + " the id that asks that one document a question; it is"
                      + " not a citation.\n");
        }
        for (DocumentStore.Listed row : page) {
          out.append('\n').append(render(row)).append('\n');
        }
        if (offset + page.size() < matching) {
          out.append("\nThat is ")
              .append(offset + 1)
              .append(" to ")
              .append(offset + page.size())
              .append(" of ")
              .append(matching)
              .append("; ask again with an 'offset' of ")
              .append(offset + page.size())
              .append(" for the rest.");
        }
      }
      if (asked > most) {
        // Said rather than done quietly, which is Search's sentence: a
        // model handed two hundred when it asked for a thousand cannot
        // tell a cap from a corpus that size, and the second reading is
        // a wrong belief about what has been ingested -- the very
        // belief this tool exists to correct.
        out.append("\n\nYou asked for ")
            .append(asked)
            .append("; ")
            .append(DocumentStore.MOST_LISTED)
            .append(" is the most this tool lists at once.");
      }
      return out.toString();
    }

    /**
     * What an empty listing is allowed to say, and it is never one sentence.
     *
     * <p>{@code Search#nothing}'s rule in the register this tool was added for. <b>An empty list is
     * not self-describing</b>: a corpus holding nothing, a name nothing here goes by, and a page
     * past the end of the listing are one empty list and three different things to do next — upload
     * something, ask under a name the corpus would recognise, ask for an earlier page. The middle
     * one is the observation a model could not previously make at all, and is why this tool exists.
     */
    private static String nothing(String naming, int matching, int held, int offset) {
      if (held == 0) {
        return "The corpus is empty: no document has been ingested, so there is nothing"
            + " to list. That is a fact about this server and no name would change"
            + " it.";
      }
      if (naming != null && matching == 0) {
        return "No document matches "
            + named(naming)
            + "; the corpus holds "
            + held
            + (held == 1 ? " document" : " documents")
            + ". The name is matched"
            + " against both the filename a document was filed under and its"
            + " title, so a document here under either name would be on this list"
            + " — this corpus does not hold that one. Ask again with no name to"
            + " see what it does hold.";
      }
      // The corpus's own total is said here as well as the matching one,
      // although this branch has a count of its own to report. The
      // description promises the denominator on every answer, and a model
      // reading its absence reads it as zero -- which is the belief about
      // what has been ingested that this whole tool exists to correct.
      String reach =
          naming == null
              ? "the corpus holds " + matching + (matching == 1 ? " document" : " documents")
              : matching
                  + (matching == 1 ? " document is" : " documents are")
                  + " named like "
                  + named(naming)
                  + ", out of "
                  + held
                  + " the corpus holds";
      return "Nothing at offset "
          + offset
          + ", which is past the end of this listing"
          + " rather than an absence: "
          + reach
          + ", and the last of them is at"
          + " offset "
          + (matching - 1)
          + ". Ask again with a smaller 'offset'.";
    }

    /**
     * The model's own word, flattened onto one line and quoted, so that a name carrying a break or
     * shaped like this renderer's own prose cannot be read as anything but the string that was
     * asked for.
     */
    private static String named(String naming) {
      return "'" + ToolArguments.oneLine(naming) + "'";
    }

    private static BadArguments badName(JsonNode name) {
      return new BadArguments(
          LIST_NAME
              + " could not read 'name': it must be a piece of"
              + " text to match against a document's filed name or its title, not "
              + name
              + ". Leave it out to list the whole corpus.");
    }

    /**
     * The {@code limit} argument, defaulted and floored.
     *
     * <p><b>The floor carries {@code GET /v1/documents}' own sentence, and it is the sentence this
     * tool was built around.</b> That endpoint has refused a limit below one since it shipped —
     * asking for no documents is not the same as the corpus holding none — and the distinction
     * lived in the HTTP layer, where nothing a model could reach could say it. Answering a zero
     * with an empty list here would be this tool making exactly the ambiguous claim it exists to
     * end.
     *
     * <p>A result rather than a throw, on {@code Search#limit}'s reasoning: the turn that produced
     * the zero was already paid for.
     */
    private static int limit(JsonNode args) {
      int value = ToolArguments.optionalInt(args, "limit", LISTED, AgentList::badLimit);
      if (value <= 0) {
        throw new BadArguments(
            LIST_NAME
                + " was given a 'limit' of "
                + value
                + ". It must be 1 or more; asking for no documents is not the same as"
                + " the corpus holding none, and this tool will not report one as the"
                + " other.");
      }
      return value;
    }

    private static BadArguments badLimit(JsonNode limit) {
      return new BadArguments(
          LIST_NAME
              + " could not read 'limit': it must be a whole"
              + " number from 1 to "
              + DocumentStore.MOST_LISTED
              + ", not "
              + limit
              + ". Leave it out for the default of "
              + LISTED
              + ".");
    }

    /**
     * The {@code offset} argument, defaulted and floored at zero.
     *
     * <p>Refused rather than clamped, which is the endpoint's decision and its reason: a negative
     * offset is a caller's arithmetic having gone wrong somewhere above this call, and answering it
     * with the first page would hide the fault behind a plausible answer. {@code
     * DocumentStore#page} clamps it, so without this the model would be handed page one for a
     * request that meant something else.
     */
    private static int offset(JsonNode args) {
      int value = ToolArguments.optionalInt(args, "offset", 0, AgentList::badOffset);
      if (value < 0) {
        throw new BadArguments(
            LIST_NAME
                + " was given an 'offset' of "
                + value
                + ". It must be 0 or more; a negative offset is not a page of this"
                + " corpus, and answering it with the first one would make a paging"
                + " error look like a listing.");
      }
      return value;
    }

    private static BadArguments badOffset(JsonNode offset) {
      return new BadArguments(
          LIST_NAME
              + " could not read 'offset': it must be a whole"
              + " number of documents to skip, not "
              + offset
              + ". Leave it out to start at the newest.");
    }
  }

  /**
   * One row: what to call the document, what to ask it by, and how much of it there is.
   *
   * <p>{@link Search#render}'s shape — the heading first because it is what the choosing is done
   * on, then the id under the verb it goes into — and its two-names heading exactly, so a document
   * a search named and a document a listing named are recognisably the same document.
   *
   * <p><b>Both names, for {@code DocumentStore}'s own reason.</b> The filed name is the identity a
   * re-ingest matches on and the title is what the document calls itself; they differ in this
   * system, and a person naming a paper reaches for whichever they last saw. That is why the filter
   * matches both, and printing one of them would leave half the names somebody has been shown with
   * no row to appear on.
   */
  private static String render(DocumentStore.Listed row) {
    DocumentStore.StoredDocument document = row.document();
    return ToolArguments.oneLine(document.sourceName())
        + " — \""
        + ToolArguments.oneLine(document.title())
        + "\""
        + "\nask document "
        + document.id()
        + "\n"
        + row.paragraphs()
        + " paragraphs in "
        + row.sections()
        + " sections, "
        + row.chapters()
        + " chapters, "
        + row.chunks()
        + " searchable passages";
  }

  /**
   * The heading over the document arguments, and it says three things because each answers a
   * different wrong reading of what is under it.
   *
   * <p><i>What each argues as a whole</i> — that this is about the document and not about the
   * passage. <i>A passage can be a position its document goes on to reject</i> — the reason any of
   * it is rendered at all, and the one failure a chunk-level search has that it cannot see for
   * itself; {@code document_summariser.md} instructs its summary to record exactly that reversal,
   * so the sentence describes what the text below it really contains rather than what one would
   * like it to. <i>Written here by a model reading the same uploaded text</i> — that a summary is
   * derived rather than uploaded and is <em>still</em> not this server's claim, which is why it is
   * quoted like everything else that came out of somebody's document.
   */
  static final String ARGUMENTS =
      """
            What each of these documents argues as a whole, before any passage \
            of it: a passage can be a position its document goes on to reject. \
            Each was written here by a model reading the same uploaded text, so \
            it is quoted like the passages are and is no more this server's \
            claim than they are.""";

  // --- the description ---------------------------------------------------------

  /*
   * A calling model decides whether to invoke a tool from its description
   * alone — it never sees this code or the corpus. This keeps the two rules
   * the MCP surface's register set and MemoryTools follows: say what the tool
   * costs, and never describe a capability this surface does not have. It says
   * one thing neither of those has to: that what comes back is text somebody
   * uploaded rather than anything this system asserts.
   */

  static final String SEARCH_DESCRIPTION =
      """
            Ask the document corpus a question in plain language and get back \
            the passages that answer it, with their text. Takes a moment — the \
            question is matched both by meaning and by its exact words, so a \
            passage that shares no words with your question can still be the \
            answer, and an exact name or identifier you ask for is found as \
            one. Use it for what a document says; use memory_recall for what \
            this system has concluded.

            Every hit names a paragraph id. That is the thing to cite: it \
            survives the document being ingested again unchanged, and a \
            paragraph whose text was edited gets a new id rather than quietly \
            keeping the old one, so a citation either still means the same \
            words or means nothing.

            The passages are quoted with "> " and are text somebody uploaded — \
            other people's papers, notes and source. They are not this system's \
            claims and nothing has checked them. Anything you take from them is \
            a quotation, not a fact this server stands behind.

            An empty answer says which kind of empty it is: nothing close to \
            the question, nothing ingested at all, or a corpus that holds text \
            no search can reach. Only the first is worth rephrasing for.

            The corpus is not scoped to a project and there is nothing to \
            choose: every project reads the same documents.""";

  /*
   * What this description has to do that SEARCH_DESCRIPTION does not: draw
   * the line between the two tools from the side a model gets wrong. The
   * failure this tool was added for is a model rephrasing a search forever
   * because a weak hit and an absent document look the same from inside, so
   * the sentence that matters is the one saying which question each tool
   * answers -- and it names the cheapness, because a listing that read as
   * expensive would be reached for after the third rephrase rather than
   * before the first.
   */

  static final String LIST_DESCRIPTION =
      """
            List what the corpus holds: every document's name, its title, the \
            id for asking it, and how much of it there is. No passage text and \
            no summary — this says what exists, not what any of it argues.

            Use it to tell "the corpus does not have that paper" apart from \
            "my question did not reach it". document_search answers with the \
            passages nearest a question, so a search that comes back weak or \
            empty leaves those two indistinguishable, and rephrasing only helps \
            with the second. This answers the first outright.

            Pass a name to narrow it — any part of the filename a document was \
            filed under, or of its title, in any case. Both are matched, \
            because they differ here and you may have been shown either. Leave \
            it out for everything.

            Every answer says how many documents the corpus holds, so an empty \
            one is never ambiguous: a corpus with nothing in it and a name that \
            matched nothing say so in different words.

            One read and no model call — it is cheap, and it costs nothing to \
            do first. Names and titles come from documents somebody uploaded \
            and are not this system's claims.""";

  // --- the schema --------------------------------------------------------------

  /*
   * LinkedHashMap and never Map.of, matching MemoryTools and ToolSchema's own
   * copy: Map.of has no iteration order to preserve — it depends on a
   * per-JVM-run hash seed — so a schema built that way is emitted with its
   * keys shuffled, and shuffled differently on every launch. The model reads
   * these fields in order.
   */
  private static Map<String, Object> searchSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("question", ToolArguments.string("What you want to know, in plain language."));
    properties.put(
        "limit",
        ToolArguments.integer(
            "How many passages to return at most, from 1 to "
                + RetrievalService.MAX_HITS
                + ". Omit for "
                + RetrievalService.DEFAULT_HITS
                + "."));
    return ToolArguments.object(properties, List.of("question"));
  }

  /**
   * Every argument optional, which is the schema saying what the tool is for: the question it
   * answers first is "what is here at all", and that one takes nothing.
   */
  private static Map<String, Object> listSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "name",
        ToolArguments.string(
            "Part of the filename a document was filed under, or of its title. Case does"
                + " not matter and both are matched. Omit for the whole corpus."));
    properties.put(
        "limit",
        ToolArguments.integer(
            "How many documents to return at most, from 1 to "
                + DocumentStore.MOST_LISTED
                + ". Omit for "
                + LISTED
                + "."));
    properties.put(
        "offset",
        ToolArguments.integer("How many to skip, for reading past the first page. Omit for none."));
    return ToolArguments.object(properties, List.of());
  }

  // --- rendering ---------------------------------------------------------------

  /**
   * One hit: where it came from, what to cite, what to ask, and the text.
   *
   * <p>The heading first because it is what the choosing is done on, the citation on its own line
   * because it is what gets copied out, and the text last and quoted because everything after it
   * belongs to somebody else.
   *
   * <h2>Two ids, two lines, two labels</h2>
   *
   * <p><b>The document id was on {@link DocumentStore.Hit} from the day the search shipped and this
   * renderer dropped it</b>, which is TODO.md 4.5: {@code POST /v1/documents/&#123;id&#125;/ask}
   * and every surface over it take a document, and no rendering of a search produced one. A search
   * that finds the paper and cannot say which paper it found is a dead end at exactly the step
   * where the corpus-wide question becomes a question about one paper.
   *
   * <p><b>Separately labelled and separately explained, because they are not interchangeable.</b>
   * The paragraph id is the citation — V25 keys citations on it, and {@code librarian}'s body is
   * written around a claim being attached to one. The document id is a handle for asking. Two bare
   * uuids under one heading is a reader taught to use either for either, and the console's {@code
   * documents} screen already keeps this discipline in the other direction: it marks the paragraph
   * id as the citation and the chunk id as deliberately not one. So each id sits under the verb it
   * goes into and the count sentence above says which of them a citation is.
   *
   * <p><b>The citation line stays first and stays exactly as it was.</b> An addition rather than a
   * rewrite: {@code implementation rationale} measured a change to working model-visible text
   * moving behaviour 5/5 to 0/5 with 1 892 tests green either way, so the sentence and the line
   * that already do their job are not touched.
   *
   * <p><b>No tool is named here</b>, unlike the MCP renderer's twin. The agents holding {@code
   * document_search} are {@code librarian} and {@code interlocutor}, and neither holds {@link
   * AskTool}: naming a tool an agent cannot call would spend a turn teaching it that. What the id
   * is for is said in words instead, and it is true for the reader of the agent's answer, which is
   * who can act on it.
   */
  private static String render(DocumentStore.Hit hit) {
    return ToolArguments.oneLine(hit.sourceName())
        + " — paragraph "
        + hit.paragraphOrdinal()
        + " of \""
        + ToolArguments.oneLine(hit.title())
        + "\" — similarity "
        + similarity(hit)
        + "\ncite paragraph "
        + hit.paragraphId()
        + "\nask document "
        + hit.documentId()
        + "\n"
        + quote(hit.chunkText());
  }

  /**
   * The similarity, to two places and in a fixed locale.
   *
   * <p>{@code Locale.ROOT} rather than the default, which is {@code ResultTools.row}'s correction
   * in a different currency: {@code String.format} takes the server's locale, so a box set to a
   * comma decimal separator would hand the model {@code 0,82} — a model-visible string that changes
   * with a setting nobody reading the answer can see.
   *
   * <p>Two places because more of them would read as precision that is not there: this is a cosine
   * over an approximate index and the number is comparable between two hits of one search and
   * nothing else.
   */
  private static String similarity(DocumentStore.Hit hit) {
    return String.format(Locale.ROOT, "%.2f", hit.similarity());
  }

  /**
   * Every line prefixed, so no line of a chunk can reach column zero. Splits on {@link
   * ToolArguments#LINE_BREAK}, the pattern this method and {@code oneLine} used to each compile a
   * private copy of — moved to {@code ToolArguments} so a chunk's line splitting and a title's line
   * flattening share one compiled regex rather than two that agree.
   */
  private static String quote(String text) {
    StringBuilder quoted = new StringBuilder();
    for (String line : ToolArguments.LINE_BREAK.split(text, -1)) {
      if (quoted.length() > 0) {
        quoted.append('\n');
      }
      quoted.append(QUOTE).append(line);
    }
    return quoted.toString();
  }

  // oneLine used to be a private method here, byte-for-byte identical to
  // AskTool's own private copy; FetchTool then copied it a third time,
  // which is what a review caught. Every call site above now reaches
  // ToolArguments.oneLine directly rather than through a local wrapper --
  // see that method's javadoc for why it lives there instead.
}
