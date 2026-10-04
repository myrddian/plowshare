package io.aeyer.plowshare.client.tools;

import static io.aeyer.plowshare.client.tools.Schemas.object;
import static io.aeyer.plowshare.client.tools.Schemas.string;

import io.aeyer.plowshare.client.ServerClient;
import io.aeyer.plowshare.client.mcp.ToolRegistry;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.MemoryProposal;
import io.aeyer.plowshare.protocol.WriteResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The four memory tools an agent sees, and the whole of what this process exposes.
 *
 * <h2>The descriptions are the contract</h2>
 *
 * <p>A calling agent decides whether to invoke a tool from its description alone — it never sees
 * this code, the HTTP surface or the archive. The prose below follows Excalibur's {@code
 * mcp/server.py} register: what the tool does, what it costs, and when to reach for it. Two rules
 * that register keeps and this one keeps with it:
 *
 * <ul>
 *   <li><b>Say what it costs.</b> "Fast and free — no model runs" is what stops an agent hoarding
 *       index calls it could have made freely, and what stops it treating a recall as free when a
 *       model has to embed the question.
 *   <li><b>Never describe a capability this surface does not have.</b> Excalibur's {@code
 *       memory_write} tells the caller a scribe will decide whether the write is new, a refinement
 *       or a supersession. Plowshare's does now too — {@code Scribe} judges it server-side — so the
 *       description says so, and says the other half as well: that a write nothing judged is still
 *       written, as a new one, and says which failure that was. An agent that believed only the
 *       first half would write a correction and assume the stale memory had been retired by it,
 *       which is exactly what this description said the wrong way round while there was no scribe
 *       at all.
 * </ul>
 *
 * <h2>Output is prose, not JSON</h2>
 *
 * <p>Every handler returns a {@link String}, which {@code StdioTransport} passes through untouched.
 * The reader is a model, and a rendered memory with its provenance on its own line costs fewer
 * tokens and reads more reliably than the same record as JSON.
 *
 * <h2>An empty answer is never the report of a failure</h2>
 *
 * <p>{@link #NOTHING} is what an empty archive looks like, and it is only ever printed when the
 * server actually answered with nothing. A server that could not be reached raises {@link
 * ServerUnreachableException} instead, which {@code StdioTransport} turns into a result carrying
 * {@code isError} — so the model reads a sentence about infrastructure rather than a sentence about
 * the archive. Confusing those two is the failure this project has spent the most effort
 * preventing: "nothing is remembered" and "I could not ask" are indistinguishable once they render
 * the same way, and the first one is a conclusion an agent will act on.
 *
 * <p>There is a third way to produce an empty answer, and it is the quietest. A memory written
 * while the embedding endpoint was down has no vector, so every vector search skips it — while it
 * stays {@code active}, stays in the index and stays readable by id. An archive holding exactly one
 * memory could therefore answer "nothing in this archive is close to that question" and be wrong.
 * The server now returns how many memories a recall could not search; {@link #recall} says so in
 * the answer, and {@link #index} marks each such line {@link #UNSEARCHABLE}. Same rule as above,
 * one layer down: an empty answer may only ever mean the archive was asked and held nothing.
 */
public final class MemoryTools {

  /**
   * What an empty result says, and the one string a failure must never produce. {@code
   * MemoryToolsTest} holds both halves of that.
   */
  static final String NOTHING = "(no memories)";

  /**
   * The marker on a memory recall cannot reach, because it has no embedding.
   *
   * <p>One constant so the index marker and the recall footnote are the same words: an agent shown
   * {@code [not searchable]} on a line and told something else in a sentence has two facts to
   * reconcile instead of one.
   */
  static final String UNSEARCHABLE = "[not searchable]";

  /**
   * Recorded as the memory's author when the caller does not say who it is.
   *
   * <p>{@code formedBy} is required by {@code Validation}, so something has to go here; Excalibur
   * uses this same word as the default {@code by} on {@code memory_invalidate}. A default rather
   * than a rejection because a write refused over provenance is a memory lost over bookkeeping, and
   * the archive would rather hold the claim with a vague author than not hold it.
   */
  static final String UNKNOWN_AUTHOR = "unknown";

  private static final String QUOTE = "> ";

  /**
   * Every line terminator Java recognises, not the three {@code String.lines()} splits on. See
   * {@link #oneLine}.
   */
  private static final Pattern LINE_BREAK = Pattern.compile("\\R");

  private final ServerClient server;

  public MemoryTools(ServerClient server) {
    this.server = server;
  }

  /**
   * Add all four tools to a registry, in the order a caller should meet them: survey what is there,
   * then read it, then ask, then add.
   */
  public void registerOn(ToolRegistry registry) {
    registry.register("memory_index", INDEX_DESCRIPTION, indexSchema(), this::index);
    registry.register("memory_read", READ_DESCRIPTION, readSchema(), this::read);
    registry.register("memory_recall", RECALL_DESCRIPTION, recallSchema(), this::recall);
    registry.register("memory_write", WRITE_DESCRIPTION, writeSchema(), this::write);
  }

  // --- the descriptions ----------------------------------------------------

  static final String INDEX_DESCRIPTION =
      """
            List every active memory as a summary plus the scope describing when \
            it applies. Fast and free — no model runs. Call this first when you \
            want to see what is remembered, or before deciding whether to ask a \
            question.

            Pass `project` for one project's own memories; omit it for the \
            memories that hold everywhere. The index carries no memory contents: \
            fetch anything you intend to rely on with memory_read.""";

  static final String READ_DESCRIPTION =
      """
            Read specific memories in full, verbatim, with their provenance and \
            state. Fast and free — no model runs. Use when you already know which \
            memories you want, typically from memory_index or from a recall.

            Nobody has to ask you to read. A summary is not the memory: read the \
            ones that bear on what you are about to say or do before you rely on \
            them.

            A memory that has been superseded or invalidated is still readable \
            here, and the reason recorded on it is the point: it is what stops \
            you rediscovering a fact that stopped being true and acting on it \
            again.""";

  static final String RECALL_DESCRIPTION =
      """
            Ask the archive a question in natural language and get back the \
            memories that answer it, verbatim. Takes a moment — the question is \
            embedded and matched by meaning, so a memory that shares no words \
            with your question can still be the answer. Use when you do not \
            already know which memory you need, especially before starting work \
            in an unfamiliar area. Nobody has to ask you to.

            Pass `project` to ask from a project: its own memories come first, \
            and the ones that hold everywhere fill the rest. Retired memories are \
            never returned. An empty answer means the archive holds nothing \
            close to the question, not that the archive is broken — and if any \
            memory could not be searched at all, the answer says so and says how \
            many, so an empty result is never something you have to guess about.""";

  static final String WRITE_DESCRIPTION =
      """
            Record something worth remembering across sessions.

            Nobody has to ask you to write. When something qualifies, write it \
            when you learn it, on your own judgement — a person saying "remember \
            this" is one reason, not the only one.

            Write durable facts, hard-won gotchas, and decisions with the reason \
            behind them — the things a future agent would waste an afternoon \
            rediscovering. Do not write transcript summaries, restatements of \
            what the code already says, or notes that only matter to the task in \
            front of you.

            `summary` is one line: what this memory is. `scope` says when it \
            applies ("working on payments auth, or anything touching retries"), \
            and is how a future recall finds it — a vague scope is a memory \
            nobody gets back. `body` is the detail. `project` files it under one \
            project; omit it for something that holds everywhere. Say who you are \
            in `formed_by`.

            The write is never refused, and it takes a moment: a scribe on the \
            server decides whether this is a new memory, more detail on one \
            already held, or a replacement for one that has stopped being true. \
            The answer tells you which it chose and why, and names the memory it \
            merged into or replaced.

            You cannot choose that yourself, and there is no argument for it: \
            naming a memory to replace would retire one you have not read. Write \
            what is true now and say what changed in the summary.

            If nothing judged the write — no scribe is deployed, or it was busy \
            — the memory is still written, as a new one, and the answer says \
            which of those happened rather than leaving you to assume it was \
            judged.""";

  // --- the schemas ---------------------------------------------------------

  /*
   * Every schema is built from LinkedHashMap and never from Map.of. Map.of has
   * no iteration order to preserve — its order depends on a per-JVM-run hash
   * seed — so a schema built that way is emitted with its keys shuffled, and
   * shuffled differently on every launch. The model reads these fields in
   * order, and two runs of the same binary would present the same tool
   * differently.
   */

  private static Map<String, Object> indexSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "project",
        string("The project whose memories to list. Omit for the memories that hold everywhere."));
    return object(properties, List.of());
  }

  private static Map<String, Object> readSchema() {
    Map<String, Object> ids = new LinkedHashMap<>();
    ids.put("type", "array");
    ids.put("items", string("A memory id, as shown by memory_index or a recall."));
    ids.put("description", "The memories to read, by id.");

    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("ids", ids);
    return object(properties, List.of("ids"));
  }

  private static Map<String, Object> recallSchema() {
    Map<String, Object> limit = new LinkedHashMap<>();
    limit.put("type", "integer");
    limit.put("description", "How many memories to return at most. Omit for the default.");

    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("question", string("What you want to know, in plain language."));
    properties.put(
        "project", string("The project to ask from. Omit to ask only what holds everywhere."));
    properties.put("limit", limit);
    return object(properties, List.of("question"));
  }

  private static Map<String, Object> writeSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("summary", string("One line: the claim itself, stated so it stands alone."));
    properties.put(
        "scope",
        string("When this memory applies, in prose. This is how a future recall finds it."));
    properties.put("body", string("The detail behind the summary."));
    properties.put(
        "project",
        string("The project this belongs to. Omit for something that holds everywhere."));
    properties.put("formed_by", string("Who is writing this — you."));
    properties.put(
        "formed_where",
        string(
            "The situation it came out of, in prose. Optional, and worth filling in:"
                + " it is what a later reader judges the claim by."));
    return object(properties, List.of("summary", "scope", "body"));
  }

  // --- the handlers --------------------------------------------------------

  /**
   * {@code memory_index} — one tier's active memories, as index lines.
   *
   * <p>Also where an unembedded memory becomes nameable. The count on a recall says how many
   * memories could not be searched; this says <em>which</em>, which is what somebody needs to
   * repair them — and it puts the fact in the one projection an agent is shown before it asks
   * anything.
   */
  public Object index(Map<String, Object> args) {
    String project = project(args);
    List<ServerClient.IndexEntry> entries = ask(() -> server.index(project));

    if (entries.isEmpty()) {
      return NOTHING + " — the " + tier(project) + " archive holds nothing yet.";
    }

    StringBuilder out =
        new StringBuilder(entries.size() + " memories in the " + tier(project) + " archive:\n");
    int unsearchable = 0;
    for (ServerClient.IndexEntry entry : entries) {
      out.append('\n').append(oneLine(entry.id())).append("  ").append(oneLine(entry.summary()));
      if (entry.unsearchable()) {
        unsearchable++;
        // Marked on the line itself, next to the id, the way render()
        // already marks a memory's state — an agent reading this list
        // has to be able to see which entries memory_recall will never
        // hand back, or it will keep asking questions nothing can
        // answer.
        out.append("  ").append(UNSEARCHABLE);
      }
      // Indented by four, so an entry's own text cannot reach column
      // zero even before it is flattened — but flattened anyway, because
      // "indented" is a property of the first line only.
      out.append("\n    when: ").append(oneLine(entry.scope())).append('\n');
    }
    if (unsearchable > 0) {
      out.append('\n')
          .append(unsearchable)
          .append(" of these ")
          .append(unsearchable == 1 ? "is" : "are")
          .append(' ')
          .append(UNSEARCHABLE)
          .append(": written while the embedding endpoint was down, so ")
          .append(unsearchable == 1 ? "it has" : "they have")
          .append(" no vector and memory_recall cannot find ")
          .append(unsearchable == 1 ? "it" : "them")
          .append(" whatever the question. Nothing is lost — read ")
          .append(unsearchable == 1 ? "it" : "them")
          .append(" by id from this list. Ask an operator to re-embed the archive to" + " make ")
          .append(unsearchable == 1 ? "it" : "them")
          .append(" findable again.\n");
    }
    return out.toString();
  }

  /** {@code memory_read} — the named memories, in full. */
  public Object read(Map<String, Object> args) {
    List<String> ids = ids(args);
    if (ids.isEmpty()) {
      throw new IllegalArgumentException("memory_read needs at least one id in 'ids'");
    }

    List<String> rendered = new ArrayList<>(ids.size());
    for (String id : ids) {
      // One request per id, because the server exposes no batch read. The
      // difference from Archive.read, which resolves every id before it
      // saves anything, is that an unknown id partway through this list
      // leaves the uses already counted on the ids before it. The count
      // is a decay signal rather than a fact anyone reads back, so the
      // cost is a slightly favoured memory; recording it here so nobody
      // has to rediscover why the two paths differ.
      rendered.add(render(ask(() -> server.read(id))));
    }
    return String.join(SEPARATOR, rendered);
  }

  /**
   * {@code memory_recall} — the memories nearest a question.
   *
   * <p><b>An empty answer here is a claim about the archive, so it has to be a true one.</b> A
   * memory written while the embedding endpoint was down is stored, active and listed in the index,
   * and is skipped by every vector search — so an archive holding exactly one memory could answer
   * "nothing is close to that question" while holding the answer. The server now reports how many
   * memories were skipped that way, and every sentence below says so when the number is not zero:
   * an agent told the archive holds nothing close will rephrase and try again, forever, and it is
   * right to.
   */
  public Object recall(Map<String, Object> args) {
    String question = required(args, "question");
    String project = project(args);
    Integer limit = integer(args, "limit");

    ServerClient.Recall found = ask(() -> server.recall(project, question, limit));

    if (found.memories().isEmpty()) {
      // Only ever reached because the server answered with an empty list.
      // A server that could not be answered at all threw out of ask()
      // above, and never arrives here.
      return NOTHING
          + " — nothing in the "
          + tier(project)
          + " archive is close to that"
          + " question. Recall matches on meaning, so a differently framed question can"
          + " still find something."
          + skipped(found.unsearchable(), project);
    }

    List<String> rendered = new ArrayList<>(found.memories().size());
    for (Memory memory : found.memories()) {
      rendered.add(render(memory));
    }
    // The question is flattened too: it is the model's own text arriving
    // through an argument, and it lands at column zero directly above a
    // SEPARATOR-joined list of memories.
    return found.memories().size()
        + " memories for: "
        + oneLine(question)
        + skipped(found.unsearchable(), project)
        + "\n\n"
        + String.join(SEPARATOR, rendered);
  }

  /**
   * The sentence that turns "nothing matched" into "part of the archive could not be looked at", or
   * the empty string when the search was complete.
   *
   * <p>Appended to both the empty and the non-empty answer, because the partial answer is the more
   * dangerous of the two: an agent that got three memories has no reason to suspect a fourth was
   * unreachable, and will act on the three.
   */
  private static String skipped(int unsearchable, String project) {
    if (unsearchable <= 0) {
      return "";
    }
    return "\n\nIncomplete answer: "
        + unsearchable
        + " memor"
        + (unsearchable == 1 ? "y" : "ies")
        + " in the "
        + tier(project)
        + " archive "
        + (unsearchable == 1 ? "has" : "have")
        + " no embedding — written"
        + " while the embedding endpoint was down — so recall could not search "
        + (unsearchable == 1 ? "it" : "them")
        + " at all, whatever the question."
        + " Nothing is lost: memory_index lists "
        + (unsearchable == 1 ? "it" : "them")
        + " marked "
        + UNSEARCHABLE
        + " and memory_read returns "
        + (unsearchable == 1 ? "it" : "them")
        + " in full. Look there before concluding"
        + " the archive does not hold the answer.";
  }

  /** {@code memory_write} — propose a memory; the server judges its shape. */
  public Object write(Map<String, Object> args) {
    MemoryProposal proposal =
        new MemoryProposal(
            required(args, "summary"),
            required(args, "scope"),
            required(args, "body"),
            orElse(optional(args, "formed_by"), UNKNOWN_AUTHOR),
            // Excalibur defaults formed_where to the empty string rather
            // than requiring it, and Validation does not check it: an
            // omitted "where" is a memory with less context, not a
            // malformed one.
            orElse(optional(args, "formed_where"), ""));

    // No verdict. The judgement about shape — new, a refinement, or a
    // replacement — is the scribe's, server-side, made against candidates
    // it retrieves itself. This surface used to construct VerdictKind.NEW
    // here because there was no scribe; the alternative it did NOT take, and
    // still does not, is letting the calling agent name a target to
    // supersede, which would hand a caller the power to retire memories it
    // never read.
    String project = project(args);
    WriteResult result = ask(() -> server.write(project, proposal));

    StringBuilder out = new StringBuilder(became(result, project));
    // The reason, always, and on its own paragraph. It is the only place the
    // caller learns whether a scribe judged this write at all: every
    // server-side fallback opens "filed flat: " and says which one it was,
    // and dropping it here would make "no scribe is deployed" and "the
    // scribe merged this deliberately" the same output.
    // Flattened, because the reason is the scribe's own sentence, which is
    // to say a model's. Scribe flattens it on the way out too; this
    // renderer's rule is about what reaches column zero and not about who
    // is upstream of it.
    out.append("\n\n").append(oneLine(result.reason()));
    if (!result.demoted().isEmpty()) {
      // Surfaced rather than silent: a write that quietly pushed other
      // memories out of the index would make the index shrink for a
      // reason no caller could see.
      out.append("\n\nThe ")
          .append(tier(project))
          .append(" index was full, so these fell out of it: ")
          .append(
              result.demoted().stream().map(MemoryTools::oneLine).collect(Collectors.joining(", ")))
          .append(". They are still readable by id and still found by recall —")
          .append(" falling out of the index is not deletion.");
    }
    return out.toString();
  }

  /**
   * What the write became, in the shape the archive actually left things.
   *
   * <p>Each verdict is a different outcome and the sentence has to say which: a merge returns the
   * id of the memory that <em>absorbed</em> the text, so reporting "wrote mem_3" for one would be
   * true and would read as a new memory; a supersession retires the memory it names, and a caller
   * that did not know would keep expecting the old one back from recall.
   */
  private String became(WriteResult result, String project) {
    String where = " in the " + tier(project) + " archive";
    String id = oneLine(result.memoryId());
    String target = oneLine(result.targetId());
    return switch (result.kind()) {
      case NEW -> "Wrote " + id + where + ".";
      case MERGED_INTO ->
          "Added this to "
              + id
              + where
              + ", which it"
              + " refines. That memory is what recall returns; no new memory was made.";
      case SUPERSEDES ->
          "Wrote "
              + id
              + where
              + ", replacing "
              + target
              + ". "
              + target
              + " is retired: it is still readable by id, and recall will not return it"
              + " again.";
    };
  }

  // --- rendering -----------------------------------------------------------

  /**
   * Between two memories in one result. Long enough that a body containing blank lines cannot be
   * mistaken for the start of the next memory.
   */
  private static final String SEPARATOR = "\n\n----------------------------------------\n\n";

  /**
   * One memory as the model should read it: what it claims, when it applies, where it came from,
   * and — when it is retired — why.
   */
  /**
   * One memory, rendered so that <b>every line at column zero is one this renderer wrote.</b>
   *
   * <p>Every single-line slot goes through {@link #oneLine} and the body is quoted line by line.
   * Not escaping: nothing a memory contains can undo it, because there is no sequence that survives
   * flattening as a line break.
   *
   * <p><b>Why this is a boundary and not tidiness.</b> Memories are written by agents and read by
   * agents, so a renderer that lets stored content impersonate the runtime's own voice is a channel
   * from one agent's output into another agent's instructions. A {@code scope} shaped like {@code
   * "mem_000042 [active] everywhere"} with a line break in front of it rendered indistinguishably
   * from a second, genuine memory — inside a SEPARATOR-joined list of them. The v1 design draws
   * exactly this line for the event intake: events carry data only and can never supply an agent's
   * instructions, and memories are the same category.
   *
   * <p><b>This is the second copy of a rule the server also holds</b>, in {@code
   * io.aeyer.plowshare.server.agents.MemoryTools}. Ported rather than shared because there is
   * nowhere to share it from: this module depends on {@code plowshare-protocol} and never on the
   * server, deliberately, so that the client cannot see a JDBC driver or a dispatcher. Two copies
   * of one rule is the cost of that boundary, and it has already been paid once — Task 6 read the
   * server's copy, concluded the field it cared about was flattened, and was right about that
   * renderer and wrong about this one. <b>Do not reason about one from the other; open both.</b>
   *
   * <p>This copy's exposure is the wider of the two: it renders full bodies on {@code
   * memory_recall} as well as {@code memory_read}, where the server's recall tool shows summaries
   * only.
   */
  private static String render(Memory memory) {
    StringBuilder out = new StringBuilder();
    out.append(oneLine(memory.id()))
        .append("  [")
        .append(oneLine(memory.state().wireName()))
        .append("]  ")
        .append(
            memory.home().isGlobal() ? "everywhere" : "project " + oneLine(memory.home().project()))
        .append('\n');
    out.append(oneLine(memory.summary())).append('\n');
    out.append("when: ").append(oneLine(memory.scope())).append('\n');
    // formed().at() is an Instant and cannot carry a line break; by() and
    // where() are strings a caller supplies. where() is the one with nothing
    // at all between it and this renderer — Validation.check does not check
    // it and Archive.newMemory does not strip it — so it is the field an
    // ordinary write can put a line break into.
    out.append("formed: ")
        .append(memory.formed().at())
        .append(" by ")
        .append(oneLine(memory.formed().by()));
    if (memory.formed().where() != null && !memory.formed().where().isBlank()) {
      out.append(" — ").append(oneLine(memory.formed().where()));
    }
    out.append('\n');

    if (memory.supersedes() != null) {
      out.append("replaced: ").append(oneLine(memory.supersedes())).append('\n');
    }
    if (memory.supersededBy() != null) {
      out.append("superseded by: ")
          .append(oneLine(memory.supersededBy()))
          .append(" — prefer that one\n");
    }
    if (memory.invalidation() != null) {
      // The reason is the whole point of keeping an invalidated memory:
      // it is what stops the stale fact being rediscovered and written
      // straight back in.
      out.append("no longer true, as of ")
          .append(memory.invalidation().at())
          .append(" (")
          .append(oneLine(memory.invalidation().by()))
          .append("): ")
          .append(oneLine(memory.invalidation().reason()))
          .append('\n');
    }

    return out.append('\n').append(quote(memory.body())).toString();
  }

  /**
   * A single-line slot, flattened.
   *
   * <p>{@code \R} and not {@code String.lines()}, and the difference is measured rather than
   * stylistic: on this JDK {@code lines()} splits LF, CR and CRLF only, while {@code \R} also
   * splits VT, FF, NEL, U+2028 and U+2029. A body carrying U+2028 would reach column zero through a
   * splitter that only knows about LF — and a test helper that split on {@code \n} would model a
   * reader this argument does not assume.
   */
  static String oneLine(String value) {
    return value == null ? "" : LINE_BREAK.matcher(value).replaceAll(" ").strip();
  }

  /**
   * A body, quoted line by line, so no line of it starts at column zero.
   *
   * <p>The body is the one slot that legitimately has line breaks in it, so flattening would
   * destroy content rather than protect a boundary. Quoting keeps the shape and still leaves column
   * zero to this renderer.
   */
  static String quote(String body) {
    StringBuilder out = new StringBuilder(body.length() + 16);
    for (String line : LINE_BREAK.split(body.strip(), -1)) {
      if (out.length() > 0) {
        out.append('\n');
      }
      out.append(QUOTE).append(line);
    }
    return out.toString();
  }

  /**
   * "global" or "project 'payments'", for the sentences that have to name a tier. A message that
   * says "nothing was found" without saying where it looked cannot be acted on.
   */
  private static String tier(String project) {
    return project == null ? "global" : "project '" + oneLine(project) + "'";
  }

  // --- arguments -----------------------------------------------------------

  private static String required(Map<String, Object> args, String name) {
    String value = optional(args, name);
    if (value == null) {
      // Names the argument. A tool error that says only "invalid
      // arguments" costs the model a guess, and it usually guesses the
      // same way twice.
      throw new IllegalArgumentException("'" + name + "' is required and must not be empty");
    }
    return value;
  }

  /**
   * @return the argument, or {@code null} when it is absent or empty.
   */
  private static String optional(Map<String, Object> args, String name) {
    Object value = args.get(name);
    if (value == null) {
      return null;
    }
    String text = value.toString();
    // Empty is treated as absent — a model with no value for an optional
    // field sends "" about as often as it omits the key. `project` is the
    // one argument this rule must not apply to; see project(Map).
    return text.isEmpty() ? null : text;
  }

  /**
   * The {@code project} argument: the tier this call is about.
   *
   * <p>Not routed through {@link #optional}, which reads an empty string as an absent one. Here
   * that would be the bug {@code Home}'s own javadoc exists to prevent: an empty string is what
   * arrives from an unset field or a harness that stringified a missing value, and reading it as
   * "global" silently files a project's memory into the tier every project reads. The caller that
   * means global omits the key.
   */
  private static String project(Map<String, Object> args) {
    Object value = args.get("project");
    if (value == null) {
      return null;
    }
    String text = value.toString();
    if (text.isBlank()) {
      throw new IllegalArgumentException(
          "'project' was sent empty. Omit it entirely for the memories that hold"
              + " everywhere — an empty project is not the global tier.");
    }
    return text;
  }

  private static String orElse(String value, String fallback) {
    return value == null ? fallback : value;
  }

  private static Integer integer(Map<String, Object> args, String name) {
    Object value = args.get(name);
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      // Models send numbers as strings often enough that refusing one
      // would cost a whole tool call to learn nothing.
      return Integer.valueOf(value.toString().trim());
    } catch (NumberFormatException notANumber) {
      throw new IllegalArgumentException("'" + name + "' should be a whole number, not " + value);
    }
  }

  /**
   * The {@code ids} argument, which is a list — but is accepted as a single string too, because a
   * model asked for one memory sends {@code "mem_x"} rather than {@code ["mem_x"]} often enough
   * that refusing it would spend a turn teaching it the schema it was already given.
   */
  private static List<String> ids(Map<String, Object> args) {
    Object value = args.get("ids");
    if (value == null) {
      return List.of();
    }
    if (value instanceof List<?> list) {
      List<String> ids = new ArrayList<>(list.size());
      for (Object element : list) {
        if (element != null && !element.toString().isBlank()) {
          ids.add(element.toString());
        }
      }
      return List.copyOf(ids);
    }
    String single = value.toString();
    return single.isBlank() ? List.of() : List.of(single);
  }

  // --- reaching the server -------------------------------------------------

  /**
   * Run one call against the server, turning "could not reach it" into a sentence a model can act
   * on.
   *
   * <p>It is thrown rather than returned as text on purpose. {@code StdioTransport} answers a
   * thrown handler with a successful JSON-RPC result carrying {@code isError: true} and the message
   * as its content — so the model both reads the reason and is told it is a failure. Returning the
   * same sentence as an ordinary result would leave the model to decide, from prose alone, whether
   * it had just been told the archive is empty.
   */
  private <T> T ask(Call<T> call) {
    try {
      return call.get();
    } catch (IOException unreachable) {
      String uncertainty = MemoryTools.uncertainty(unreachable);
      if (uncertainty != null)
        throw new MemoryTools.ServerUnreachableException(uncertainty, unreachable);
      throw new ServerUnreachableException(
          "could not reach the Plowshare server at "
              + server.baseUrl()
              + " — "
              + describe(unreachable)
              + ". This says nothing about what is remembered: the archive was never"
              + " asked. Check the server is running, then try again.",
          unreachable);
    }
  }

  /**
   * The message, or the exception's type when it carries none — a ConnectException with a null
   * message would otherwise arrive as the word "null" in the middle of the sentence above.
   */
  /** Submitted WS work requires reconciliation before another mutation is issued. */
  static String uncertainty(IOException failure) {
    if (failure instanceof io.aeyer.plowshare.sdk.Plowshare.TransportException transport
        && transport.delivery() != io.aeyer.plowshare.sdk.Plowshare.Delivery.NOT_SUBMITTED)
      return "The Plowshare request has an unknown outcome. It may have been applied. No request was replayed. Read durable status or recover its receipt before submitting work again.";
    return null;
  }

  private static String describe(Throwable t) {
    String message = t.getMessage();
    return message == null || message.isBlank() ? t.toString() : message;
  }

  @FunctionalInterface
  private interface Call<T> {
    T get() throws IOException;
  }

  /**
   * The server could not be reached at all.
   *
   * <p>A distinct type from {@link ServerClient.ServerError}, which means the server answered and
   * said no: "the archive refused this proposal" and "I never got to ask" call for different
   * reactions, and a caller that cannot tell them apart will retry the wrong one.
   */
  public static final class ServerUnreachableException extends RuntimeException {
    public ServerUnreachableException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
