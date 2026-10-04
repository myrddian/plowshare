package io.aeyer.plowshare.server.agents.scribe;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.MemoryProposal;
import io.aeyer.plowshare.protocol.Verdict;
import io.aeyer.plowshare.protocol.VerdictKind;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.MemoryTools;
import io.aeyer.plowshare.server.agents.ModelJson;
import io.aeyer.plowshare.server.archive.Archive;
import io.aeyer.plowshare.server.llm.accounting.*;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmException;
import io.aeyer.plowshare.server.llm.dispatch.LlmSaturatedException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides the <em>shape</em> of a write — new, a refinement, or a replacement — before the archive
 * files it.
 *
 * <h2>What it replaces</h2>
 *
 * <p>Until now the client hardcoded {@link VerdictKind#NEW} on every write, so merge and
 * supersession — {@code Lifecycle}, tombstones, the dated-separator merge, the whole newest-wins
 * policy slice 1 built and tested — were reachable only as {@code NEW} from the only client there
 * is. The alternative was letting the calling agent name a target itself, and that is refused for
 * the reason the client's own comment gave: it would hand a caller the power to retire memories it
 * never read.
 *
 * <h2>No tools, and no path by which it could have one</h2>
 *
 * <p>Measured 2026-08-29 against qwen3.5-9b on the reference box: asked merely to say one word, it
 * called a tool <b>3/3</b> times. The scribe sits on the synchronous write path, so each of those
 * would be a second model call with a person waiting on it. So candidates are retrieved here, in
 * code, by the same vector query {@code memory_recall} runs — the same judgement that deleted the
 * librarian — and put in the prompt.
 *
 * <p>This is structural rather than a setting. {@link #judge} builds its request through {@code
 * JobRuntime.requestFor}, which reaches {@link ChatRequest#of(String, java.util.List)} and so
 * carries an empty tool list, and never calls {@code withTools}; it never reads {@link
 * AgentDefinition#tools()} at all, so an operator who adds a tool to {@code scribe.md} changes
 * nothing here. {@code a_definition_that_declares_tools_is_still_offered_none} is what holds that
 * line.
 *
 * <h2>Never throws, and every failure is a distinguishable {@code NEW}</h2>
 *
 * <p>A write must not be lost because the thing that judges its shape was down. Eleven {@code
 * flat(...)} sites therefore end in {@code NEW}, and <b>each files under its own reason</b>: a year
 * later a maintainer has to be able to tell "no scribe is deployed" from "the scribe was busy",
 * because those have opposite fixes. <b>Seventeen reason strings, not eleven</b> — {@link
 * Unreadable} fans one site out into seven sentences saying what about the answer could not be
 * read. Eleven is the number to change; seventeen is the number to grep. Every fallback reason
 * opens {@code "filed flat: "} and a judged verdict's is stripped of it by {@link #judgedReason},
 * so that prefix is a grep rather than a convention a model can break by emitting it.
 *
 * <p>The reason travels back to the writing agent through {@code memory_write}'s output, and that
 * is why <b>no failure's own message is put in it</b>: only the exception's type. A transport-layer
 * message can carry a URL, a header or a request body, and a reason is prose a model reads and a
 * person may paste somewhere. The full throwable goes to the log instead, where {@code
 * LlmException}'s family is already documented as naming neither a base URL nor a key.
 *
 * <h2>What it is not</h2>
 *
 * <p>Not a job. It makes exactly one model call and has no turn loop, so {@link
 * AgentDefinition#maxTurns()} and {@link AgentDefinition#maxModelCalls()} are not read here; the
 * file carries {@code 1} for each because that is what is true of it. Running it through {@code
 * JobRuntime} would give it a tool layer, which is the one thing it must not have.
 *
 * <h2>The cost, which is one embedding call and used to be two</h2>
 *
 * <p>{@link #judge} retrieves candidates by vector, so a write that reaches this class has to have
 * its proposal embedded. <b>It embeds it exactly once, and the archive stores that same vector
 * rather than computing it again</b>: the candidate query and the memory are embedded for the
 * identical text, so {@link Judgement} carries the vector out and {@code Archive.applyVerdict(…,
 * Precomputed)} takes it.
 *
 * <p><b>It was two, and on more writes than it looks.</b> The retrieval happens before the
 * empty-candidate short circuit — it has to, since the short circuit is a fact about what the
 * retrieval found — so it was never only the writes a model rules on: past the three guards above
 * it (no registry, no {@code scribe} definition, a blank summary) <em>every</em> write was 2×. That
 * is why the saving is on the common path rather than the rare one, and why every fallback taken
 * after the query hands the vector on too — the commonest of them is the empty-candidate one.
 *
 * <p><b>A write whose endpoint is down still costs two</b>, and the number is a fact about that
 * case rather than about writes in general: the query throws, so there is no vector to hand on,
 * this class files flat without one, and the archive asks again for the memory. {@code
 * EndToEndTest.MarkerEmbeddings} carries that as the reason its failure knob is a count and not a
 * flag — a single-shot failure was consumed by the query, leaving the memory perfectly searchable
 * under a test asserting it was not.
 *
 * <p>Retrieval in code is still the design, and the alternative the spec rejected — giving the
 * scribe {@code memory_recall} and letting it retrieve — still costs a model call instead, against
 * a model measured calling a tool <b>3/3</b> times when asked merely to say one word. What changed
 * is the size of the bill, not who pays it.
 *
 * <p><b>The identity the saving rests on is incidental, and the archive checks it rather than
 * trusting it.</b> Two expressions in two classes that happen to agree; reused, a drift stores a
 * wrong vector against a right memory, which no search would ever report. {@link #question} and
 * {@code Archive.embeddedText} are the pair, and {@code Archive.applyVerdict(…, Precomputed)} owns
 * the argument and the refusal — it is not repeated here.
 *
 * <h2>The reason is durable, and reading it back is {@link
 * io.aeyer.plowshare.server.archive.ReasonLog}</h2>
 *
 * <p>The spec says every fallback "says so in the {@code reason} … <b>So a year later the archive
 * can distinguish 'no scribe judged this' from 'the scribe was busy'.</b>" It could not, for two
 * slices: {@code Archive.applyVerdict} put {@code verdict.reason()} into the returned {@code
 * WriteResult} and nowhere else, so the seventeen distinct strings reached the HTTP response and
 * the client's renderer — useful to whoever made the write, in the moment — and were gone the
 * instant that response was read.
 *
 * <p>They are now also a row. <b>A table and not a column on {@code memories}</b>, and the obstacle
 * was a fact about merges rather than a preference about timing: {@code Archive.merge} writes no
 * new row — it appends to the <em>target</em> and returns the target's own id — so a {@code
 * MERGED_INTO} verdict had no new record for a column to sit on, and writing it onto the target
 * would have overwritten what that memory's own write recorded, months earlier. That is exactly the
 * distinction the spec cares about most, and an append-only table is what makes attaching a merge
 * to the target safe. {@code V5__memory_reasons.sql} carries the whole account and the three
 * questions its shape had to settle.
 *
 * <p>It is also <b>not</b> the same change as {@code Proposal.proposedBy}, which was a settled
 * one-column addition to a different table and shipped on its own in {@code
 * V4__proposals_proposed_by.sql} for exactly that reason: one file carrying both {@code ALTER
 * TABLE}s would have tied a decided change to an undecided one.
 */
public final class Scribe implements UsageAware {
  private UsageOwners usageOwners = UsageOwners.NONE;

  @Override
  public void useUsageOwners(UsageOwners source) {
    usageOwners = java.util.Objects.requireNonNull(source);
  }

  private static final Logger log = LoggerFactory.getLogger(Scribe.class);

  /** The agent this looks for in the registry, and the stem of its file. */
  public static final String AGENT = "scribe";

  /**
   * How many memories the scribe is shown.
   *
   * <p>{@code MemoryTools.DEFAULT_LIMIT}'s number, and the same reasoning: a shortlist a small
   * model can hold in front of itself. It is a ceiling on prompt size as much as on relevance —
   * every candidate past the first few is one the vector query already ranked below them.
   */
  static final int CANDIDATES = 5;

  /**
   * How long the scribe is willing to wait to <em>start</em>.
   *
   * <p>The first real user of the request-owned budget. A write from a harness is interactive:
   * somebody is waiting on it, and the cost of not judging is a flat filing rather than a lost
   * memory. Without this the request takes the pool's own default — thirty seconds in the shipped
   * configuration, which is right for a batch ingest and wrong here.
   *
   * <p>It bounds queueing only. The model's own generation is bounded by the pool's {@code
   * chat-timeout}; there is no per-request budget for that, and inventing one here would be a
   * second timeout nobody could see in the configuration.
   */
  static final Duration BUDGET = Duration.ofSeconds(5);

  /**
   * A verdict, and the embedding the archive can skip because of it.
   *
   * <p>{@link #judge} retrieves candidates with the same text the memory it is judging will be
   * embedded for, so its query vector <em>is</em> the memory's vector. Handing the pair back is
   * what turns a write's two model calls into one; {@code Archive.applyVerdict} takes it, checks
   * the text it came from against the memory's own, and refuses rather than storing a vector that
   * does not belong to it.
   *
   * @param verdict how this proposal should be filed. Never null.
   * @param embedding the query vector and its text, or {@code null} when the scribe never got as
   *     far as embedding anything — no registry, no scribe defined, a blank summary, an endpoint
   *     that could not be reached, or the outer catch. Every fallback taken <em>after</em> the
   *     query carries it, which matters: the commonest of them is the empty-candidate short
   *     circuit, and a build that dropped the vector there would pay two model calls on exactly the
   *     writes 3a measured as the expensive case.
   */
  public record Judgement(Verdict verdict, Archive.Precomputed embedding) {}

  private final LlmDispatcher dispatcher;
  private final Archive archive;
  private final Supplier<AgentRegistry> agents;
  private final Duration budget;

  /**
   * @param agents the agent graph, resolved late. <b>A supplier for the reason {@code JobRuntime}'s
   *     constructor sets out:</b> the registry validates every declared tool name against the tool
   *     layer, so one of the two has to be built after the other, and a boot that has not filled
   *     this in yet must file writes flat rather than fail them. A supplier that yields null is
   *     that state, and {@code no_registry_wired_falls_back_to_new} is what pins it.
   */
  public Scribe(LlmDispatcher dispatcher, Archive archive, Supplier<AgentRegistry> agents) {
    this(dispatcher, archive, agents, BUDGET);
  }

  /**
   * The same, with the queueing budget named.
   *
   * <p>Package-private, and it exists so the saturation path can be measured in milliseconds
   * instead of by waiting {@link #BUDGET} out in a test. What that costs is that the tests driving
   * saturation say nothing about the shipped number, so {@code
   * the_shipped_budget_is_what_a_default_scribe_asks_for} builds a scribe through the
   * <em>public</em> constructor and reads the budget off the request. An earlier version of this
   * sentence credited {@code the_request_carries_the_scribes_own_budget_and_not_the_pools} with
   * that, and it does not: it passes 150ms to this constructor and never mentions {@link #BUDGET}.
   */
  Scribe(
      LlmDispatcher dispatcher, Archive archive, Supplier<AgentRegistry> agents, Duration budget) {
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    this.archive = Objects.requireNonNull(archive, "archive");
    this.agents = Objects.requireNonNull(agents, "agents");
    this.budget = Objects.requireNonNull(budget, "budget");
  }

  /**
   * How this proposal should be filed in this tier.
   *
   * <p>Never throws for anything the model, the endpoint or the archive does. The last-resort
   * clause at the bottom is what makes that sentence true of this method rather than of the paths
   * it currently has, and it is reachable — {@code
   * a_scribe_that_cannot_be_asked_at_all_falls_back_to_new} drives it through a supplier that
   * throws, which is what a half-finished wiring looks like.
   *
   * @throws NullPointerException if either argument is null, which is the caller's bug and not a
   *     write worth filing flat
   */
  public Judgement judge(MemoryProposal proposal, Home home) {
    return judge(proposal, home, usageOwners.in(home, null, UsageAttribution.Operation.REVIEW));
  }

  public Judgement judge(MemoryProposal proposal, Home home, UsageAttribution owner) {
    Objects.requireNonNull(proposal, "proposal");
    Objects.requireNonNull(home, "home");
    try {
      return judged(proposal, home, owner);
    } catch (RuntimeException broken) {
      // Everything the model, the endpoint and the archive can do is
      // handled below and returns a verdict rather than reaching here.
      // What reaches here is this server being wrong — a supplier that
      // throws, a registry half-wired — and losing a memory over that is
      // the one outcome worse than filing it flat.
      log.error("The scribe could not be asked at all; filing flat.", broken);
      return flat(
          "the scribe could not be asked at all (" + broken.getClass().getSimpleName() + ")");
    }
  }

  private Judgement judged(MemoryProposal proposal, Home home, UsageAttribution owner) {
    AgentRegistry registry = agents.get();
    if (registry == null) {
      // Separate from the miss below, and the separation is what keeps a
      // tripwire honest. EndToEndTest asserts this exact sentence against
      // a context with no AgentRegistry bean; collapsed into one reason,
      // a boot that wired a registry over a directory with no scribe.md
      // would produce the same words and leave that test green over a
      // wiring that had become real.
      return flat("this server has no agent registry");
    }
    if (!registry.names().contains(AGENT)) {
      // Asked by name rather than through AgentRegistry.get, which throws
      // for a miss: an absent scribe is a filing decision here, not an
      // exception for the clause at the bottom to turn into a different
      // sentence.
      return flat("this server defines no agent named '" + AGENT + "'");
    }
    AgentDefinition definition = registry.get(AGENT);
    if (proposal.summary().isBlank()) {
      // Reached before Validation.check does, because MemoryController
      // judges and then applies. Without this the blank summary becomes an
      // embedding call and a model call, both spent on a write that is
      // about to be refused with 400.
      return flat("the proposal had no summary to judge it by");
    }

    // Embedded here rather than inside recall, so the vector survives this
    // method and reaches the archive. Everything below this line files under
    // a reason AND hands the vector on; everything above it -- the three
    // guards -- returns before there is one to hand on, which is exactly the
    // set of writes that never cost a model call in the first place.
    // Null until the endpoint answers, and read again in the catch below:
    // `recall` can fail for a reason that is not the embedding -- a database
    // that is gone -- and in that case the vector is already paid for and
    // perfectly good, so the write should not buy it twice.
    Archive.Precomputed query = null;
    List<Memory> candidates;
    try {
      query =
          owner.status() == UsageAttribution.Status.LEGACY_UNATTRIBUTED
              ? archive.embedding(question(proposal))
              : archive.embedding(question(proposal), owner);
      candidates = nameable(archive.recall(query, home, CANDIDATES).memories(), home);
    } catch (RuntimeException unsearchable) {
      // Archive.recall throws rather than returning empty when the
      // question cannot be embedded, precisely so "nothing is held" and
      // "the search never ran" stay different facts. Collapsing them here
      // would file a write flat under a sentence claiming the archive
      // holds nothing like it.
      log.warn(
          "The archive could not be searched for scribe candidates; filing flat.", unsearchable);
      return flat("the archive could not be searched for anything like this proposal", query);
    }
    if (candidates.isEmpty()) {
      // No model call. Both verdicts that name a target take it from this
      // list, so with an empty one the answer is NEW whatever the model
      // would have said — and asking anyway spends a call, on the
      // synchronous write path, to be told something already known.
      return flat(
          "the archive held nothing close to this proposal, so there was"
              + " nothing to file it against",
          query);
    }

    Completion completion;
    try {
      completion =
          dispatcher.complete(
              JobRuntime.requestFor(
                      definition, conversation(definition, proposal, home, candidates))
                  .withBudget(budget)
                  .withAttribution(
                      owner.forOperation(UsageAttribution.Operation.REVIEW, definition.name())));
    } catch (LlmSaturatedException busy) {
      // Ahead of LlmException, which it extends. Saturation is the one
      // failure here that is about load rather than about the box, and it
      // is the one an operator fixes by adding a lane slot.
      // Placeholder and throwable together, like every other log line
      // here. Measured rather than assumed, because this branch has been
      // caught on unverified library behaviour:
      // a_saturated_scribe_logs_the_budget_and_the_cause reads the event
      // back off a logback appender and asserts both the interpolated
      // budget and the attached throwable.
      log.warn("The scribe was not started within {}; filing flat.", budget, busy);
      return flat("the scribe was busy and did not start within its budget", query);
    } catch (LlmException unreachable) {
      log.warn("The scribe could not be reached; filing flat.", unreachable);
      return flat("the scribe could not be reached", query);
    } catch (RuntimeException broken) {
      // Wider than LlmException on purpose, and the sentence says which
      // one it is: an IllegalArgumentException out of ChatRequest for a
      // definition whose model: is misconfigured is not the endpoint being
      // down, and telling an operator it was would send them at the wrong
      // machine. JobRuntime draws the same distinction for the same
      // reason.
      log.warn("The scribe failed for a reason that is not the endpoint; filing flat.", broken);
      return flat(
          "the scribe failed for a reason that is not the endpoint being"
              + " unreachable ("
              + broken.getClass().getSimpleName()
              + ")",
          query);
    }

    try {
      return new Judgement(read(completion.content(), candidates), query);
    } catch (Unreadable unusable) {
      return flat(unusable.getMessage(), query);
    }
  }

  /**
   * The recall hits this write could actually be filed against.
   *
   * <p>{@code Archive.recall} answers a project question from the project tier <em>and</em> global,
   * while {@code Archive.requireTarget} refuses a cross-tier target outright — "a memory in another
   * tier is shadowed, never superseded", because one project retiring a global memory would take it
   * away from every other project. So a project write is handed memories it cannot name, and
   * showing them means either mislabelling the list or spending the scribe's one call on a verdict
   * that has to be thrown away.
   *
   * <p><b>Filtering here costs no same-tier candidate</b>, and that was checked rather than assumed
   * — an earlier version of this class labelled the tiers instead, on the belief that {@code
   * recall} truncates the merged answer by distance and that dropping global hits afterwards would
   * shrink the shortlist. It does not truncate that way. {@code MemoryStore.searchByVector} filters
   * to one tier and orders by distance <em>within</em> it, and {@code recall} concatenates the
   * project block ahead of the global one before {@code subList(0, limit)} — which is what {@code
   * Archive.Recall}'s javadoc means by "project tier ahead of global". The truncation can therefore
   * only drop global rows, so this leaves exactly {@code min(projectHits, CANDIDATES)}: the same
   * list a project-only search would have returned.
   *
   * <p>A global recall reads one tier already, so this is a no-op for one.
   */
  private static List<Memory> nameable(List<Memory> found, Home home) {
    return found.stream().filter(memory -> memory.home().equals(home)).toList();
  }

  /**
   * What the candidate search asks.
   *
   * <p>Summary and scope, joined by a newline — <b>the same text {@code Archive.embed} stores a
   * memory's vector for</b>, read from that method rather than guessed. A query built any other way
   * is a point in the same space asking a differently shaped question, and the nearest neighbours
   * it returns are nearest to something other than the proposal.
   *
   * <p><b>Stripped, and it did not used to be.</b> {@code Archive.newMemory} strips both fields on
   * the way in, so {@code Archive.embeddedText} is the stripped text while this joined the raw ones
   * — the two agreed for every proposal without whitespace at an end and differed for the rest.
   * That was harmless while the two vectors were computed separately, since each was right for its
   * own use. It is not harmless now: this vector <em>becomes</em> the memory's, and {@code
   * Archive.applyVerdict} refuses one computed from other text rather than storing it. {@code
   * a_write_whose_summary_is_padded_is_still_filed} is what fails if this strip goes.
   */
  private static String question(MemoryProposal proposal) {
    return proposal.summary().strip() + "\n" + proposal.scope().strip();
  }

  // --- the prompt --------------------------------------------------------------

  /**
   * The system prompt is the agent file's, verbatim; everything else is built here.
   *
   * <p><b>The output contract is in Java and not in {@code scribe.md}</b>, and that is deliberate:
   * {@link #read} is the other half of it. An operator who edited the JSON keys in the file would
   * not break the boot or fail a test — every write would simply file flat, forever, under "the
   * scribe's answer could not be read". What belongs in the file is the policy: what counts as a
   * refinement rather than a replacement.
   */
  private static List<ChatMessage> conversation(
      AgentDefinition definition, MemoryProposal proposal, Home home, List<Memory> candidates) {
    StringBuilder out = new StringBuilder();
    out.append("A memory is being proposed for ").append(describe(home)).append(".\n\n");
    out.append("Proposed:\n");
    out.append("summary: ").append(MemoryTools.oneLine(proposal.summary())).append('\n');
    out.append("when: ").append(MemoryTools.oneLine(proposal.scope())).append('\n');
    // The proposal's body is shown and the candidates' are not. It is one
    // body against five, on the synchronous write path, against a 9b model;
    // and the proposal is the thing being judged, while the candidates only
    // have to be recognisable enough to be chosen between.
    out.append("body:\n").append(MemoryTools.quote(proposal.body())).append("\n\n");

    // True of both tiers, because nameable() has already made it so: what
    // the model is shown is exactly the set of ids it may name, and FORMAT's
    // closing "only an id from the list above" needs no tier qualifier to be
    // the whole rule. The alternative — showing global hits under a caveat —
    // put unnameable decoys and a two-clause exception in front of a 9b
    // model on the synchronous write path, in a renderer that withholds
    // candidate bodies to protect that same attention.
    out.append("Already held in ")
        .append(describe(home))
        .append(", nearest this proposal first.")
        .append(" These are the only memories you may name.\n");
    for (Memory candidate : candidates) {
      out.append('\n').append(MemoryTools.oneLine(candidate.id())).append('\n');
      out.append("summary: ").append(MemoryTools.oneLine(candidate.summary())).append('\n');
      out.append("when: ").append(MemoryTools.oneLine(candidate.scope())).append('\n');
    }

    out.append('\n').append(FORMAT);
    return List.of(ChatMessage.system(definition.prompt()), ChatMessage.user(out.toString()));
  }

  /**
   * What the answer has to look like, in the model's own input.
   *
   * <p>Spelled out with the three verdicts and what each does, because the wire names are not
   * self-explanatory: {@code merged_into} keeps the target and {@code supersedes} retires it, and a
   * model that had those the wrong way round would retire memories it meant to extend.
   */
  private static final String FORMAT =
      """
            Answer with one JSON object and nothing else:

            {"verdict": "new", "target": null, "reason": "one sentence"}
            {"verdict": "merged_into", "target": "<an id above>", "reason": "one sentence"}
            {"verdict": "supersedes", "target": "<an id above>", "reason": "one sentence"}

            new — this claim is not one of the memories above.
            merged_into — this is more detail on one of them; that memory is kept \
            and this text is added to it.
            supersedes — this replaces one of them; that memory is retired and \
            this one answers in its place. Use it only when the older claim has \
            stopped being true.

            Name a target only for merged_into and supersedes, and only an id \
            from the list above.""";

  /**
   * "the global archive" or "the 'payments' archive".
   *
   * <p>Flattened, like every other value this renderer puts in a single-line slot. {@code Home.of}
   * refuses a blank project name and checks nothing else, so a line break in one would otherwise
   * reach column zero — and this string now appears on a candidate's own heading line, which is the
   * line a forged entry would have to imitate.
   */
  private static String describe(Home home) {
    return home.isGlobal()
        ? "the global archive"
        : "the '" + MemoryTools.oneLine(home.project()) + "' archive";
  }

  // --- reading the answer ------------------------------------------------------

  /**
   * A model answer that cannot become a verdict, carrying the sentence it files flat under. Nested,
   * so nothing outside this class can be thrown into {@link #judged}'s catch for it.
   */
  private static final class Unreadable extends RuntimeException {

    Unreadable(String detail) {
      super("the scribe's answer could not be read (" + detail + ")");
    }
  }

  private static Verdict read(String content, List<Memory> candidates) {
    JsonNode answer;
    try {
      answer = ModelJson.object(content);
    } catch (ModelJson.Unreadable why) {
      // Re-wrapped rather than caught in judged(): every sentence a write
      // files flat under opens "the scribe's answer could not be read",
      // and the shared reader deliberately reports a detail rather than a
      // sentence, because the curator frames the same three details as
      // being about a memory's ruling instead.
      throw new Unreadable(why.getMessage());
    }

    JsonNode kindNode = answer.path("verdict");
    if (!kindNode.isTextual()) {
      throw new Unreadable("no 'verdict'");
    }
    VerdictKind kind;
    try {
      kind = VerdictKind.fromWireName(kindNode.asText());
    } catch (IllegalArgumentException unknown) {
      throw new Unreadable(
          "'" + kindNode.asText() + "' is not one of new, merged_into," + " supersedes");
    }

    JsonNode reasonNode = answer.path("reason");
    if (!reasonNode.isTextual() || reasonNode.asText().isBlank()) {
      // Required, on Verdict's own reasoning: a supersession whose reason
      // was optional would routinely arrive without one, leaving a retired
      // memory and no account of what retired it.
      throw new Unreadable("no 'reason'");
    }
    String reason = judgedReason(reasonNode.asText());

    if (kind == VerdictKind.NEW) {
      // Whatever it put in 'target' is dropped rather than passed through.
      // NEW names nothing, and WriteResult.targetId would otherwise report
      // a memory this write did not touch.
      return new Verdict(VerdictKind.NEW, null, reason);
    }

    JsonNode targetNode = answer.path("target");
    if (!targetNode.isTextual() || targetNode.asText().isBlank()) {
      throw new Unreadable("a '" + kind.wireName() + "' verdict names no memory");
    }
    String target = targetNode.asText().strip();

    if (candidates.stream().noneMatch(candidate -> candidate.id().equals(target))) {
      // The hallucinated id. Archive.applyVerdict throws for one, and a
      // throw on the write path loses the memory outright — so the id is
      // checked against what the scribe was actually shown, which is
      // stricter than "it exists" and is the honest test of a judgement
      // made from a list.
      //
      // This is also the whole cross-tier defence, since nameable() has
      // already removed the memories that would fail requireTarget: a
      // global id in a project write is one the scribe was never shown,
      // so the sentence is true of it. There is no second guard here,
      // because a guard the shipped path cannot reach is dead code kept
      // alive by a test that constructs its input.
      return flatVerdict(
          "the scribe named " + target + ", which is not one of the memories it was shown");
    }
    return new Verdict(kind, target, reason);
  }

  // --- the flat filing ---------------------------------------------------------

  /**
   * A {@code NEW} verdict whose reason says which fallback produced it.
   *
   * <p>The {@code "filed flat: "} opening is the marker: a judged verdict's reason is the model's
   * own words and never carries it, so one grep separates the writes nothing judged from the writes
   * something did.
   */
  private static final String FLAT = "filed flat: ";

  /**
   * A flat filing with no vector to offer — the three guards above the candidate query, and the
   * outer catch.
   */
  private static Judgement flat(String because) {
    return new Judgement(flatVerdict(because), null);
  }

  /**
   * The same, keeping the vector the query already paid for. Every fallback after the retrieval
   * takes this one: the write still has to be embedded, and the numbers are already in hand.
   */
  private static Judgement flat(String because, Archive.Precomputed query) {
    return new Judgement(flatVerdict(because), query);
  }

  private static Verdict flatVerdict(String because) {
    // Flattened here and not at the call sites, which is the correction:
    // two of the eleven quote text the model chose — an invented verdict
    // word, an invented memory id — and only the id ones had been
    // flattened. A reason with a line break in it reaches column zero in
    // memory_write's output. One boundary, so a twelfth fallback cannot
    // forget.
    return new Verdict(VerdictKind.NEW, null, MemoryTools.oneLine(FLAT + because));
  }

  /**
   * The model's own reason, made safe to put beside this class's own.
   *
   * <p>Flattened, because the sentence is model text on its way into another model's input through
   * {@code memory_write}'s output, where a line break reaches column zero.
   *
   * <p>And stripped of any leading {@link #FLAT}, which is the correction to a comment that claimed
   * more than the code did: the class javadoc offers that prefix as the grep separating writes
   * nothing judged from writes something did, and a model that emitted it verbatim defeated exactly
   * that. A loop rather than one strip, because one is beaten by doubling the prefix. Rewriting the
   * model's words is a cost, and a narrow one: it is the only string this class reserves, and the
   * alternative was a marker that is merely usually right.
   */
  private static String judgedReason(String raw) {
    String reason = MemoryTools.oneLine(raw);
    while (reason.regionMatches(true, 0, FLAT, 0, FLAT.length())) {
      reason = reason.substring(FLAT.length()).strip();
    }
    return reason;
  }
}
