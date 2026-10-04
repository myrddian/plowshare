package io.aeyer.plowshare.client.tools;

import static io.aeyer.plowshare.client.tools.MemoryTools.oneLine;
import static io.aeyer.plowshare.client.tools.MemoryTools.quote;
import static io.aeyer.plowshare.client.tools.Schemas.object;
import static io.aeyer.plowshare.client.tools.Schemas.string;

import io.aeyer.plowshare.client.ServerClient;
import io.aeyer.plowshare.client.mcp.ToolRegistry;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reading a conversation back: which ones there are, what the model is shown, what actually
 * happened, and what it costs.
 *
 * <h2>The richest thing this system holds was invisible to the client that matters most</h2>
 *
 * <p>Before these, a foreign harness over MCP could <b>start</b> a Plowshare agent and collect its
 * result and nothing else. It could not list conversations, read one back, or see a trajectory — so
 * a whole archive of folded histories, timings, failed attempts and diagnostics sat on the server
 * with no door on this side. That is the gap these close, and the reason they are a first-class
 * output of the telemetry work rather than a convenience afterwards.
 *
 * <h2>Every listing is bounded, and the bound is stated twice</h2>
 *
 * <p><b>A trajectory can be thousands of entries, and a tool that returns all of them is a tool
 * that empties a context window.</b> {@code FileTools} settled this shape on the server and {@code
 * result_list} follows it: a cap the model can read before it calls, both numbers in the answer —
 * how many came back and how many there are — and the next call spelled out with the offset already
 * worked out, so nothing in the sentence has to be arithmetic.
 *
 * <p>Each entry's text is bounded a second time, at {@link #MOST_CHARACTERS_SHOWN}, and that bound
 * is <b>this file's and not the server's</b>: the server already cuts an entry at eight thousand
 * characters for a console to render, which is twenty times too much to put twenty of in front of a
 * model. The true length travels beside every excerpt, so nothing is shortened silently. <b>What
 * this surface cannot do is give the rest back</b> — {@code result_read} redeems a stored result's
 * handle inside a run and has no MCP equivalent — and a reader that meets a cut entry is told
 * exactly how much of it is missing rather than being left to wonder.
 *
 * <h2>Reading and never speaking</h2>
 *
 * <p>All five are reads. There is deliberately no {@code conversation_speak} here: an utterance
 * spends the allowance a person set for their own turns, and {@code AgentTools.run} already
 * declines to put a turn into somebody's conversation for exactly that reason. A harness that wants
 * to hold a conversation opens its own.
 *
 * <h2>Every line at column zero is one this renderer wrote</h2>
 *
 * <p>The rule the other three tool families keep, and it is sharper here than anywhere else on this
 * surface: an entry's text is <em>whatever was said or whatever a tool returned</em>, which is
 * arbitrary text with line breaks in it, and a trajectory is the one tool answer made almost
 * entirely of it. Excerpts go through {@link MemoryTools#quote} so they keep their shape and still
 * leave column zero alone; names, ids and arguments go through {@link MemoryTools#oneLine}.
 */
public final class ConversationTools {

  /**
   * The most rows any of these listings puts in one answer.
   *
   * <p>{@code ResultTools.Listing}'s number, and taken from it on purpose: the two are the same
   * kind of answer to the same kind of reader, and a second cap chosen independently would be a
   * second number to explain.
   */
  static final int MOST_LISTED = 20;

  /**
   * The most of one entry's text an answer shows.
   *
   * <p><b>Twenty rows is the bound that matters and this is the one that makes it hold.</b> The
   * server cuts an entry at eight thousand characters, which is right for a console rendering a
   * scrollback and is twenty times too much to put twenty of in front of a model — that answer
   * would be a hundred and sixty thousand characters, above what {@code file_read} allows a whole
   * turn. At this bound a full page is under ten thousand, which is one ordinary tool result.
   *
   * <p>It is a choice and not a measurement. What it is chosen against is that an utterance and an
   * answer are usually shorter than this and arrive whole, while a tool result is usually longer
   * and arrives as its opening — which is enough to tell what it was and not enough to work from,
   * and the length beside it is what says so.
   */
  static final int MOST_CHARACTERS_SHOWN = 500;

  /**
   * The most of one call's arguments an answer shows. Shorter than an entry's because arguments are
   * a path and a pattern most of the time, and a whole file the one time they are not.
   */
  static final int MOST_ARGUMENT_CHARACTERS = 200;

  /** What to say when a tier holds no conversations at all. */
  static final String NOTHING_OPEN = "(nothing open)";

  private final ServerClient server;

  public ConversationTools(ServerClient server) {
    this.server = server;
  }

  public void registerOn(ToolRegistry registry) {
    registry.register("conversation_list", LIST_DESCRIPTION, listSchema(), this::list);
    registry.register("conversation_search", SEARCH_DESCRIPTION, searchSchema(), this::search);
    registry.register(
        "conversation_chat",
        CHAT_DESCRIPTION,
        pageSchema(
            "Where in the conversation to start, counting from 0 at the first thing"
                + " the model is shown."),
        this::chat);
    registry.register(
        "conversation_trajectory",
        TRAJECTORY_DESCRIPTION,
        pageSchema("Where in the log to start, counting from 0 at the first thing" + " recorded."),
        this::trajectory);
    registry.register("conversation_context", CONTEXT_DESCRIPTION, contextSchema(), this::context);
  }

  // --- the tools -----------------------------------------------------------------

  /**
   * What is open in one tier.
   *
   * <p><b>The whole list crosses the wire and a page of it reaches the model</b>, which is worth
   * saying rather than implying. The server does not page this read and gives its reason — a tier
   * holds as many conversations as somebody opened, so a limit there would be a number invented
   * rather than measured — and that reasoning is about a console, which can scroll. A model cannot,
   * so the bound is applied here, where the answer is going somewhere with a context window.
   */
  public Object list(Map<String, Object> args) {
    String project = project(args);
    int offset = offset(args);

    List<ServerClient.Conversation> open = ask(() -> server.conversations(project));
    if (open.isEmpty()) {
      return "Nothing is open in "
          + tier(project)
          + ". "
          + NOTHING_OPEN
          + "\n\nA conversation is opened by whoever is going to speak in it; this"
          + " surface reads them and does not open one.";
    }
    if (offset >= open.size()) {
      return pastTheEnd(offset, open.size(), "conversation_list", "conversations");
    }
    int last = Math.min(offset + MOST_LISTED, open.size()) - 1;
    StringBuilder out = new StringBuilder();
    out.append("Conversations ")
        .append(offset)
        .append(" to ")
        .append(last)
        .append(" of ")
        .append(open.size())
        .append(" in ")
        .append(tier(project))
        .append(", counting from 0 as offset does, oldest first.\n");
    for (ServerClient.Conversation conversation : open.subList(offset, last + 1)) {
      out.append("\n")
          .append(oneLine(conversation.id()))
          .append(" — allowance ")
          .append(
              conversation.maxModelCalls() == null
                  ? "unlimited"
                  : conversation.maxModelCalls() + " model calls");
    }
    out.append(
        "\n\nRead one with conversation_chat for what the model is shown, or"
            + " conversation_trajectory for everything that happened in it.");
    more(out, last, open.size(), "conversation_list", null);
    return out.toString();
  }

  /**
   * Where, in one tier's conversations, something was said.
   *
   * <p><b>The only one of these five that finds a conversation rather than reading one.</b> The
   * other four all begin by naming a conversation or a tier and answering about it; this one starts
   * from words and answers with places. A harness that has an id already has {@code
   * conversation_chat}; a harness that has a phrase had nothing at all until now.
   *
   * <p><b>The reach is rendered as prose and never as three numbers.</b> A model shown "ejected: 4"
   * has to know what an ejection is to read it; a model told that four results had their payload
   * taken by a retention sweep, so their words are gone, knows why they are not in the list. {@code
   * conversation_context}'s rule about absences, applied to the counts that say what a search could
   * not look at.
   */
  public Object search(Map<String, Object> args) {
    String question = required(args, "q");
    String project = project(args);
    int offset = offset(args);

    ServerClient.LogHits found =
        ask(() -> server.searchEntries(project, question, offset, MOST_LISTED));
    return renderedHits(found, question, project, offset);
  }

  /**
   * What the model is shown in one conversation.
   *
   * <p>The projection: what a fold covered is gone, and so is everything whose kind never reaches a
   * model. <b>This is the conversation as the model reads it and not as a person had it</b> — the
   * assistant turns that were entirely tool calls are here, and so are the results that answered
   * them.
   */
  public Object chat(Map<String, Object> args) {
    String conversation = required(args, "conversation");
    int offset = offset(args);

    ServerClient.Entries page = ask(() -> server.chat(conversation, offset, MOST_LISTED));
    return rendered(
        page,
        offset,
        "conversation_chat",
        conversation,
        "what the model is shown in this conversation: what a fold covered is gone,"
            + " and so is everything whose kind never reaches a model");
  }

  /**
   * Everything that happened in one conversation.
   *
   * <p>The log, unfiltered. An entry a fold covered says which summary covered it; a {@code
   * diagnostic} is the harness talking about the conversation rather than in it; an {@code
   * attempt_failed} is a model call that was refused or abandoned, and is how a run that stopped is
   * legible at all. The timings are on every row.
   */
  public Object trajectory(Map<String, Object> args) {
    String conversation = required(args, "conversation");
    int offset = offset(args);

    ServerClient.Entries page = ask(() -> server.trajectory(conversation, offset, MOST_LISTED));
    return rendered(
        page,
        offset,
        "conversation_trajectory",
        conversation,
        "everything this conversation recorded, in the order it happened — including"
            + " what a fold covered, the harness's own diagnostics, and the model"
            + " calls that failed");
  }

  /**
   * What this conversation's prompt costs, and what the server declines to estimate about it.
   *
   * <p><b>The absences are the answer and are rendered as prose, not as blanks.</b> A reader shown
   * "system prompt: —" would take it for a number this call failed to fetch; what it means is that
   * the server measured the whole request with the model's own tokenizer and has no way to count a
   * part of it, and the reason travels with each one.
   */
  public Object context(Map<String, Object> args) {
    String conversation = required(args, "conversation");
    String agent = optional(args, "agent");

    ServerClient.Context context = ask(() -> server.context(conversation, agent));
    StringBuilder out = new StringBuilder();
    out.append("Conversation ")
        .append(oneLine(conversation))
        .append(": ")
        .append(context.turns())
        .append(" turns, ")
        .append(context.turnsMeasured())
        .append(" of them measured.\n\n");
    if (context.sent() == null) {
      out.append(
          "No turn of this conversation has reached a model call, so nothing has"
              + " been measured. That is not a cost of zero.");
    } else {
      out.append("The whole prompt of turn ")
          .append(context.sentAtTurn())
          .append(" cost ")
          .append(context.sent())
          .append(
              " tokens, counted by the model's own"
                  + " tokenizer. That is the entire request — the system prompt, the"
                  + " tool schemas and everything said — and it is the one token"
                  + " figure that exists.");
    }
    out.append("\n\nWhat cannot be given, and why:");
    for (ServerClient.Unavailable why : context.unavailable()) {
      out.append("\n\n").append(oneLine(why.component())).append(":\n").append(quote(why.reason()));
    }
    appendPrefix(out, context.prefix(), agent);
    return out.toString();
  }

  /**
   * The agent's fixed block, when one was named, and the invitation when none was.
   *
   * <p>The invitation matters more than it looks: a caller that never names an agent never learns
   * that the block can be priced at all, and the reason it has to name one — that a conversation
   * does not record which agent answered it — is a fact about this system worth stating where
   * somebody is looking at the consequence.
   */
  private static void appendPrefix(StringBuilder out, ServerClient.Prefix prefix, String agent) {
    if (prefix == null) {
      out.append(
          "\n\nNo agent was named, so the fixed block is not priced. A conversation"
              + " does not record which agent answered a turn — the agent is chosen per"
              + " turn — so this call cannot work it out; pass 'agent' to see what one"
              + " agent's system prompt and tool schemas cost, in characters.");
      return;
    }
    out.append("\n\nThe fixed block of '")
        .append(oneLine(prefix.agent()))
        .append("' on model '")
        .append(oneLine(prefix.model()))
        .append(
            "', in characters of the JSON actually sent — characters and not"
                + " tokens, and they must not be scaled into tokens:\n")
        .append("\nsystem prompt — ")
        .append(prefix.systemPromptCharacters())
        .append(" characters")
        .append("\ntool schemas — ")
        .append(prefix.toolCharacters())
        .append(" characters across ")
        .append(prefix.tools().size())
        .append(" tools");
    for (ServerClient.ToolCost tool : prefix.tools()) {
      out.append("\n  ")
          .append(oneLine(tool.name()))
          .append(" — ")
          .append(tool.characters())
          .append(" characters");
    }
    out.append("\n\nThis is what '")
        .append(oneLine(agent))
        .append(
            "' would be sent before"
                + " anything was said. It is a fact about the agent and not about this"
                + " conversation, which records no agent of its own.");
  }

  // --- rendering -----------------------------------------------------------------

  /**
   * One page of entries: the range and the total first, the rows, then the exact next call.
   *
   * <p>{@code ResultTools.Listing}'s order and its reasoning — the number that changes how the rest
   * is read goes before the rest, and the continuation goes last, where a model that has read the
   * rows and wants more will look for it.
   */
  private static String rendered(
      ServerClient.Entries page, int offset, String tool, String conversation, String what) {

    if (page.total() == 0) {
      return "Conversation "
          + oneLine(conversation)
          + " holds nothing on this reading:"
          + " "
          + what
          + ". Nothing has been said in it yet, or a fold has covered"
          + " everything there was.";
    }
    if (page.entries().isEmpty()) {
      return pastTheEnd(offset, page.total(), tool, "entries");
    }
    int last = offset + page.entries().size() - 1;
    StringBuilder out = new StringBuilder();
    out.append("Entries ")
        .append(offset)
        .append(" to ")
        .append(last)
        .append(" of ")
        .append(page.total())
        .append(" in conversation ")
        .append(oneLine(conversation))
        .append(", counting from 0 as offset does. This reading is ")
        .append(what)
        .append(".\n");
    for (ServerClient.Entry entry : page.entries()) {
      out.append('\n').append(row(entry));
    }
    more(out, last, page.total(), tool, conversation);
    return out.toString();
  }

  /**
   * One page of hits: the range and the total first, the hits, what the search could not look at,
   * then the exact next call.
   *
   * <p><b>The reach goes after the hits and before the continuation</b>, which is a deliberate
   * third position rather than a fourth thing appended. A reader that found what it wanted stops at
   * the hits; a reader that found nothing, or too little, reads on — and that is exactly the reader
   * for whom "four results had their payload ejected" changes the conclusion.
   */
  private static String renderedHits(
      ServerClient.LogHits found, String question, String project, int offset) {

    StringBuilder out = new StringBuilder();
    if (found.total() == 0) {
      out.append("Nothing in ")
          .append(tier(project))
          .append(" matches ")
          .append(oneLine(question))
          .append(".");
      appendReach(out, found.reach(), true);
      return out.toString();
    }
    if (found.hits().isEmpty()) {
      return pastTheEnd(offset, found.total(), "conversation_search", "matching entries");
    }
    int last = offset + found.hits().size() - 1;
    out.append("Entries ")
        .append(offset)
        .append(" to ")
        .append(last)
        .append(" of ")
        .append(found.total())
        .append(" in ")
        .append(tier(project))
        .append(" matching ")
        .append(oneLine(question))
        .append(
            ", counting from 0 as offset does, closest first. Read a conversation a"
                + " hit is in with conversation_trajectory.\n");
    for (ServerClient.LogHit hit : found.hits()) {
      out.append('\n').append(hitRow(hit));
    }
    appendReach(out, found.reach(), false);
    more(out, last, found.total(), "conversation_search", null, question);
    return out.toString();
  }

  /**
   * One hit: where it is, what it is, and the words around the match.
   *
   * <p>The heading line carries the conversation and the ordinal because <b>those are what make a
   * hit actionable</b> — a snippet with no address is a paragraph from nowhere. The snippet is
   * quoted, so an entry that is itself a numbered list cannot be mistaken for more rows.
   */
  private static String hitRow(ServerClient.LogHit hit) {
    StringBuilder out = new StringBuilder();
    out.append("[")
        .append(oneLine(hit.conversationId()))
        .append(" entry ")
        .append(hit.ordinal())
        .append("] ")
        .append(oneLine(hit.kind()))
        .append(" — turn ")
        .append(hit.turnOrdinal());
    if (hit.recordedAt() != null) {
      out.append(" — ").append(hit.recordedAt());
    }
    if (hit.supersededBy() != null) {
      // Named on a hit for the reason it is named on a page, and one more:
      // this is a row the model of that conversation can no longer see, so
      // a reader that goes looking for it in conversation_chat will not
      // find it and would otherwise conclude the search was wrong.
      out.append(" — folded away by the summary at ").append(hit.supersededBy());
    }
    if (hit.handle() != null) {
      out.append(" — handle ").append(oneLine(hit.handle()));
    }
    out.append('\n').append(quote(shortened(hit.snippet(), MOST_CHARACTERS_SHOWN)));
    // The entry's real length and not the snippet's, because a snippet is
    // the middle of an entry rather than its opening: "showing 500 of
    // 96 000" would be the wrong sentence, since the other 95 500 are not
    // after this text, they are around it.
    out.append("\n  (the words around the match; this entry is ")
        .append(hit.length())
        .append(" characters in all)");
    return out.toString();
  }

  /**
   * What the search could not look at, as prose.
   *
   * <p><b>Silent when there is nothing to say.</b> A tier with no ejected payloads and no roleless
   * kinds would otherwise carry two sentences saying "and nothing else", which trains a reader to
   * skip the paragraph in the case where it matters.
   */
  private static void appendReach(
      StringBuilder out, ServerClient.Reach reach, boolean nothingMatched) {
    out.append("\n\nSearched ")
        .append(reach.searched())
        .append(reach.searched() == 1 ? " entry" : " entries")
        .append(nothingMatched ? "." : " to find these.");
    if (reach.searched() == 0 && reach.ejected() == 0 && reach.recordedOnly() == 0) {
      out.append(
          " Nothing has been said in this tier at all, so this is not a question"
              + " that found nothing — there was nothing to ask it of.");
      return;
    }
    if (reach.ejected() > 0) {
      out.append(" ")
          .append(reach.ejected())
          .append(
              reach.ejected() == 1
                  ? " tool result had its payload ejected by a retention sweep, so its"
                      + " words are gone and it could not be searched"
                  : " tool results had their payloads ejected by a retention sweep, so"
                      + " their words are gone and they could not be searched")
          .append("; the entries are still in the trajectory, marked as ejected.");
    }
    if (reach.recordedOnly() > 0) {
      out.append(" ")
          .append(reach.recordedOnly())
          .append(" more ")
          .append(reach.recordedOnly() == 1 ? "entry is" : "entries are")
          .append(
              " of a kind no model is ever shown — a diagnostic, a failed"
                  + " attempt, a runtime note or a plan — and this searches what was"
                  + " said; read those with conversation_trajectory.");
    }
  }

  /**
   * One entry: what it is, when, how long it took, what it said, and what it asked for.
   *
   * <p>The heading line is this renderer's and the text below it is quoted, so an entry whose
   * content is itself a numbered list cannot be mistaken for more rows.
   */
  private static String row(ServerClient.Entry entry) {
    StringBuilder out = new StringBuilder();
    out.append("[")
        .append(entry.ordinal())
        .append("] ")
        .append(oneLine(entry.kind()))
        .append(" — turn ")
        .append(entry.turnOrdinal());
    if (entry.recordedAt() != null) {
      out.append(" — ").append(entry.recordedAt());
    }
    if (entry.tookMillis() != null) {
      out.append(" — took ").append(entry.tookMillis()).append(" ms");
    }
    if (entry.supersededBy() != null) {
      out.append(" — folded away by the summary at ").append(entry.supersededBy());
    }
    if (entry.handle() != null) {
      out.append(" — handle ").append(oneLine(entry.handle()));
    }
    // An ejected payload named rather than rendered as an empty entry. The
    // row is intact -- the call happened, at this ordinal, on this handle,
    // and it returned this many characters -- and only the text has gone; a
    // reader shown nothing at all here would read a hundred-thousand-
    // character file read as a tool that returned nothing.
    if (entry.ejectedAt() != null) {
      out.append(" — content ejected on ").append(entry.ejectedAt());
    } else if (!entry.excerpt().isBlank()) {
      out.append('\n').append(quote(shortened(entry.excerpt(), MOST_CHARACTERS_SHOWN)));
      int shown = Math.min(entry.excerpt().length(), MOST_CHARACTERS_SHOWN);
      if (shown < entry.length()) {
        out.append("\n  (showing ")
            .append(shown)
            .append(" of ")
            .append(entry.length())
            .append(" characters; the rest is not reachable from this surface)");
      }
    }
    for (ServerClient.Asked asked : entry.toolCalls()) {
      out.append("\n  calls ")
          .append(oneLine(asked.name()))
          .append(" (")
          .append(oneLine(asked.id()))
          .append(") with ")
          .append(oneLine(shortened(asked.arguments(), MOST_ARGUMENT_CHARACTERS)));
      int shown = Math.min(asked.arguments().length(), MOST_ARGUMENT_CHARACTERS);
      if (shown < asked.length()) {
        out.append(" (").append(shown).append(" of ").append(asked.length()).append(" characters)");
      }
    }
    return out.toString();
  }

  private static String shortened(String text, int most) {
    return text.length() <= most ? text : text.substring(0, most);
  }

  /**
   * The continuation note, or nothing when this page is the end of the list.
   *
   * <p>The next offset is a number and never an instruction to add one, which is {@code
   * file_read}'s correction: a model that has to do the arithmetic is a model that sometimes does
   * it wrong, and it has already been given every number it could get it wrong with.
   */
  private static void more(
      StringBuilder out, int last, int total, String tool, String conversation) {
    more(out, last, total, tool, conversation, null);
  }

  /**
   * The same note for a listing whose repeatable argument is a question rather than a conversation.
   * One body, because the sentence has to be the same sentence: a model that has learned to look
   * for it here should find it in the same words.
   */
  private static void more(
      StringBuilder out, int last, int total, String tool, String conversation, String question) {
    if (last >= total - 1) {
      return;
    }
    out.append("\n\nThere is more of this list: call ").append(tool);
    if (conversation != null) {
      out.append(" again with the same conversation and offset=");
    } else if (question != null) {
      out.append(" again with the same question and offset=");
    } else {
      out.append(" again with offset=");
    }
    out.append(last + 1).append('.');
  }

  /**
   * An offset past the end, which is a different fact from an empty list.
   *
   * <p>{@code ResultTools.Listing} makes the same distinction and {@code FileTools.Read} argues it:
   * a model told "nothing here" for an offset of 5 000 into a list of 57 concludes the list is
   * empty, which is the confident wrong answer this whole project is against.
   */
  private static String pastTheEnd(int offset, int total, String tool, String things) {
    return "There is nothing at offset "
        + offset
        + ": this holds "
        + total
        + " "
        + things
        + ", the last is at offset "
        + (total - 1)
        + ", and the list is not empty."
        + " Call "
        + tool
        + " again with offset=0 for the beginning.";
  }

  private static String tier(String project) {
    return project == null ? "the global tier" : "project '" + oneLine(project) + "'";
  }

  // --- the descriptions a model reads ---------------------------------------------

  static final String LIST_DESCRIPTION =
      "List the conversations open in one tier, oldest first. A conversation is a"
          + " person's thread of utterances with an agent, holding its own budget and"
          + " its whole history; this is how you find the id the other conversation_*"
          + " tools take. Bounded: "
          + MOST_LISTED
          + " at a time, with the total and"
          + " the next offset in the answer.";

  static final String SEARCH_DESCRIPTION =
      "Find where something was said, by its words, across every conversation in one"
          + " tier. This is how you get an id when you have a phrase and not a"
          + " conversation; the other conversation_* tools all start from an id."
          + " Each hit names the conversation and the entry number to read it at with"
          + " conversation_trajectory, and shows the words around the match rather"
          + " than the start of the entry, so a match in the middle of a large tool"
          + " result is visible. It matches on words and not on meaning: a question"
          + " matches an entry only if every one of its content words is in that"
          + " entry, stemmed, so 'refilling' finds 'refilled' and a question of"
          + " nothing but common words finds nothing. It searches what was said —"
          + " utterances, answers, tool results and the summaries a fold left,"
          + " including entries a fold has since covered — and not the harness's own"
          + " records of a run, which are read with conversation_trajectory. A tool"
          + " result whose payload a retention sweep has ejected cannot be found at"
          + " all, because its words are gone; the answer says how many of those"
          + " there were rather than leaving them silently missing. Bounded: "
          + MOST_LISTED
          + " hits at a time, each shown to "
          + MOST_CHARACTERS_SHOWN
          + " characters with the entry's real length beside it, and the total and"
          + " the next offset in the answer.";

  static final String CHAT_DESCRIPTION =
      "Read what the model is shown in one conversation, one page at a time. This is the"
          + " projection: entries a compaction folded away are gone and replaced by"
          + " the summary that stands for them, and kinds no model ever sees are"
          + " absent. Use this to see the conversation as the model reads it; use"
          + " conversation_trajectory to see what actually happened. Bounded: "
          + MOST_LISTED
          + " entries at a time, each shown to "
          + MOST_CHARACTERS_SHOWN
          + " characters with its real length beside it.";

  static final String TRAJECTORY_DESCRIPTION =
      "Read everything that happened in one conversation, one page at a time. Unfiltered:"
          + " the entries a compaction covered are still here and say which summary"
          + " covered them, alongside the harness's own diagnostics, the model calls"
          + " that were refused or abandoned, and how long each entry took. Use this"
          + " to work out what a run did and why it stopped; use conversation_chat"
          + " for the shorter reading the model itself is given. Bounded: "
          + MOST_LISTED
          + " entries at a time, each shown to "
          + MOST_CHARACTERS_SHOWN
          + " characters with its real length beside it.";

  static final String CONTEXT_DESCRIPTION =
      "What one conversation's prompt costs, and what cannot honestly be said about its"
          + " parts. The whole request is measured by the model's own tokenizer; the"
          + " system prompt's, the tools' and the messages' separate shares are not"
          + " available and the answer says why for each rather than estimating"
          + " them. Naming an agent additionally prices that agent's system prompt"
          + " and tool schemas in characters of JSON — characters, not tokens.";

  // --- schemas -------------------------------------------------------------------

  private static Map<String, Object> listSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "project",
        string(
            "Which tier to list. Omit for the global one; a project's conversations are"
                + " not in it."));
    properties.put(
        "offset",
        integerProperty(
            "Where in the list to start, counting from 0 at the oldest. Omit for the first "
                + MOST_LISTED
                + "."));
    return object(properties, List.of());
  }

  private static Map<String, Object> searchSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "q",
        string(
            "The words to look for, in prose. Every content word has to be present in an"
                + " entry for it to match, and common words are ignored — so a shorter"
                + " question finds more."));
    properties.put(
        "project",
        string(
            "Which tier to search. Omit for the global one; a project's conversations are"
                + " not in it, and this search does not cross the boundary."));
    properties.put(
        "offset",
        integerProperty(
            "Where in the hits to start, counting from 0 at the closest. Omit for the first "
                + MOST_LISTED
                + "."));
    return object(properties, List.of("q"));
  }

  private static Map<String, Object> pageSchema(String offsetDescription) {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "conversation", string("The conversation's id, as conversation_list showed it."));
    properties.put(
        "offset", integerProperty(offsetDescription + " Omit for the first " + MOST_LISTED + "."));
    return object(properties, List.of("conversation"));
  }

  private static Map<String, Object> contextSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "conversation", string("The conversation's id, as conversation_list showed it."));
    properties.put(
        "agent",
        string(
            "Which agent's fixed block to price, by name. Optional, and the conversation"
                + " cannot supply it: the agent is chosen per turn and no conversation"
                + " records which one answered."));
    return object(properties, List.of("conversation"));
  }

  /**
   * One integer-valued property.
   *
   * <p>Not in {@code Schemas} beside {@code string}: this is the first caller, and that class's own
   * javadoc draws the line at three copies. It moves there when a third family wants one.
   */
  private static Map<String, Object> integerProperty(String description) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "integer");
    schema.put("description", description);
    return schema;
  }

  // --- arguments -----------------------------------------------------------------

  private static String required(Map<String, Object> args, String name) {
    String value = optional(args, name);
    if (value == null) {
      throw new IllegalArgumentException("'" + name + "' is required and must not be empty");
    }
    return value;
  }

  private static String optional(Map<String, Object> args, String name) {
    Object value = args.get(name);
    if (value == null) {
      return null;
    }
    String text = value.toString();
    return text.isBlank() ? null : text;
  }

  /**
   * A tier a caller may leave out and may not send empty, on {@code AgentTools.project}'s
   * reasoning: an empty string is what an unset field sends, and reading it as global would
   * silently widen what is listed.
   */
  private static String project(Map<String, Object> args) {
    Object value = args.get("project");
    if (value == null) {
      return null;
    }
    String text = value.toString();
    if (text.isBlank()) {
      throw new IllegalArgumentException(
          "'project' was sent empty. Omit it entirely for the global tier — an empty"
              + " project is not the global tier.");
    }
    return text;
  }

  /**
   * Where to start, from zero, and never negative.
   *
   * <p>Refused here rather than passed on, so the message names the tool and the argument. The
   * server refuses it too and would answer a 400, which is correct and arrives as "the server said
   * no" — one layer further from the model that can fix it.
   */
  private static int offset(Map<String, Object> args) {
    Object value = args.get("offset");
    if (value == null) {
      return 0;
    }
    int offset;
    if (value instanceof Number number) {
      offset = number.intValue();
    } else {
      try {
        offset = Integer.parseInt(value.toString().trim());
      } catch (NumberFormatException notANumber) {
        throw new IllegalArgumentException(
            "'offset' should be a whole number, the position to start at, not "
                + value
                + ". Leave it out to start at the beginning.");
      }
    }
    if (offset < 0) {
      throw new IllegalArgumentException(
          "'offset' counts from 0 and there is nothing before that; this asked for "
              + offset
              + ". Leave it out to start at the beginning.");
    }
    return offset;
  }

  /**
   * One call, with an unreachable server said as prose rather than thrown.
   *
   * <p>The sentence is this family's own, for the reason {@code Schemas} declines to share {@code
   * ask}: what a reader needs to know differs per surface, and here it is that a read changed
   * nothing, so retrying is free.
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
              + ". Nothing was read and nothing was changed: this surface only"
              + " reads, so trying again costs nothing. Check the server is"
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
