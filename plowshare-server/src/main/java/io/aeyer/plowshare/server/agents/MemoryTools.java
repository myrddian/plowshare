package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.MemoryProposal;
import io.aeyer.plowshare.protocol.WriteResult;
import io.aeyer.plowshare.server.agents.ToolArguments.BadArguments;
import io.aeyer.plowshare.server.agents.scribe.Scribe;
import io.aeyer.plowshare.server.archive.Archive;
import io.aeyer.plowshare.server.archive.ArchiveException;
import io.aeyer.plowshare.server.archive.Validation;
import io.aeyer.plowshare.server.archive.ValidationException;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import io.aeyer.plowshare.server.requests.RequestedProposal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The two memory tools an agent gets: ask the archive a question, then read what it named.
 *
 * <h2>Two tools, because recall is a shortlist</h2>
 *
 * <p>{@link Recall} hands back ids, summaries and scopes; {@link Read} hands back bodies. That
 * split is what leaves the second tool a job, and it is the shape that was measured: against
 * qwen3.5-9b on the reference box, 2026-08-29, a run terminated as recall, then read, then answer,
 * in three turns. A recall that inlined every body would spend a 9b model's context on memories it
 * was about to discard, and would leave {@code memory_read} a tool with no reason to exist. The MCP
 * surface's {@code memory_recall} does return bodies, and that is not an inconsistency to fix: its
 * caller is a large model in a session with room to spare, and it has no turn budget to run out of.
 *
 * <h2>Errors are results, and the exceptions are the ones that are not the model's fault</h2>
 *
 * <p>Every parse failure, every missing argument and every id that does not exist comes back as
 * prose the model can read and correct on its next turn — see {@link AgentTool} for why, at length.
 * What is <b>not</b> caught is the archive being unable to answer at all: {@link Archive#recall}
 * throws when the question cannot be embedded, and rendering that as "nothing was found" would hand
 * the model a confident empty answer for a search that never ran. That is the same failure {@link
 * Archive.Recall#unsearchable()} exists to prevent from the other side, and this class surfaces
 * that count for the same reason.
 *
 * <h2>A memory's own content cannot forge this renderer's voice</h2>
 *
 * <p>The premise of the whole system is that agents write memories other agents later recall, which
 * makes stored content a channel from one agent's output into another agent's input. {@code
 * Validation.check} constrains only the summary to a single line and the body only by length, so a
 * {@code scope}, a body, an invalidation reason or a project name may all carry line breaks — and
 * each of them lands in text a model reads as structure. Left alone, a body holding {@code
 * mem_000042 [active] everywhere} on its own line renders indistinguishably from a second genuine
 * memory, separator and all.
 *
 * <p><b>The rule: every line at column zero is one this class wrote.</b> A field in a single-line
 * slot has its line breaks flattened by {@link #oneLine}; the body, the one genuinely multi-line
 * field, is quoted line by line by {@link #quote}. There is no escape sequence, so there is nothing
 * a body can contain that undoes it — which is why this is done here and not in {@code Validation}:
 * refusing delimiter-shaped text at write time would reject a legitimate memory <em>about</em> this
 * output format, and would change a write contract the MCP surface shares. The renderer owns its
 * own boundaries.
 *
 * <p>Line breaks are matched with the regex {@code \R} rather than {@code String.lines()}. Measured
 * on this JDK: {@code lines()} splits LF, CR and CRLF only, while {@code \R} also splits VT, FF,
 * NEL, U+2028 and U+2029 — five characters a reader may still break on and a {@code lines()}-based
 * renderer would pass straight through.
 *
 * <h2>The tier is not an argument</h2>
 *
 * <p>Neither schema has a {@code project} field. {@code home} arrives as a parameter of {@link
 * AgentTool#run}, from whoever started the job: <b>a tool must not let an agent name its own
 * tier.</b>
 *
 * <p>Both tools are immutable and hold only an {@link Archive}, so one instance of each serves
 * every job.
 */
public final class MemoryTools {

  /**
   * The name the model calls, the job log records, and {@code AgentRegistry.load} is told about.
   * Spelled once.
   */
  public static final String RECALL_NAME = "memory_recall";

  /** As {@link #RECALL_NAME}. */
  public static final String READ_NAME = "memory_read";

  /**
   * As {@link #RECALL_NAME}. The same name the MCP surface uses, deliberately: see the design's §3
   * for why parity beat accuracy here.
   */
  public static final String WRITE_NAME = "memory_write";

  /**
   * What a memory with no named author is recorded as, matching the MCP surface's own default: an
   * omitted author is a memory with less provenance, not a malformed one.
   */
  private static final String UNKNOWN_AUTHOR = "unknown";

  /**
   * How many memories a recall returns when the model does not say. Deliberately small: this is a
   * shortlist to choose from, not the answer.
   */
  public static final int DEFAULT_LIMIT = 5;

  /**
   * The most one recall returns, however large a number the model sends. A local 9b model's context
   * is the budget being protected, and a model that asks for a hundred is guessing rather than
   * needing them.
   */
  public static final int MAX_LIMIT = 20;

  /**
   * Between two rendered memories, and unforgeable because it starts at column zero and a body
   * cannot reach column zero. The length is belt and braces for a reader skimming rather than
   * parsing.
   */
  static final String SEPARATOR = "\n\n----------------------------------------\n\n";

  /**
   * What every line of a body is prefixed with. The quoting convention a reader already knows,
   * which is why it is this and not an indent: a model reads {@code >} as somebody else's words.
   */
  private static final String QUOTE = "> ";

  /**
   * The full Unicode linebreak set — see the class javadoc for why this and not {@code
   * String.lines()}.
   */
  private static final Pattern LINE_BREAK = Pattern.compile("\\R");

  private MemoryTools() {}

  // --- memory_recall -----------------------------------------------------------

  /**
   * {@code memory_recall} — the memories nearest a question, as a shortlist.
   *
   * <p><b>An empty answer here is a claim about the archive, so it has to be a true one.</b> A
   * memory written while the embedding endpoint was down is stored, active, listed in the index,
   * and skipped by every vector search — so an archive holding exactly one memory could answer
   * "nothing is close to that question" while holding the answer. {@link Archive.Recall} carries
   * the count of what could not be searched, and every sentence below says so when it is not zero:
   * an agent told the archive holds nothing close will rephrase and try again, forever, and it is
   * right to.
   */
  public static final class Recall implements AgentTool {

    private final Archive archive;
    private final ToolSchema schema;

    public Recall(Archive archive) {
      this.archive = Objects.requireNonNull(archive, "archive");
      this.schema = ToolSchema.from(RECALL_NAME, RECALL_DESCRIPTION, recallSchema());
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
      // model's, and the never-throw rule is a rule about a caller's
      // mistakes. See AgentTool.
      Objects.requireNonNull(argumentsJson, "argumentsJson");
      Objects.requireNonNull(home, "home");
      try {
        return answer(argumentsJson, home, owner);
      } catch (BadArguments unusable) {
        // Every validation failure below lands here and becomes the tool
        // result. Nothing else is caught: an archive that could not be
        // asked is not a mistake the model made, and is not one it can
        // correct by calling again with better arguments.
        return unusable.getMessage();
      }
    }

    private String answer(String argumentsJson, Home home, UsageAttribution owner) {
      JsonNode args =
          ToolArguments.parse(
              argumentsJson, RECALL_NAME, "{\"question\": \"how does payments authenticate\"}");
      String question =
          ToolArguments.requireText(
              args, "question", RECALL_NAME, "one sentence saying what you want to know");
      int asked = limit(args);
      int limit = Math.min(asked, MAX_LIMIT);

      Archive.Recall found =
          owner.status() == UsageAttribution.Status.LEGACY_UNATTRIBUTED
              ? archive.recall(question, home, limit)
              : archive.recall(question, home, limit, owner);

      StringBuilder out = new StringBuilder();
      if (found.memories().isEmpty()) {
        out.append("Nothing recalled — nothing in the ")
            .append(tier(home))
            .append(" archive is close to that question. Recall matches on meaning,")
            .append(" so a differently framed question can still find something.");
      } else {
        out.append(found.memories().size())
            .append(found.memories().size() == 1 ? " memory in the " : " memories in the ")
            .append(tier(home))
            .append(" archive, nearest to that question first.")
            .append(" These are summaries; read any of them in full with ")
            .append(READ_NAME)
            .append(".\n");
        for (Memory memory : found.memories()) {
          out.append('\n').append(summarise(memory)).append('\n');
        }
      }
      if (asked > MAX_LIMIT) {
        // Said rather than done quietly: a model that asked for a
        // hundred and was handed twenty in silence cannot tell a cap
        // from an archive that small, and the second reading is a
        // wrong belief about what is remembered.
        out.append("\n\nYou asked for ")
            .append(asked)
            .append("; ")
            .append(MAX_LIMIT)
            .append(" is the most this tool returns at once.");
      }
      // Outside the empty/non-empty branch above, and that is the
      // guard. The failure this count exists to prevent is an *empty*
      // recall over an archive that holds the answer and could not search
      // it — appended only to a non-empty answer, the footnote would be
      // missing from exactly the case it was written for, and the model
      // would read "nothing is close to that question" about an archive
      // that was never searched. See
      // an_empty_recall_over_an_archive_it_could_not_search_says_both_things.
      out.append(skipped(found.unsearchable(), home));
      return out.toString();
    }

    /**
     * The {@code limit} argument, defaulted and refused when it is not a number this tool can act
     * on.
     *
     * <p>A non-positive limit is refused rather than passed through. {@link Archive#recall} returns
     * an empty result for one — read from its source — which this class would then render as
     * "nothing in the archive is close to that question": a false claim about what is remembered,
     * made because the model typed a zero.
     */
    private static int limit(JsonNode args) {
      // The reading is ToolArguments' — the default, the leniency about a
      // number sent as a string, and the measured reason canConvertToInt
      // comes before asInt are all argued there, and this was the
      // implementation they were generalised from when file_read became
      // the second tool to need them. The floor stays here, because the
      // sentence it throws is about this tool's archive and nothing
      // general can write it.
      int value = ToolArguments.optionalInt(args, "limit", DEFAULT_LIMIT, Recall::badLimit);
      if (value <= 0) {
        throw new BadArguments(
            RECALL_NAME
                + " was given a 'limit' of "
                + value
                + ". It must be 1 or more; asking for none is not the same as finding"
                + " none, and this tool will not report one as the other.");
      }
      return value;
    }

    private static BadArguments badLimit(JsonNode limit) {
      return new BadArguments(
          RECALL_NAME
              + " could not read 'limit': it must be a whole"
              + " number from 1 to "
              + MAX_LIMIT
              + ", not "
              + limit
              + ". Leave it out for"
              + " the default of "
              + DEFAULT_LIMIT
              + ".");
    }
  }

  // --- memory_read -------------------------------------------------------------

  /**
   * {@code memory_read} — the named memories, in full.
   *
   * <p>Reads any state, tombstones included, which is deliberate: the reason a fact stopped being
   * true is what stops the next agent meeting the same evidence and writing the stale fact straight
   * back in.
   *
   * <p><b>Not filtered by {@code home}.</b> {@link Archive#read} takes no home, and this does not
   * add one. Ids reach a model from a recall, which is tier-scoped already, and the MCP surface's
   * {@code memory_read} behaves the same way — but the load-bearing reason is Task 6: promotion has
   * to read a project's memory in order to propose it for global, and a tier filter here would make
   * the one operation that crosses tiers impossible to write. What an agent cannot do is
   * <em>discover</em> another project's ids, which is the containment recall enforces.
   *
   * <p>Asserted by {@code read_is_not_filtered_by_the_home_the_job_runs_in}, not merely written
   * down here. Every other read in that suite passes a home matching the memories it reads, so
   * adding a tier filter would otherwise change the decision with nothing going red.
   */
  public static final class Read implements AgentTool {

    private final Archive archive;
    private final ToolSchema schema;

    public Read(Archive archive) {
      this.archive = Objects.requireNonNull(archive, "archive");
      this.schema = ToolSchema.from(READ_NAME, READ_DESCRIPTION, readSchema());
    }

    @Override
    public ToolSchema schema() {
      return schema;
    }

    @Override
    public String run(String argumentsJson, Home home) {
      Objects.requireNonNull(argumentsJson, "argumentsJson");
      Objects.requireNonNull(home, "home");
      try {
        return answer(argumentsJson);
      } catch (BadArguments unusable) {
        return unusable.getMessage();
      }
    }

    private String answer(String argumentsJson) {
      JsonNode args = ToolArguments.parse(argumentsJson, READ_NAME, "{\"ids\": [\"mem_000001\"]}");
      List<String> ids = ids(args);

      List<Memory> memories;
      try {
        memories = archive.read(ids);
      } catch (ArchiveException unknownId) {
        return missing(ids, unknownId);
      }

      List<String> rendered = new ArrayList<>(memories.size());
      for (Memory memory : memories) {
        rendered.add(render(memory));
      }
      return String.join(SEPARATOR, rendered);
    }

    /**
     * The ids that do not exist, named — all of them, not the first.
     *
     * <p>A tool that returned {@code "[]"} for an id the model invented teaches it the archive is
     * empty; one that names the id teaches it the id was wrong. {@link Archive#read} throws on the
     * first id it cannot resolve, so the rest are found here with {@link Archive#get}, which counts
     * no use and so cannot disturb decay by being called on the error path. One id per turn would
     * otherwise cost a turn per typo.
     *
     * <p>{@link Archive#read} also resolves every id before it returns any — read from its source,
     * whose javadoc says why: a batch naming one unknown id counts no uses at all rather than
     * counting the ids that sorted before it. So <em>nothing</em> was read, and the message says
     * that too, or a model reads a result mentioning only the bad id as proof the good one does not
     * exist either.
     *
     * <p><b>Untested guard, and the one this class admits rather than pins:</b> if no id turns out
     * to be missing, the original exception is rethrown rather than dressed up as a spelling
     * mistake. Reaching it needs an {@code ArchiveException} that does not name an unknown id, or a
     * row deleted between the two calls, and neither exists: {@code MemoryStore} raises Spring's
     * {@code DataAccessException} for a database that is unreachable, which is not caught here and
     * propagates, and nothing in this codebase deletes rows. Recorded rather than left to read as
     * covered.
     */
    private String missing(List<String> ids, ArchiveException unknownId) {
      List<String> absent = new ArrayList<>();
      for (String id : ids) {
        try {
          archive.get(id);
        } catch (ArchiveException gone) {
          absent.add("'" + id + "'");
        }
      }
      if (absent.isEmpty()) {
        throw unknownId;
      }
      return READ_NAME
          + " found no memory with "
          + (absent.size() == 1 ? "id " : "these ids: ")
          + String.join(", ", absent)
          + ". Nothing was read: the archive resolves every id before it returns any,"
          + " so any ids you named that do exist were not returned either. Ids come"
          + " from "
          + RECALL_NAME
          + " — check them there and call again.";
    }

    /**
     * The {@code ids} argument, as a list of at least one non-blank id.
     *
     * <p>A bare string is accepted as a single id. A model sending one id without wrapping it in an
     * array is an obvious slip, and refusing it costs a turn to teach the model something it could
     * have been given.
     */
    private static List<String> ids(JsonNode args) {
      JsonNode ids = args.path("ids");
      if (ids.isTextual() && !ids.asText().isBlank()) {
        return List.of(ids.asText().strip());
      }
      if (!ids.isArray() || ids.isEmpty()) {
        throw badIds(ids);
      }
      List<String> collected = new ArrayList<>(ids.size());
      for (JsonNode id : ids) {
        // isTextual and not asText: measured against Jackson 2.17.2,
        // this project's version, a NullNode's asText() returns the
        // four-character string "null", so a JSON null read that way
        // becomes an id the archive is then asked for by name.
        if (!id.isTextual() || id.asText().isBlank()) {
          throw badIds(ids);
        }
        collected.add(id.asText().strip());
      }
      return List.copyOf(collected);
    }

    private static BadArguments badIds(JsonNode ids) {
      return new BadArguments(
          READ_NAME
              + " needs 'ids': the memories to read, as a list"
              + " of memory ids like [\"mem_000001\"]. A single id may be sent on its own."
              + " What arrived was "
              + (ids.isMissingNode() ? "nothing" : ids.toString())
              + ". Ids come from "
              + RECALL_NAME
              + ".");
    }
  }

  // --- memory_write ------------------------------------------------------------

  /**
   * {@code memory_write} — propose a memory; the scribe decides its shape.
   *
   * <p><b>It proposes and it does not write</b>, which is what the MCP tool of the same name has
   * always done: no {@code verdict} is accepted here, and {@link RequestedProposal#toFile} refuses
   * one in the same sentence the HTTP and frame surfaces refuse it in. A verdict naming a target
   * would retire a memory this caller never read.
   *
   * <p><b>Synchronous, and the reason is the reason.</b> {@code Scribe} files seventeen distinct
   * fallback reasons so that "no scribe is deployed" and "the scribe was busy" can be told apart;
   * they reach nobody who can act on them unless the caller is shown them. That is why this returns
   * the verdict rather than an acknowledgement.
   */
  public static final class Write implements AgentTool {

    private final Archive archive;
    private final Supplier<Scribe> scribe;
    private final ToolSchema schema;

    public Write(Archive archive, Supplier<Scribe> scribe) {
      this.archive = Objects.requireNonNull(archive, "archive");
      this.scribe = Objects.requireNonNull(scribe, "scribe");
      this.schema = ToolSchema.from(WRITE_NAME, WRITE_DESCRIPTION, writeSchema());
    }

    private java.util.function.BooleanSupplier restricted = () -> false;

    public Write forRun(io.aeyer.plowshare.server.information.InformationJobs inputs, String log) {
      Write copy = new Write(archive, scribe);
      copy.restricted = () -> inputs != null && log != null && inputs.hasInputs(log);
      return copy;
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
      Objects.requireNonNull(argumentsJson, "argumentsJson");
      Objects.requireNonNull(home, "home");
      if (restricted.getAsBoolean())
        return "Document-derived material must retain its input restrictions. Record it with information_write as a report; memory_write cannot publish it into the memory archive.";
      try {
        return filed(argumentsJson, home, owner);
      } catch (BadArguments unusable) {
        return unusable.getMessage();
      } catch (CallerFault refused) {
        // The no-verdict refusal, in RequestedProposal's words. A result
        // and not a throw for BadArguments' reason exactly: the model can
        // correct it by calling again without the field.
        return refused.getMessage();
      } catch (ValidationException malformed) {
        // Blank summary, blank scope, an oversize body. Also the model's to
        // fix, and the message already names the field.
        return malformed.getMessage();
      }
    }

    private String filed(String argumentsJson, Home home, UsageAttribution owner) {
      JsonNode args =
          ToolArguments.parse(
              argumentsJson,
              WRITE_NAME,
              "{\"summary\": \"payments authenticate with mTLS\","
                  + " \"scope\": \"the payments service\","
                  + " \"body\": \"The gateway pins a client cert.\"}");
      MemoryProposal proposal;
      try {
        proposal =
            RequestedProposal.toFile(
                new MemoryProposal(
                    ToolArguments.requireText(
                        args, "summary", WRITE_NAME, "the claim, in one line"),
                    ToolArguments.requireText(args, "scope", WRITE_NAME, "when this applies"),
                    ToolArguments.requireText(
                        args, "body", WRITE_NAME, "the whole of it, and why it is true"),
                    author(args),
                    where(args)),
                args.hasNonNull("verdict"));
      } catch (IllegalArgumentException invalid) {
        throw new BadArguments(invalid.getMessage());
      }
      Validation.check(proposal, archive.maxBodyChars());
      Scribe.Judgement judged =
          owner.status() == UsageAttribution.Status.LEGACY_UNATTRIBUTED
              ? scribe.get().judge(proposal, home)
              : scribe.get().judge(proposal, home, owner);
      return render(
          owner.status() == UsageAttribution.Status.LEGACY_UNATTRIBUTED
              ? archive.applyVerdict(proposal, judged.verdict(), home, judged.embedding())
              : archive.applyVerdict(
                  proposal, judged.verdict(), home, judged.embedding(), id -> {}, owner));
    }

    private static String author(JsonNode args) {
      String named = ToolArguments.optionalText(args, "formed_by", Write::badAuthor);
      return named == null ? UNKNOWN_AUTHOR : named;
    }

    private static String where(JsonNode args) {
      String named = ToolArguments.optionalText(args, "formed_where", Write::badWhere);
      return named == null ? "" : named;
    }

    private static BadArguments badAuthor(JsonNode value) {
      return new BadArguments(
          "memory_write's 'formed_by' must be text naming who"
              + " formed this memory, or be left out. Nothing was written.");
    }

    private static BadArguments badWhere(JsonNode value) {
      return new BadArguments(
          "memory_write's 'formed_where' must be text naming where"
              + " this came from, or be left out. Nothing was written.");
    }
  }

  // --- the descriptions --------------------------------------------------------

  /*
   * A calling model decides whether to invoke a tool from its description
   * alone — it never sees this code or the archive. These follow the register
   * the MCP surface's descriptions set, and keep its two rules: say what the
   * tool costs, and never describe a capability this surface does not have.
   * Neither mentions a project, because neither takes one.
   *
   * AND EACH SAYS NOBODY HAS TO ASK. Observed by the owner on gpt-oss-120b,
   * 2026-09-13: with a write tool described only by what to write, the model
   * read it as an explicit action, like file_write, and would never remember
   * anything unprompted. That
   * is a fact about the tool, true for every agent that holds it, so it is
   * said here once rather than in each definition — WHEN a particular agent
   * should remember is policy, and policy is the definition's or the
   * harness's, never the tool's.
   *
   * The two sentences about what the system already does for the model are
   * load-bearing, not reassurance: Reminder appends a shortlist after the
   * person's message on every turn of an agent that can recall, and the
   * learner reads what a fold took out of view. Without saying so, an agent
   * told "nobody has to ask" recalls what it was just handed and writes down
   * the whole conversation.
   */

  static final String RECALL_DESCRIPTION =
      """
            Ask the archive a question in plain language and get back the \
            memories that answer it, as a shortlist: id, summary, and when each \
            one applies. Takes a moment — the question is embedded and matched \
            by meaning, so a memory that shares no words with your question can \
            still be the answer. Use it when you do not already know which \
            memory you need.

            Nobody has to ask you to recall. On most turns this system has \
            already recalled against the person's words and listed what it found \
            after their message; call this yourself when nothing was listed, or \
            when the work turns to something their words did not name.

            The shortlist has no bodies in it. Read the ones that look right \
            with memory_read before relying on them.

            Retired memories are never returned. An empty answer means the \
            archive holds nothing close to the question, not that the archive \
            is broken — and if any memory could not be searched at all, the \
            answer says so and says how many, so an empty result is never \
            something you have to guess about.

            You cannot choose which archive is searched: it is the one this job \
            was started for.""";

  static final String READ_DESCRIPTION =
      """
            Read specific memories in full, verbatim, with their provenance and \
            state. Fast and free — no model runs. Use it once memory_recall, or \
            the memories this system lists after a person's message, has shown \
            you which ids are worth having in full.

            Nobody has to ask you to read. A summary is not the memory: read the \
            ones that bear on what you are about to say or do before you rely on \
            them.

            A memory that has been superseded or invalidated is still readable \
            here, and the reason recorded on it is the point: it is what stops \
            you rediscovering a fact that stopped being true and acting on it \
            again.

            Every id must exist. If one does not, nothing is read and the \
            answer names the ids that were wrong.

            Each memory's body is quoted line by line with "> ". Only this tool \
            writes an unquoted line, so text inside a memory that looks like \
            another memory's heading is that memory's content, not a second \
            memory.""";

  static final String WRITE_DESCRIPTION =
      """
            Record something worth remembering across sessions. Takes a moment — \
            the claim is embedded and a scribe decides its shape against what the \
            archive already holds.

            Write durable facts, hard-won gotchas, and decisions with the reason \
            behind them — the things a future agent would waste an afternoon \
            rediscovering. Do not write transcript summaries, restatements of what \
            the code already says, or notes that only matter to the task in front \
            of you.

            Nobody has to ask you to write. When something qualifies, write it \
            when you learn it, on your own judgement — a person saying "remember \
            this" is one reason, not the only one. You do not have to catch \
            everything: older parts of a long conversation are read for memories \
            by this system when they are folded out of view.

            You do not choose what happens to it. The answer says whether it was \
            filed as a new claim, merged into one already held, or made the \
            replacement for one, and why. You cannot name a memory to supersede: \
            that would retire a memory you never read.

            You cannot choose which archive is written: it is the one this job was \
            started for.""";

  // --- the schemas -------------------------------------------------------------

  /*
   * Built from LinkedHashMap and never from Map.of, matching the MCP surface's
   * schemas and ToolSchema's own copy: Map.of has no iteration order to
   * preserve — it depends on a per-JVM-run hash seed — so a schema built that
   * way is emitted with its keys shuffled, and shuffled differently on every
   * launch. The model reads these fields in order.
   */

  private static Map<String, Object> recallSchema() {
    Map<String, Object> limit =
        ToolArguments.integer(
            "How many memories to return at most, from 1 to "
                + MAX_LIMIT
                + ". Omit for "
                + DEFAULT_LIMIT
                + ".");

    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("question", ToolArguments.string("What you want to know, in plain language."));
    properties.put("limit", limit);
    return ToolArguments.object(properties, List.of("question"));
  }

  private static Map<String, Object> readSchema() {
    Map<String, Object> ids = new LinkedHashMap<>();
    ids.put("type", "array");
    ids.put("items", ToolArguments.string("A memory id, exactly as memory_recall showed it."));
    ids.put("description", "The memories to read, by id.");

    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("ids", ids);
    return ToolArguments.object(properties, List.of("ids"));
  }

  private static Map<String, Object> writeSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "summary",
        ToolArguments.string("The claim, in one line. What a future reader needs to know."));
    properties.put(
        "scope",
        ToolArguments.string(
            // Not "the project" -- the schema this describes must not carry that
            // word anywhere, since home arrives from the job and a tool must not
            // let a model choose the archive by typing it. See
            // the_write_schema_does_not_let_an_agent_name_its_own_tier.
            "When this applies — the service, the situation, whatever narrows it."));
    properties.put(
        "body", ToolArguments.string("The whole of it: the fact, and the reason it is true."));
    properties.put(
        "formed_by",
        ToolArguments.string("Who formed this. Omit if you have no better answer than yourself."));
    properties.put(
        "formed_where",
        ToolArguments.string("Where it came from — a file, a run, a conversation. Optional."));
    return ToolArguments.object(properties, List.of("summary", "scope", "body"));
  }

  // --- arguments ---------------------------------------------------------------

  // --- rendering ---------------------------------------------------------------

  /**
   * One memory as a shortlist line: what to ask for, what it claims, and when it applies. No body —
   * that is what {@link Read} is for.
   */
  private static String summarise(Memory memory) {
    return head(memory) + oneLine(memory.summary()) + "\nwhen: " + oneLine(memory.scope());
  }

  /**
   * One memory as the model should read it: what it claims, when it applies, where it came from,
   * and — when it is retired — why.
   */
  private static String render(Memory memory) {
    StringBuilder out = new StringBuilder(head(memory));
    out.append(oneLine(memory.summary())).append('\n');
    out.append("when: ").append(oneLine(memory.scope())).append('\n');
    out.append("formed: ")
        .append(memory.formed().at())
        .append(" by ")
        .append(oneLine(memory.formed().by()));
    if (!blank(memory.formed().where())) {
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
      // The reason is the whole point of keeping an invalidated memory: it
      // is what stops the stale fact being rediscovered from old evidence
      // and written straight back in.
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
   * A write's outcome as the model should read it: what shape it took, where it landed, and the
   * scribe's own sentence about why.
   *
   * <p>The reason is flattened for the class's column-zero rule. It is a model's prose, so it may
   * carry line breaks, and a reason reaching column zero would render as a heading this renderer
   * did not write.
   */
  private static String render(WriteResult result) {
    StringBuilder out = new StringBuilder();
    out.append("filed as ").append(result.kind().wireName()).append(": ").append(result.memoryId());
    if (result.targetId() != null) {
      out.append("\ntarget: ").append(result.targetId());
    }
    out.append("\nwhy: ").append(oneLine(result.reason()));
    if (!result.demoted().isEmpty()) {
      out.append("\ndemoted to make room: ").append(String.join(", ", result.demoted()));
    }
    return out.toString();
  }

  /**
   * The id, the state and the tier, on one line. The id first, because it is what the model has to
   * copy into its next call.
   *
   * <p>Every part is flattened, including the id and the state. Neither can carry a line break
   * through any shipped path — the archive mints ids as {@code mem_%06d} and the state is an enum —
   * but this is the line a forged header would have to imitate, and a boundary with one field
   * exempted is a boundary someone later has to reason about field by field. Package-private so the
   * invariant is pinned directly rather than only through the fields a write path happens to allow
   * through.
   */
  static String head(Memory memory) {
    return oneLine(memory.id())
        + "  ["
        + oneLine(memory.state().wireName())
        + "]  "
        + (memory.home().isGlobal()
            ? "everywhere"
            : "project '" + oneLine(memory.home().project()) + "'")
        + '\n';
  }

  /**
   * A value for a single-line slot, with every line break turned into a space.
   *
   * <p>Public, like {@link #quote}, and it used to be package-private: these two are the boundary
   * the class javadoc describes, they are worth pinning directly rather than only through whatever
   * a write path currently allows through, and {@code agents.scribe.Scribe} renders memories into a
   * prompt from another package. It is the one flattening rule the whole system has — a second copy
   * of it in the scribe is exactly the drift {@code ToolArguments} exists to prevent.
   *
   * <p>Not escaping: there is no sequence a value can contain that produces a line break on the far
   * side, so nothing here is forgeable by choosing the right input. The cost is that a genuinely
   * multi-line scope reads as one long line, which is the right trade — a scope is documented as
   * one sentence saying when a memory applies.
   *
   * <p>The TypeScript MCP renderer applies the equivalent display rule at its own output boundary.
   * Server rendering and MCP rendering have independent compatibility tests.
   */
  public static String oneLine(String value) {
    return LINE_BREAK.matcher(value).replaceAll(" ").strip();
  }

  /**
   * A body, quoted line by line, so none of its lines can reach column zero.
   *
   * <p>The whole body is stripped first — leading and trailing blank lines are noise, and a
   * trailing newline would otherwise render an empty {@code "> "} line. {@code Archive} already
   * strips a body on write, so through the archive that call is a no-op; it is here, and pinned by
   * a direct test, because this method is a boundary and a boundary should hold for whatever it is
   * handed rather than for whatever the current caller happens to send. Public for the reason
   * {@link #oneLine} is: the scribe quotes a proposal's body into a prompt from another package. A
   * line already beginning {@code "> "} simply gets another, which is the point: quoting is
   * unconditional, so there is no depth at which content escapes back to the margin.
   */
  public static String quote(String body) {
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
   * The footnote that keeps an empty answer honest, or nothing at all.
   *
   * <p>Zero is the ordinary case and says nothing, which is what makes the sentence worth reading
   * when it appears: a footnote that were always there would tell a model nothing either way.
   */
  private static String skipped(int unsearchable, Home home) {
    if (unsearchable <= 0) {
      return "";
    }
    boolean one = unsearchable == 1;
    return "\n\nIncomplete answer: "
        + unsearchable
        + " memor"
        + (one ? "y" : "ies")
        + " in the "
        + tier(home)
        + " archive "
        + (one ? "has" : "have")
        + " no embedding"
        + " — written while the embedding endpoint was down — so recall could not search "
        + (one ? "it" : "them")
        + " at all, whatever the question. Nothing is lost: "
        + (one ? "it is" : "they are")
        + " still readable by id, and an operator"
        + " re-embedding the archive makes "
        + (one ? "it" : "them")
        + " findable again.";
  }

  /**
   * "global" or "project 'payments'". A sentence saying nothing was found without saying where it
   * looked cannot be acted on.
   */
  private static String tier(Home home) {
    return home.isGlobal() ? "global" : "project '" + home.project() + "'";
  }

  /**
   * Null-safe, and <b>the null half is not reachable through the archive.</b> {@code
   * MemoryProposal} requires {@code formedWhere} to be non-null, so every memory the write path
   * produces carries a non-null {@code where}; {@code Provenance} permits a null all the same, and
   * a memory built by hand would be one field away from an NPE inside a renderer.
   *
   * <p>Package-private for the same reason as {@link #firstLine}: a defensive half no shipped path
   * can reach is pinned by a test rather than left for a green suite to read as coverage over it.
   */
  static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
