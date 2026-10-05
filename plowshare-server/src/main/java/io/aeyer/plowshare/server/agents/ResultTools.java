package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.ToolArguments.BadArguments;
import io.aeyer.plowshare.server.archive.Redemption;
import io.aeyer.plowshare.server.archive.StoredResults;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The two tools a conversation's own stored results are reached through: {@link Read} turns one
 * reference back into the result it stands for, and {@link Listing} names the ones whose references
 * a fold has taken away.
 *
 * <h2>Why there are two of them</h2>
 *
 * <p>A reference is redeemed from the line that carries it, so while the line is in front of the
 * model {@link Read} is the whole mechanism. A fold supersedes the line along with the turn that
 * carried it — the row stays redeemable, and stops being <em>addressable</em> — and {@link Listing}
 * is what gives the address back, in one page rather than in a seam that would have to carry every
 * handle it had ever covered. The seam names it in one sentence whose length does not depend on how
 * much was folded; see {@code Compaction.SEAM_OVER_STORED_RESULTS}.
 *
 * <h2>What {@link Read} is the other half of</h2>
 *
 * <p>{@code Compaction.whatWasSaidAndWhatCameBack} used to drop an earlier turn's tool results
 * outright: the rows stayed in the log with every byte intact, and a model had no way to reach
 * them, so it read the same file again. It now substitutes a <em>reference</em> — the same {@code
 * tool} message, against the same {@code tool_call_id}, its content replaced by a line naming the
 * tool, the size of the stored result and a handle. This is what redeems the handle.
 *
 * <p><b>Without this tool the reference is a cost with no benefit</b>, and the spec says so: a
 * retrieval tool nobody calls is strictly worse than no tool at all, because the context has been
 * spent on references and nothing comes back. The floor is nevertheless safe for an agent that
 * <em>holds</em> the tool — a model that never redeems gets exactly the old behaviour plus one line
 * per call — which is what makes this shippable before it is measured.
 *
 * <p><b>An agent that does not declare {@link #READ_NAME} is not given references at all</b>, and
 * that is the same argument taken to its conclusion: for such an agent the retrieval tool is not
 * merely uncalled but absent, so the lines and the assistant messages carrying them would be spent
 * with nothing able to come back. {@code AgentDefinition.canRedeem} is the condition and {@code
 * Compaction.whatWasSaidAndWhatCameBack} is where it is asked; only {@code interlocutor} declares
 * this today.
 *
 * <h2>The description is the risk, and it is not a thing a test can hold</h2>
 *
 * <p>This repository has a measured scar here. Removing one sentence from the interlocutor prompt —
 * the one saying <em>"file_grep finds the line rather than the file and hands you the offset to
 * read at"</em> — made the agent stop calling {@code file_grep} entirely: sixteen turns, {@code
 * TURN_CAP}, twice. <b>1 892 tests passed either way.</b> So {@link #READ_DESCRIPTION} is written
 * with the same care as the code below it, in the register {@code file_grep}'s own description set,
 * and whether it earns its call is settled by a live run.
 *
 * <h2>Scoped to its conversation, twice</h2>
 *
 * <p>A handle is a random UUID, so a handle for another conversation cannot be <em>constructed</em>
 * — the attack is inexpressible rather than refused. That is not enough on its own: unguessable is
 * not the same as unauthorised, and this project doubles enforcement. This tool holds one run's
 * {@link Transcript}, which is one conversation's, and it has no parameter naming another; {@code
 * EntryStore.redeem} names the conversation again in its own WHERE. Neither copy is redundant,
 * because each is wrong in a different way if the other is removed.
 *
 * <p><b>Both are built per run</b>, for {@code AgentRunTool}'s reason exactly: each carries a thing
 * no instance shared by every job could know. Both are immutable and hold only the transcript, and
 * {@link Listing} is scoped the same way and twice over — which matters more there than here,
 * because it is the one that hands out addresses rather than the one that spends them.
 */
public final class ResultTools {

  /**
   * The name the model calls, the job log records, the reference line names, and {@code
   * AgentRegistry.load} is told about. Spelled once.
   */
  public static final String READ_NAME = "result_read";

  /**
   * The name the seam sends a model to when the reference lines behind it are gone. Spelled once,
   * here, and read by {@code Compaction.SEAM_OVER_STORED_ RESULTS}, {@code JobRuntime.knownTools}
   * and {@code AgentRegistry.load}.
   */
  public static final String LIST_NAME = "result_list";

  /**
   * How an ejection date is written for a model.
   *
   * <p>The calendar day in UTC and no time of day. What a model does with this is tell a person
   * which run to look for in an export; the hour is noise for that, and a locale-dependent spelling
   * would be a model-visible string that changed with the server's — the correction {@code
   * Compaction.REFERENCE} already carries about {@code %,d}. ISO order, so it sorts and reads the
   * same way in every language.
   */
  static final DateTimeFormatter EJECTED_ON =
      DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

  private ResultTools() {}

  /**
   * {@code result_read} — one stored tool result, exactly as the tool returned it.
   *
   * <h2>Verbatim, and nothing added</h2>
   *
   * <p>The answer is the stored content and no prose around it. Two reasons, and the second is the
   * load-bearing one. A model that redeems a file read wants the file, not the file with a harness
   * sentence stapled to the top; and the reference line promises the result is <em>complete and
   * unchanged</em>, so an answer that decorated it would make that promise false at the one moment
   * a model could check it.
   *
   * <p><b>An ejected payload is the one answer that is not the content, and it does not weaken that
   * rule.</b> Nothing is added to a result that is here; what {@link #wasEjected} returns is the
   * harness speaking in place of a result that is not, and it says so in the sentence. The rule the
   * retention design puts on it is that it must not be {@link #notHere}: a payload that was here
   * and went is a different fact from a handle that never resolved, and a model that reads the
   * second for the first stops looking.
   *
   * <p><b>There is no size cap, and that is an argument rather than an omission.</b> Every stored
   * result was in a prompt once — the turn that produced it sent it to the model — so a redemption
   * asks for something this conversation has already been shown to fit. A second cap here would
   * refuse on a bound the original call did not have, and would do it to a model that asked for
   * exactly one specific thing.
   *
   * <h2>Every mistake is a result</h2>
   *
   * <p>Unparseable arguments, a missing handle, something that is not a UUID, and a UUID this
   * conversation does not hold all come back as prose the model can act on. See {@link AgentTool}:
   * a caller's mistake is never an exception, and the turn that produced it was already paid for.
   * What is <b>not</b> softened is a null argument, which is the runtime's bug.
   *
   * <p><b>A handle that does not resolve and a handle from another conversation get the same
   * sentence, deliberately.</b> Distinguishing them would tell a model whether a handle exists
   * somewhere it may not read, which is the one thing an authorisation check must not leak; {@code
   * EntryStore.redeem} answers empty for both and this cannot tell them apart even if it wanted to.
   */
  public static final class Read implements AgentTool {

    private final Transcript transcript;
    private final ToolSchema schema;

    public Read(Transcript transcript) {
      this.transcript = Objects.requireNonNull(transcript, "transcript");
      this.schema = ToolSchema.from(READ_NAME, READ_DESCRIPTION, handleSchema());
    }

    @Override
    public ToolSchema schema() {
      return schema;
    }

    @Override
    public String run(String argumentsJson, Home home) {
      // Outside the try: a null here is the runtime's bug and not the
      // model's, and the never-throw rule is a rule about a caller's
      // mistakes. See AgentTool.
      Objects.requireNonNull(argumentsJson, "argumentsJson");
      Objects.requireNonNull(home, "home");
      try {
        return answer(argumentsJson);
      } catch (BadArguments unusable) {
        return unusable.getMessage();
      }
    }

    /**
     * {@code home} is not read and must not be.
     *
     * <p>Every other tool in this package routes on the tier its job was started for. This one
     * addresses a row in the conversation the run is already inside, and the transcript is what
     * says which conversation that is. A home filter here would be a second scope over the same
     * question with no way to be right about both — a conversation belongs to one home, so the
     * filter would either never fire or would refuse a model its own history.
     */
    private String answer(String argumentsJson) {
      JsonNode args =
          ToolArguments.parse(
              argumentsJson, READ_NAME, "{\"handle\": \"6b1f0c2a-9d4e-4f18-8a71-2c5e0b7d3f96\"}");
      String sent =
          ToolArguments.requireText(
              args, "handle", READ_NAME, "the handle from the stored-result line you want back");
      UUID handle = parsed(sent);
      Optional<Redemption> stored = transcript.redeem(handle);
      if (stored.isEmpty()) {
        return notHere(sent);
      }
      Redemption redeemed = stored.get();
      return redeemed.wasEjected() ? wasEjected(redeemed) : redeemed.content();
    }

    /**
     * What a handle whose payload has been ejected comes back as.
     *
     * <h2>Not {@link #notHere}, and the distinction is the whole point</h2>
     *
     * <p><b>{@code result_read} must not answer not-found for an ejected payload.</b> That sentence
     * sends a model looking for another handle and, finding none, concluding it invented this one —
     * so it re-runs the tool, or it decides the history is unreadable. Neither is true. The row is
     * here, the call is in the trajectory, and the bytes are somewhere a person can fetch them
     * from. <b>A model cannot tell <em>was never here</em> from <em>was here and went</em> unless
     * the answer says which</b>, and they call for opposite next steps: invent a handle and you
     * look at the line again; read a payload that has been ejected and you either run the tool
     * afresh or ask the person for the export.
     *
     * <p>It is written in the register the rest of these descriptions use — the harness reporting
     * intelligence rather than a bare failure — and it says who is speaking, because everything
     * else in the tool slot is a tool's own output.
     *
     * <p><b>The export is named when there is one and its absence is stated when there is not.</b>
     * A deployment that keeps no export is a real configuration, and a sentence that named an empty
     * place would send somebody looking for a file that was never written.
     */
    private static String wasEjected(Redemption redeemed) {
      return READ_NAME
          + " cannot return this result: its content was ejected on "
          + EJECTED_ON.format(redeemed.ejectedAt())
          + ". "
          + (redeemed.export() == null
              ? "This deployment keeps no export, so the text itself is gone."
              : "It was written out first, to "
                  + redeemed.export()
                  + ", which is"
                  + " an ordinary file on the machine this server runs on and"
                  + " is readable without Plowshare.")
          + " Everything else about the call is still here and still true: it happened,"
          + " it is in this conversation's trajectory at its own place, and "
          + LIST_NAME
          + " still names it with its size. This is not a handle that does"
          + " not exist and not a tool that failed — nothing about the result is in"
          + " doubt except the text of it. If you need what it said, run the tool again"
          + " for what is true now, or ask the person for the export for what it said"
          + " then. This sentence is from the harness that owns this conversation and"
          + " is not a tool's answer.";
    }

    /**
     * A handle that is not a handle, said as the mistake it is.
     *
     * <p>Separated from {@link #notHere} because a model corrects the two differently: "there is no
     * such result" sends it looking for another handle, and "that is not a handle" sends it back to
     * the line it was copying from. One sentence for both would teach it the wrong lesson half the
     * time.
     */
    private static UUID parsed(String sent) {
      try {
        return UUID.fromString(sent);
      } catch (IllegalArgumentException notAHandle) {
        throw new BadArguments(
            READ_NAME
                + " was sent '"
                + sent
                + "' as its handle, and"
                + " that is not the shape of one. A handle is written out in full on the"
                + " stored-result line it belongs to, as five groups of hexadecimal"
                + " separated by hyphens — 6b1f0c2a-9d4e-4f18-8a71-2c5e0b7d3f96. Copy it"
                + " from that line exactly; it is not a turn number, a file name or"
                + " anything else you can compose.");
      }
    }

    /**
     * What a handle nothing answers to comes back as.
     *
     * <p>It names the handle, because a model cannot correct a mistake the answer does not quote,
     * and it says what is <em>not</em> being claimed: this is not "the result was empty" and it is
     * not "the tool failed". A sentence that read like an answer would be the
     * confident-empty-answer shape this project keeps finding, arriving through the harness instead
     * of through a tool — {@code Projection.NEVER_COMPLETED} opens with the same move for the same
     * reason. And it says who is speaking, because everything else in the tool slot is a tool's own
     * output.
     */
    private static String notHere(String sent) {
      return READ_NAME
          + " has no stored result under the handle '"
          + sent
          + "' in this"
          + " conversation. That is not a result that came back empty and not a tool"
          + " that failed: nothing is stored at that address here, so nothing can be"
          + " concluded from this. Handles come from the stored-result lines in this"
          + " conversation's own history and work nowhere else — copy one from there"
          + " rather than composing one. If the result you want has no such line,"
          + " run the tool again instead. This sentence is from the harness that owns"
          + " this conversation and is not a tool's answer.";
    }
  }

  /**
   * {@code result_list} — the stored results a fold took the reference lines away from.
   *
   * <h2>The defect it closes, which is the one {@link Read} could not</h2>
   *
   * <p>A handle survives the fold that hid it: {@code EntryStore.redeem} does not filter {@code
   * superseded_by}, and {@code CompactionTest.a_result_a_fold_covered_is_still_redeemable} proves
   * the row still answers. What does <b>not</b> survive is the reference line, because a fold
   * supersedes it along with the turn that carried it. So a result behind a seam stayed redeemable
   * and stopped being <em>addressable</em>: nothing in the prompt told the model the handle, and
   * past a seam the model was exactly where it had been before any of this was built. This is where
   * the address comes back from.
   *
   * <h2>Why the seam does not simply list the handles</h2>
   *
   * <p>Because that list would grow without bound. A second fold would have to carry the first
   * fold's handles or lose them, so every fold would inherit every handle before it — and the
   * accumulating list would partly undo the shrinking a fold exists to do. {@link
   * Compaction#SEAM_OVER_STORED_RESULTS} carries <b>one sentence</b> instead, the same length
   * whether the span held two results or two hundred, and it names this tool. The cost of the
   * addresses is then paid by a model that has decided it wants them, on a turn it chose to spend,
   * and never by one that has not.
   *
   * <h2>What it lists, and what it deliberately does not</h2>
   *
   * <p><b>Only what is behind a seam.</b> A result no fold has covered is in front of the model
   * already, as its own reference line carrying these same three facts, so listing it would spend a
   * bounded page on addresses the model is holding anyway — and, since the list is newest first, it
   * would spend the <em>front</em> of the page on them and push out the ones only this tool can
   * reach.
   *
   * <p><b>Newest first</b>, because the seam a model has just read is the nearest one and what is
   * behind it is what the conversation was doing a moment ago. It is also the ordering that makes
   * the bound cheap to defend: what falls off the end of the first page is the oldest work, which
   * is the least likely to be what the current question needs.
   *
   * <p><b>Bounded, and the shape is {@code file_read}'s.</b> {@link #MOST_LISTED} is this tool's
   * own cap, the answer names both numbers — how many are shown and how many there are — and it
   * spells the next call. A short list with no total cannot be told apart from the end of the list,
   * and only one of those readings is true.
   */
  public static final class Listing implements AgentTool {

    /**
     * The most stored results one answer names, whatever is asked for.
     *
     * <p><b>A bound on what a model can choose between in one turn</b>, which is {@code
     * FileTools.MAX_READ_LINES}' kind of number and not a transport's. A conversation folded a
     * dozen times can hold hundreds of stored results, and an unbounded listing would spend on
     * addresses the context the fold was taken to recover.
     *
     * <p><b>It is a judgement and not a measurement, and saying so is better than a test that
     * appears to pin it.</b> {@code MAX_READ_LINES} is 300 because two live reads on one box
     * answered at 400 lines and did not answer at 1 600; nothing equivalent has been run for this.
     * What the number is reasoned from is what the answer is <em>for</em>: it exists so a model can
     * pick one result, and a model picking from more than a few dozen is not picking. Twenty rows
     * is roughly 1 800 characters — about 1% of the history a 128 000-token conversation folds at —
     * and the rest of the list is one more call away rather than gone.
     *
     * <p><b>Nothing here is derived from {@code FileTools}' constants.</b> Those answer to a file's
     * readable length and a socket's buffer; this answers to how many things a model can weigh at
     * once, and a value that moved for one would move silently for the others.
     */
    static final int MOST_LISTED = 20;

    /**
     * How much of a tool's name one row shows.
     *
     * <p><b>The name is model-supplied text and this is the only field on a row that is not this
     * server's to size.</b> A call to a tool that does not exist is still answered and still
     * recorded — {@code JobRuntime.noSuchTool} — so the name on a stored result is whatever the
     * model emitted: unbounded, and free to contain line breaks. Everything else on a row is a
     * bounded integer and a UUID, so with this cap the whole answer's length follows from {@link
     * #MOST_LISTED} and there is no second display bound to argue about. That is where this parts
     * company with {@code FileTools.MAX_DISPLAY_CHARS}, which exists because a provider on the
     * other side of a wire can return more than it was asked for.
     *
     * <p>Every tool name this server binds is far under it, so the cap fires only on a name a model
     * invented. The flattening is {@code MemoryTools.oneLine} and is the same defence {@code
     * noSuchTool} applies, for the reason it gives: an invented name must not turn one line into
     * twenty.
     */
    static final int NAME_SHOWN = 60;

    private final Transcript transcript;
    private final ToolSchema schema;

    public Listing(Transcript transcript) {
      this.transcript = Objects.requireNonNull(transcript, "transcript");
      this.schema = ToolSchema.from(LIST_NAME, LIST_DESCRIPTION, offsetSchema());
    }

    @Override
    public ToolSchema schema() {
      return schema;
    }

    @Override
    public String run(String argumentsJson, Home home) {
      // Outside the try, and `home` is not read at all: {@link Read} owns
      // both arguments and neither is different here.
      Objects.requireNonNull(argumentsJson, "argumentsJson");
      Objects.requireNonNull(home, "home");
      try {
        return answer(argumentsJson);
      } catch (BadArguments unusable) {
        return unusable.getMessage();
      }
    }

    private String answer(String argumentsJson) {
      JsonNode args = ToolArguments.parse(argumentsJson, LIST_NAME, "{\"offset\": 0}");
      int offset = offset(args);
      StoredResults stored = transcript.stored(offset, MOST_LISTED);
      if (stored.total() == 0) {
        return NOTHING_IS_BEHIND_A_SEAM;
      }
      if (stored.listed().isEmpty()) {
        return pastTheEnd(offset, stored.total());
      }
      return rendered(stored, offset);
    }

    /**
     * The listing itself: the range, what each line is, and the rows.
     *
     * <p>The range and the total go <b>first</b>, {@code file_read}'s continuation note's position
     * and for a weaker version of its reason — nothing cuts a tail here, but the number that
     * changes how the rest is read belongs before the rest. The next call goes last, where a model
     * that has read the rows and wants more will look for it.
     */
    private static String rendered(StoredResults stored, int offset) {
      int last = offset + stored.listed().size() - 1;
      StringBuilder out = new StringBuilder();
      out.append("Stored results ")
          .append(offset)
          .append(" to ")
          .append(last)
          .append(" of ")
          .append(stored.total())
          .append(", counting from 0 as offset does. These are the results of turns")
          .append(" of this conversation that have been summarised away, most recent")
          .append(" first. The results themselves were not summarised and nothing")
          .append(" was trimmed: read any of them back in full with ")
          .append(READ_NAME)
          .append(", using the handle on its line.\n");
      for (StoredResults.Result result : stored.listed()) {
        out.append('\n').append(row(result));
      }
      if (last < stored.total() - 1) {
        out.append("\n\nThere is more of this list: call ")
            .append(LIST_NAME)
            .append(" again with offset=")
            .append(last + 1)
            .append('.');
      }
      return out.toString();
    }

    /**
     * One stored result on one line: the tool, the size, the handle.
     *
     * <p>The same three facts {@code Compaction.REFERENCE} carries and in the same order, so a
     * model that has read a reference line already knows how to read this. The tool first, because
     * it is what the choosing is done on; the handle last, because it is what gets copied into the
     * next call.
     *
     * <p><b>{@code %d} and not {@code %,d}</b>, which is the correction the reference line already
     * carries: {@code String.format} groups against the default locale, so a grouped number is a
     * model-visible string that changes with the server's, and the two lines describe the same
     * result to the same model.
     */
    private static String row(StoredResults.Result result) {
      return named(result.tool())
          + " — "
          + result.size()
          + " characters — handle "
          + result.handle()
          // Listed AS ejected rather than omitted, which the retention
          // design settles: a model that cannot tell "was never here"
          // from "was here and went" re-runs the tool on the first
          // reading and gives up on the second, and only one of those
          // is right. The size is still on the row -- ejected_chars
          // keeps it -- so what changed is one clause and not the
          // shape of the line.
          + (result.ejectedAt() == null
              ? ""
              : " — content ejected on "
                  + EJECTED_ON.format(result.ejectedAt())
                  + ", so "
                  + READ_NAME
                  + " will report where it went rather"
                  + " than return it");
    }

    /**
     * A tool's name as a row may show it: flattened, shortened, and said plainly when there is
     * none.
     *
     * <p>A missing name is a result whose call nothing in the log declares. No run this server
     * writes produces one — the assistant message is recorded before the results it asked for — and
     * the row is kept anyway, because the address is the thing that cannot be recovered any other
     * way. What it must not do is arrive as the word {@code null}, which reads as a tool by that
     * name.
     */
    private static String named(String tool) {
      if (tool == null || tool.isBlank()) {
        return "(the tool that ran is not recorded)";
      }
      String flattened = MemoryTools.oneLine(tool);
      return flattened.length() <= NAME_SHOWN
          ? flattened
          : flattened.substring(0, NAME_SHOWN) + "…";
    }

    /**
     * What a conversation with nothing behind a seam is told.
     *
     * <p><b>Not "there are no results".</b> That would be false and it is the
     * confident-empty-answer shape this project keeps refusing: the conversation may have run fifty
     * tools, and every one of their results is in front of the model already with its own handle on
     * its own line. What is empty is the space behind the seams, because there are none yet. It
     * names the state rather than the count, so a model reads it as "you already have everything"
     * instead of "your history was lost".
     *
     * <p>It is also the honest answer for a run in no conversation at all — {@code Transcript.NONE}
     * — where there is nothing behind a seam for the stronger reason that there are no earlier
     * turns.
     */
    private static final String NOTHING_IS_BEHIND_A_SEAM =
        LIST_NAME
            + " has nothing to list: no turn of this conversation has been summarised away,"
            + " so nothing has had its stored-result line taken from it. Every result an"
            + " earlier turn got is still in front of you where it happened, each one a line"
            + " naming the tool, the size and the handle — and "
            + READ_NAME
            + " reads any"
            + " of those back in full.";

    /**
     * A page past the end of the list, said as the end of the list rather than as an empty one.
     *
     * <p>{@code file_read} splits the same two answers — a file with nothing in it and a caller
     * that has paged one window too far — because a model that reads the second as the first stops
     * looking. It names the offset of the last row, so the correction is arithmetic the model does
     * not have to guess at, and it names the call that starts again.
     */
    private static String pastTheEnd(int offset, int total) {
      return "This conversation holds "
          + total
          + " stored result"
          + (total == 1 ? "" : "s")
          + " behind its summaries, so there is nothing at"
          + " offset "
          + offset
          + " — the last one is at offset "
          + (total - 1)
          + ". This is the end of the list and not a failure; call "
          + LIST_NAME
          + " again with offset=0 for the most recent.";
    }

    /**
     * The {@code offset} argument, defaulted and refused when it is not a number this tool can act
     * on.
     *
     * <p>{@code file_read}'s wording and its floor, in the unit this list is counted in: there is
     * nothing before the most recent stored result, and an omitted offset is a model asking for the
     * top of the list.
     */
    private static int offset(JsonNode args) {
      int offset = ToolArguments.optionalInt(args, "offset", 0, Listing::badOffset);
      if (offset < 0) {
        throw new BadArguments(
            LIST_NAME
                + " was given an 'offset' of "
                + offset
                + ". It is a position in this list, the most recent stored result is at"
                + " 0, and there is nothing before that. Leave it out for the most"
                + " recent.");
      }
      return offset;
    }

    private static BadArguments badOffset(JsonNode offset) {
      return new BadArguments(
          LIST_NAME
              + " could not read 'offset': it must be a whole"
              + " number, the position in the list to start at, not "
              + offset
              + ". Leave"
              + " it out for the most recent "
              + MOST_LISTED
              + ".");
    }
  }

  // --- what the model is told this tool does -----------------------------------

  /*
   * Written to the rule the file and memory tools already keep: say what the
   * tool costs, and never describe a capability this surface does not have.
   *
   * WHAT IT HAS TO DO THAT THEIRS DO NOT. Every other tool's description
   * answers "when would I want this?". This one has to answer that AND teach a
   * mechanism the model has never met: that a line it is reading in the tool
   * slot is a pointer, that the thing it points at is intact, and that one call
   * gets it. A model that does not know a reference is redeemable will read one
   * as "that result is gone" and re-run the tool, which is the outcome this
   * change exists to stop and is indistinguishable from the change never
   * having been made.
   *
   * SO THE FIRST SENTENCE NAMES THE LINE. file_grep's load-bearing sentence
   * -- "finds the line rather than the file and hands you the offset to read
   * at" -- works because it says what the tool GIVES YOU that the obvious
   * alternative does not, in the units of the next thing you will do.
   * Removing it stopped an agent calling the tool at all. The equivalent here
   * is that the stored-result line and the handle are the same object read
   * from either end: the line tells you what the result was, and the handle
   * turns it back into the result.
   *
   * WHAT IT DELIBERATELY DOES NOT PROMISE. An earlier draft said the stored
   * result included "the part behind a summary of turns you can no longer
   * see", which is true of the LOG and false of what the model can act on: a
   * fold supersedes the reference line along with the turn that carried it, so
   * after a fold there is no handle in the prompt for what is behind the seam.
   * The row is still redeemable and nothing tells the model its address. What
   * the sentence says instead is the part that is both true and usable -- a
   * handle goes on working after the turns around it are summarised -- and the
   * gap is recorded here rather than papered over: making the spec's claim
   * hold would mean carrying handles across a seam, which is a change to what a
   * fold writes and is not this one.
   *
   * AND IT NAMES THE CHEAPER ORDER, which is the second thing file_grep's does.
   * Re-running a tool costs a call and does the work twice; redeeming costs a
   * call and cannot. The third clause is the one only this tool can say: a
   * re-read answers "what does the file say now", and a redemption answers
   * "what did I see" -- and when the agent has been editing, those are
   * different questions and only one of them is the one being asked.
   */
  static final String READ_DESCRIPTION =
      """
            Read back a tool result from earlier in this conversation, in full \
            and exactly as the tool returned it. Fast and free — no model runs.

            Results from earlier turns are not repeated in this conversation. \
            Each one is left in its place as a short line saying which tool ran, \
            how large its result was, and a handle. That line and the result are \
            the same thing from two ends: the line tells you what you got, and \
            the handle turns it back into what it said. Nothing was trimmed and \
            nothing was summarised, and a handle goes on working after the turns \
            around it have been summarised away.

            Redeeming is the cheaper order once a result already exists. Running \
            the tool again costs the same call and does the work a second time; \
            and it answers a different question — what the file says now, rather \
            than what you read. If you have since written to it, those are not \
            the same answer.

            Send one handle, copied from a stored-result line in this \
            conversation. Handles cannot be worked out or guessed at, and one \
            from anywhere else will not resolve; if there is no line for what \
            you want, run the tool again instead.""";

  /*
   * WHAT THIS ONE HAS TO TEACH THAT result_read's DOES NOT. That description
   * explains a line the model is looking at. This one explains a line the
   * model CANNOT see: the reference went away with the turn that carried it,
   * so there is nothing on the screen to point at and the first sentence has
   * to supply the missing object itself -- the results are still there, they
   * are simply no longer named in front of you.
   *
   * SO IT NAMES THE SEAM. The one place a model meets this situation is
   * immediately after reading Compaction.SEAM_OVER_STORED_RESULTS, which says
   * a span was summarised and that this tool lists what those turns' tools
   * returned. Both texts therefore say "summarised away" in the same words, on
   * purpose: the seam is the cue and this is the follow-through, and a model
   * matching one to the other is the whole path from a fold to a redemption.
   *
   * AND IT SAYS WHAT IS NOT IN IT, which is the half a listing can get wrong
   * silently. A model that expected this to name everything the conversation
   * has ever run would read a short list as a lost history. What is missing
   * from it is missing because it is still in front of the model, which is a
   * better fact than a longer list.
   *
   * THE BOUND IS IN THE SENTENCE and not only in the answer, for
   * file_read's reason: a model that knows the cap before it calls can plan a
   * second call instead of concluding the list ended.
   */
  static final String LIST_DESCRIPTION =
      """
            List the tool results this conversation has stored from turns that \
            have been summarised away. Fast and free — no model runs.

            When a span of turns is summarised, the stored-result lines in those \
            turns go with them, so the results those calls returned are no \
            longer named anywhere in front of you. Nothing was trimmed and \
            nothing was discarded: this names each one — which tool ran, how \
            large its result was, and its handle — and %s turns any of those \
            handles back into exactly what the tool returned then.

            Most recent first, %d at a time, and the answer says how many there \
            are in all; send 'offset' to see the ones after that. Results from \
            turns you can still see are not listed here, because each of those \
            already carries its own line where it happened.

            Reach for it when a summary names work whose detail you now need. \
            Running the tool again costs the same call and does the work a \
            second time, and it answers a different question — what the file \
            says now, rather than what you were shown then."""
          .formatted(READ_NAME, Listing.MOST_LISTED);

  // --- the schema --------------------------------------------------------------

  private static Map<String, Object> offsetSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "offset",
        ToolArguments.integer(
            "Where in the list to start, counting from 0 at the most recent stored result."
                + " Omit for the most recent "
                + Listing.MOST_LISTED
                + "; send the"
                + " number the previous answer named to see the rest."));
    return ToolArguments.object(properties, List.of());
  }

  private static Map<String, Object> handleSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "handle",
        ToolArguments.string(
            "The handle from the stored-result line you want back, copied exactly as it"
                + " is written there."));
    return ToolArguments.object(properties, List.of("handle"));
  }
}
