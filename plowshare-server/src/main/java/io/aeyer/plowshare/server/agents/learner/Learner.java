package io.aeyer.plowshare.server.agents.learner;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.MemoryProposal;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.EntryKind;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.Learning;
import io.aeyer.plowshare.server.agents.LogGrowth;
import io.aeyer.plowshare.server.agents.LoggedEntry;
import io.aeyer.plowshare.server.agents.MemoryTools;
import io.aeyer.plowshare.server.agents.ModelJson;
import io.aeyer.plowshare.server.agents.scribe.Scribe;
import io.aeyer.plowshare.server.archive.Archive;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryRecord;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.Validation;
import io.aeyer.plowshare.server.llm.accounting.*;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmException;
import io.aeyer.plowshare.server.llm.dispatch.LlmSaturatedException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads what a fold took out of a model's view and asks whether anything in it was worth keeping.
 *
 * <h2>The candidate generator, and that is all it is</h2>
 *
 * <p>Everything downstream of "here is something worth remembering" already exists: {@code Scribe}
 * judges the <em>shape</em> of a write — new claim, refinement of one held, or replacement — and
 * {@code Archive.applyVerdict} files it. What did not exist is something that reads a span of
 * conversation and answers "is there anything here at all?", usually with no. This is that and
 * nothing more, which is why it is a class beside {@code Scribe} rather than a new pipeline.
 *
 * <h2>It proposes; it does not write</h2>
 *
 * <p>{@code memory_write} is bound for agents now, and this agent may not hold it: {@code learner}
 * is one of the three {@code AgentRegistry.THE_MEMORY_PIPELINE} names, and {@code
 * AgentRegistry.mayNotAuthor} is the refusal an agent that judges or files what everyone else
 * proposes gets. What changed is the reason, not the shape of the work: this class still never asks
 * for the tool. It builds a {@link MemoryProposal} directly and goes through exactly the pair
 * {@code MemoryController.write} uses for a person's own write: {@link Scribe#judge} and then
 * {@link Archive#applyVerdict}. The learner agent never sees a tool.
 *
 * <p><b>The design named the wrong door and the correction is worth recording.</b> §3 of {@code
 * 2026-09-03-log-scanning-learner-design.md} says the learner "calls {@code
 * ProposalStore.propose(memoryId, action, reason, by)}, and the existing pipeline takes over:
 * {@code scribe} judges the shape of each proposal … {@code promotion_judge} rules, {@code
 * PromotionQueue} carries it." Those are two different pipelines and only one of them can be
 * entered from here. {@code ProposalStore.propose} files a question <em>about a memory that already
 * exists</em> — its {@code memory_id} is a foreign key into {@code memories}, and the question it
 * asks is whether that memory should be promoted to the global tier, which {@code promotion_judge}
 * rules on. A learner has no memory id to name: it is proposing that a memory should exist at all.
 * That is the write path, it is judged by the scribe, and it is what this class calls.
 *
 * <h2>It is infrastructure</h2>
 *
 * <p>The owner's rule: "budget and things shouldn't be an agent thing — that's the system, anything
 * 'infra' … should not be in that context going to the LLM". So:
 *
 * <ul>
 *   <li><b>Its own budget, not the conversation's.</b> It spends no {@code Budget} at all — the
 *       same correction the fold already took, for the same reason: a conversation whose allowance
 *       is nearly gone must not stop being mined, and a person must not pay out of their own
 *       allowance for a pass they did not ask for and are not told about. What it does hold is
 *       {@link #BUDGET}, which bounds only how long it waits to <em>start</em>.
 *   <li><b>Its runs are {@code diagnostic} entries.</b> Those carry role NULL and are refused a
 *       role by {@code entries_role_matches_kind}, so they cannot reach a model by construction. A
 *       conversation is never told a learner ran over it.
 * </ul>
 *
 * <h2>Failure is silent and self-healing</h2>
 *
 * <p>A pass that fails logs, writes its diagnostic, advances no {@code learned_at}, and the next
 * window is simply wider. Nothing retries, nothing backs off, no window is split. The cost of a
 * failed pass is one model call and a span that stays in the queue — which is exactly where it was.
 *
 * <p><b>Never throws</b>, for {@code Scribe}'s reason one door over and one more: its caller is a
 * fold, and a fold's caller is a turn that has already ended. See {@link Learning}.
 */
public final class Learner implements Learning, UsageAware {
  private UsageOwners usageOwners = UsageOwners.NONE;

  @Override
  public void useUsageOwners(UsageOwners source) {
    usageOwners = java.util.Objects.requireNonNull(source);
  }

  private io.aeyer.plowshare.server.archive.DigestStore digestProvenance;

  public Learner withProvenance(io.aeyer.plowshare.server.archive.DigestStore store) {
    digestProvenance = store;
    return this;
  }

  private static final Logger log = LoggerFactory.getLogger(Learner.class);

  /** The agent this looks for in the registry, and the stem of its file. */
  public static final String AGENT = "learner";

  /**
   * Who a memory this pass produced is credited to, on the proposal and in the archive. Not a
   * person and not the conversation's own agent: it is this pass, and a memory nobody in that
   * conversation asked for.
   */
  public static final String BY = "learner";

  /**
   * How long a pass is willing to wait to <em>start</em>.
   *
   * <p><b>Short, and short for the opposite reason to {@code Scribe.BUDGET}.</b> The scribe waits
   * five seconds because somebody is waiting on it and the cost of not judging is a flat filing.
   * Nothing waits on this. It is short because a busy pool is a pool serving people's turns, and
   * infrastructure that queued behind them would be taking a lane from a person to mine a
   * conversation that has already ended. Giving up costs nothing here: the window was never marked,
   * so the next pass covers the same span and more.
   *
   * <p>It bounds queueing only; the model's own generation is bounded by the pool's {@code
   * chat-timeout}, exactly as the scribe's is.
   */
  static final Duration BUDGET = Duration.ofSeconds(5);

  /**
   * The most memories one window may produce.
   *
   * <p><b>A bound on a model that has mistaken the log for a to-do list.</b> The instruction says
   * the ordinary answer is none, and the failure mode of a small model asked "is anything here
   * worth keeping" is a list of everything that happened — which would land in the archive as a
   * dozen shallow rows that every future recall then pays attention to, for ever. Three is not a
   * measurement: it is the number past which an answer has stopped being a judgement and become a
   * summary, and a window that produces more is reported as truncated rather than filed whole.
   */
  static final int MOST_PROPOSED = 3;

  /** What each entry of the window is labelled with in the prompt. */
  private static final String LINE = "[%d | turn %d | %s] %s";

  /**
   * The one sentence the window is introduced by.
   *
   * <p><b>"Nobody will come back to" and no longer "summarised away", which is a correction and not
   * a rewording.</b> A window is no longer always folded material: {@code EntryStore.ONLY_FOLDED}
   * is asked only of a tree somebody can still speak into, so the rows of an archived run that
   * never folded arrive here having been summarised away by nothing. The old sentence was a false
   * premise told to a model about the very material in front of it, and the true one is the premise
   * the widening is argued from — {@code Turn.speak} refuses a turn into this tree, so there is no
   * later moment.
   *
   * <p>Changed to the clause and no further. This repository has measured that prompt text moves
   * behaviour invisibly and on things as small as paragraph order ({@code implementation
   * rationale}), so the shape, the length and the position of every other word are what they were.
   * The same two clauses are corrected in {@code learner.md} and in {@link #formedWhere}, which
   * said the same thing in the same words.
   */
  private static final String THE_SPAN =
      "Part of a conversation in %s that nobody will come back to."
          + " %d of the %d entries waiting are here, oldest first.";

  /** What a pass writes into the conversation it read. */
  static final String MINED =
      "A learning pass read %d of the %d entries awaiting it, through turn %d, and"
          + " proposed %d %s.";

  /** And what a pass that could not be taken writes instead. */
  static final String NOT_MINED =
      "A learning pass over %d of the %d entries awaiting it could not be taken, so"
          + " nothing was marked and the next window covers them again. Reason: %s";

  private final LlmDispatcher dispatcher;
  private final EntryStore entries;
  private final ConversationStore conversations;
  private final Archive archive;
  private final Scribe scribe;
  private final Supplier<AgentRegistry> agents;
  private final Supplier<Instant> now;

  /**
   * Whether a pass is in flight, for {@code Compaction.folding}'s reason: a second pass would
   * compute a window overlapping the first's and pay a model call to propose the same thing twice.
   * A dropped pass costs nothing — the queue only grows.
   *
   * <p>One flag for the server and not one per conversation, which is where it differs from the
   * fold: a fold is about one conversation and a pass is about whichever conversation the provider
   * chose, so two passes are two passes at the same shortlist.
   */
  private final AtomicBoolean passing = new AtomicBoolean();

  /**
   * Told when a pass's note lands in a conversation's log. See {@link LogGrowth}; set after
   * construction for {@code Compaction.growth}'s reason, and telling nobody until it is.
   */
  private volatile LogGrowth growth = LogGrowth.NONE;

  public Learner(
      LlmDispatcher dispatcher,
      EntryStore entries,
      ConversationStore conversations,
      Archive archive,
      Scribe scribe,
      Supplier<AgentRegistry> agents) {
    this(dispatcher, entries, conversations, archive, scribe, agents, Instant::now);
  }

  /**
   * The same, with the clock supplied.
   *
   * <p>Injected for {@code EntryStore}'s reason: a test asserting when a row was marked needs a
   * stamp somebody chose.
   */
  public Learner(
      LlmDispatcher dispatcher,
      EntryStore entries,
      ConversationStore conversations,
      Archive archive,
      Scribe scribe,
      Supplier<AgentRegistry> agents,
      Supplier<Instant> now) {
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    this.entries = Objects.requireNonNull(entries, "entries");
    this.conversations = Objects.requireNonNull(conversations, "conversations");
    this.archive = Objects.requireNonNull(archive, "archive");
    this.scribe = Objects.requireNonNull(scribe, "scribe");
    this.agents = Objects.requireNonNull(agents, "agents");
    this.now = Objects.requireNonNull(now, "now");
  }

  /**
   * Tells {@code growth} when a pass's note lands in a conversation's log.
   *
   * <p>The note is written into whichever conversation the pass chose, which is not necessarily the
   * one whose fold triggered it, so the fold's own telling does not cover it.
   */
  public void useGrowth(LogGrowth growth) {
    this.growth = Objects.requireNonNull(growth, "growth");
  }

  /**
   * {@inheritDoc}
   *
   * <p>Runs the whole pass on the calling thread, which for the fold trigger is the fold's own —
   * see {@link Learning}, where the ordering is the rule: the fold commits first and this runs
   * after it.
   */
  @Override
  public void thereIsMaterial() {
    if (!passing.compareAndSet(false, true)) {
      log.debug(
          "a learning pass is already in flight, so the one this trigger would have"
              + " started was dropped. The next window covers the same material and"
              + " more.");
      return;
    }
    try {
      pass();
    } catch (RuntimeException broken) {
      // Wide, and wide on purpose: the caller is a fold, whose own caller
      // is a turn that has ended, so there is nobody left for this to be
      // an error to. Everything a model or an endpoint can do is handled
      // below and writes a diagnostic; what reaches here is this server
      // being wrong.
      log.warn(
          "a learning pass could not be taken at all. Nothing was marked, so the"
              + " next window covers the same material. Reason: {}",
          describe(broken));
    } finally {
      passing.set(false);
    }
  }

  /**
   * One pass: take the window the system computes, ask once, file what comes back, mark what was
   * given.
   *
   * <h2>The order, which is the whole of the failure story</h2>
   *
   * <p>The marking is <b>last</b>, and only after a model has actually answered about this window.
   * A pass that could not reach a model marks nothing, so the span is still in the queue and the
   * next window is wider — that is the self-healing, and it is the reason the mark is not taken at
   * the moment the window is computed.
   *
   * <p><b>A proposal that could not be filed does not hold the mark back</b>, which is the one
   * asymmetry here and is deliberate. The window's claim is that the learner was shown these rows,
   * and it was. If the archive is refusing writes then the scribe and the embedding endpoint are
   * almost certainly down with it, so holding the span back would buy a model call per fold, for
   * ever, against a failure nothing here can fix — and this design says in as many words that
   * nothing retries and nothing backs off. It is counted and named in the diagnostic instead.
   */
  private void pass() {
    AgentRegistry registry = agents.get();
    if (registry == null || !registry.names().contains(AGENT)) {
      // Not a warning per fold. A server with no agent directory, or one
      // whose directory has no learner in it, is a legal server that does
      // not learn -- and the fold that called this fires on every ending
      // turn.
      log.debug("no agent named '{}' is defined, so nothing was mined.", AGENT);
      return;
    }
    Optional<LearningWindow> next = LearningWindow.next(entries, conversations);
    if (next.isEmpty()) {
      log.debug("nothing is awaiting the learner.");
      return;
    }
    LearningWindow window = next.get();
    AgentDefinition definition = registry.get(AGENT);
    UsageAttribution owner =
        usageOwners.conversation(
            window.conversationId(), window.through(), UsageAttribution.Operation.LEARNING);

    String answer;
    try {
      Completion completion =
          dispatcher.complete(
              JobRuntime.requestFor(
                      definition,
                      List.of(
                          ChatMessage.system(definition.prompt()),
                          ChatMessage.user(rendered(window))))
                  .withBudget(BUDGET)
                  .withAttribution(
                      owner.forOperation(UsageAttribution.Operation.LEARNING, definition.name())));
      answer = completion.content();
    } catch (LlmSaturatedException busy) {
      // Debug and not warn, unlike the two below. A pool with no lane
      // spare is a pool serving people's turns, which is what BUDGET is
      // short for; it is the designed outcome of this class yielding, and
      // one line per fold about it is noise.
      log.debug("the learner did not start within {}, so nothing was mined.", BUDGET);
      noted(
          window,
          NOT_MINED.formatted(
              window.shown(), window.awaiting(), "it did not start within " + BUDGET));
      return;
    } catch (LlmException unreachable) {
      // The type and not the message, which is Scribe's rule and its
      // reason: a transport-layer message can carry a URL, a header or a
      // request body, and this sentence is written into a row.
      log.warn("the learner could not be reached, so nothing was mined.", unreachable);
      noted(
          window,
          NOT_MINED.formatted(
              window.shown(),
              window.awaiting(),
              "it could not be reached (" + unreachable.getClass().getSimpleName() + ")"));
      return;
    }

    List<MemoryProposal> proposed;
    try {
      proposed = read(answer, window);
    } catch (ModelJson.Unreadable unusable) {
      log.warn(
          "the learner's answer could not be read ({}), so nothing was mined.",
          unusable.getMessage());
      noted(
          window,
          NOT_MINED.formatted(
              window.shown(),
              window.awaiting(),
              "its answer could not be read (" + unusable.getMessage() + ")"));
      return;
    }

    int filed = 0;
    for (MemoryProposal proposal : proposed) {
      if (file(proposal, window, owner)) {
        filed++;
      }
    }
    entries.markLearned(window.conversationId(), window.ordinals(), now.get());
    noted(
        window,
        MINED.formatted(
            window.shown(),
            window.awaiting(),
            window.through(),
            filed,
            filed == 1 ? "memory" : "memories"));
    log.info(
        "conversation {}: a learning pass read {} of the {} entries awaiting it,"
            + " through turn {}, and filed {}.",
        window.conversationId(),
        window.shown(),
        window.awaiting(),
        window.through(),
        filed);
  }

  /**
   * One proposal through the same door a person's write goes through.
   *
   * <p>{@code Validation.check} first, exactly as {@code MemoryController.write} calls it before
   * the scribe: a proposal with a multi-line summary or a body past the limit is refused without
   * spending an embedding on it. A model that answered with one is not a failure of the pass — it
   * is one candidate this server declines — so it is counted and the rest of the window's proposals
   * still go.
   *
   * @return whether the archive took it
   */
  private boolean file(MemoryProposal proposal, LearningWindow window, UsageAttribution owner) {
    try {
      Validation.check(proposal, archive.maxBodyChars());
      Scribe.Judgement judged =
          owner.status() == UsageAttribution.Status.LEGACY_UNATTRIBUTED
              ? scribe.judge(proposal, window.home())
              : scribe.judge(proposal, window.home(), owner);
      java.util.function.Consumer<String> provenance =
          id -> {
            if (digestProvenance != null)
              digestProvenance.provenance(
                  id,
                  window.conversationId(),
                  window.entries().stream()
                      .map(io.aeyer.plowshare.server.archive.EntryRecord::turnOrdinal)
                      .toList());
          };
      if (owner.status() == UsageAttribution.Status.LEGACY_UNATTRIBUTED) {
        archive.applyVerdict(
            proposal, judged.verdict(), window.home(), judged.embedding(), provenance);
      } else {
        archive.applyVerdict(
            proposal, judged.verdict(), window.home(), judged.embedding(), provenance, owner);
      }
      return true;
    } catch (RuntimeException refused) {
      log.warn(
          "conversation {}: a memory the learner proposed was not filed. Reason: {}",
          window.conversationId(),
          describe(refused));
      return false;
    }
  }

  /**
   * What the model is shown: the window, and the two numbers that say what it is part of.
   *
   * <p><b>Kinds are named and not rendered as roles.</b> This is not a conversation being continued
   * — nothing here is a prompt the model should answer <em>as</em> — it is a transcript being read,
   * so it arrives as labelled lines in one user message rather than as a replayed exchange. That is
   * also what keeps a line inside the transcript from arriving as an instruction with a role behind
   * it; {@code learner.md} carries the same warning in prose, which is the belt to this brace.
   */
  private static String rendered(LearningWindow window) {
    StringBuilder said =
        new StringBuilder(
            THE_SPAN.formatted(describe(window.home()), window.shown(), window.awaiting()));
    said.append("\n\n");
    for (EntryRecord entry : window.entries()) {
      said.append(
              LINE.formatted(
                  entry.ordinal(), entry.turnOrdinal(), who(entry.kind()), entry.content()))
          .append("\n\n");
    }
    return said.toString().strip();
  }

  /**
   * Who said one line, in words rather than in this table's vocabulary.
   *
   * <p>{@code utterance} and {@code answer} are the log's names for its own columns; a model
   * reading a transcript is better served by what they mean.
   */
  private static String who(EntryKind kind) {
    return switch (kind) {
      case UTTERANCE -> "asked";
      case ANSWER -> "answered";
      case SUMMARY -> "summary of what came before";
      case TURN_SUMMARY -> "summary of earlier work in that turn";
      default -> kind.wireName();
    };
  }

  /** Decode the entire bounded answer before any proposal is filed. */
  private static List<MemoryProposal> read(String content, LearningWindow window) {
    return io.aeyer.plowshare.server.agents.ModelAnswers.learning(content).memories().stream()
        .map(
            candidate ->
                new MemoryProposal(
                    candidate.summary(),
                    candidate.scope(),
                    candidate.body(),
                    BY,
                    formedWhere(window)))
        .toList();
  }

  /**
   * An exception as a line a person can read: the type and the first line of the message, and
   * nothing else.
   *
   * <p>{@code JobRuntime.describe}'s rule, and a third copy of it for {@code Curator.describe}'s
   * reason — that method is package-private to {@code server.agents} and this is a package below
   * it. The rule is the part that matters and it is the same one: <b>Postgres puts {@code Detail:
   * Failing row contains (…)} on the second line</b>, and for {@code entries} that row holds
   * whatever a tool read off somebody's disk. A constraint violation logged whole would put it in a
   * log file, out of a method whose whole purpose is to say that a write did not happen.
   */
  private static String describe(RuntimeException failed) {
    String message = failed.getMessage();
    String first = message == null ? "" : message.lines().findFirst().orElse("");
    return first.isBlank()
        ? failed.getClass().getSimpleName()
        : failed.getClass().getSimpleName() + ": " + first;
  }

  /**
   * Which archive this conversation's memories belong to, in the words {@code Scribe} uses for the
   * same fact.
   *
   * <p>Home validates project identities. Flattening additionally protects the renderer's own
   * heading lines if another input source is introduced.
   */
  private static String describe(Home home) {
    return home.isGlobal()
        ? "the global archive"
        : "project '" + MemoryTools.oneLine(home.project()) + "'";
  }

  /**
   * Where a memory this pass produced was formed, in prose a person reads.
   *
   * <p>It no longer says the turn "was summarised away", for {@link #THE_SPAN}'s reason exactly: a
   * window over a tree nobody can speak into again may hold rows no fold ever covered, and this
   * string is stored on the memory and read back long afterwards. The turn is still named, because
   * that is the half of it a person tracing a memory actually uses.
   */
  private static String formedWhere(LearningWindow window) {
    return "read out of conversation "
        + window.conversationId()
        + ", through turn "
        + window.through();
  }

  /**
   * The pass's own record, in the conversation it read.
   *
   * <p>A {@code diagnostic}, so it carries no role and cannot reach a model — {@code
   * entries_role_matches_kind} refuses it one — which is what lets this be written into the
   * conversation at all. It is filed against {@link LearningWindow#through()}, the far end of what
   * it read, because {@code entries_belong_to_a_turn_numbered_from_one} requires a turn and that is
   * the truthful one for a note about a span.
   *
   * <p>Swallowed, and the note is the least important thing this method's caller does: a pass whose
   * diagnostic could not be written has still mined the span and filed what it found.
   *
   * <p>The log's followers are told only once the note is written: a note that failed added nothing
   * for them to read. And telling them is guarded as {@code Compaction}'s is: a push that could not
   * be made must not reach {@link #thereIsMaterial}, which would report a pass that marked its span
   * as one that could not be taken at all.
   */
  private void noted(LearningWindow window, String note) {
    try {
      entries.append(window.conversationId(), window.through(), LoggedEntry.diagnostic(note));
    } catch (RuntimeException notWritten) {
      log.warn(
          "conversation {}: a learning pass ran and its note could not be written." + " Reason: {}",
          window.conversationId(),
          describe(notWritten));
      return;
    }
    try {
      growth.appended(window.conversationId());
    } catch (RuntimeException notTold) {
      log.debug(
          "conversation {}: its followers could not be told a learning pass's note"
              + " landed. Reason: {}",
          window.conversationId(),
          describe(notTold));
    }
  }
}
