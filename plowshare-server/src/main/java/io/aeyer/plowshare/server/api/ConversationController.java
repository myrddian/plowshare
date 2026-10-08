package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.CallerAccess;
import io.aeyer.plowshare.server.agents.Compaction;
import io.aeyer.plowshare.server.agents.EntryKind;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.LogStages;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.archive.CompactionStore;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Conversations;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.TurnRecord;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.auth.AuthFilter;
import io.aeyer.plowshare.server.llm.tokens.Tokenizer;
import io.aeyer.plowshare.server.requests.RequestedAfter;
import io.aeyer.plowshare.server.requests.RequestedAgent;
import io.aeyer.plowshare.server.requests.RequestedBefore;
import io.aeyer.plowshare.server.requests.RequestedBudget;
import io.aeyer.plowshare.server.requests.RequestedHome;
import io.aeyer.plowshare.server.requests.RequestedKinds;
import io.aeyer.plowshare.server.requests.RequestedLifecycle;
import io.aeyer.plowshare.server.requests.RequestedLogQuestion;
import io.aeyer.plowshare.server.requests.RequestedSession;
import io.aeyer.plowshare.server.requests.RequestedTurnCap;
import io.aeyer.plowshare.server.requests.RequestedWindow;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Conversations: opening one, reading one back, and continuing a run in one that stopped.
 *
 * <h2>Why this is its own controller and not a method on {@code AgentController}</h2>
 *
 * <p>A turn is a job and is submitted, polled and cancelled through {@code AgentController}
 * precisely because of that — the conversation changes the allowance and the home and nothing else
 * about a run. <b>A conversation is not a job.</b> It outlives every job in it, it is a row where a
 * job is a handle in a map, and it has a lifecycle of its own that a restart does not end. That is
 * the same difference that puts {@code projects}, {@code memories} and {@code proposals} behind
 * three controllers rather than one: this server names a surface after the thing it manages.
 *
 * <p>{@code AgentController}'s own javadoc is the other half of the argument. It names the job
 * surface and lists the verbs on it, and every one of them is a fact about a run; adding a
 * conversation's lifecycle to that class would make the sentence that opens it describe two things.
 *
 * <h2>The reads, and the rule that decided each one</h2>
 *
 * <p>This section used to say there was no listing and no read-back, on the rule {@code
 * ProjectController} states and {@code AgentController} paid for once: an endpoint with no caller
 * is an untested surface saying the server offers more than it does, and adding one when something
 * wants it is cheaper than carrying it. <b>The rule has not changed; what wants them has.</b> A
 * REPL needed neither, and the reasoning was written from the REPL — it opens a conversation,
 * speaks by putting the id {@link #open} answers with into {@code RunAgentRequest.conversation},
 * and polls {@code GET /v1/jobs/&#123;id&#125;} like any other run, so its own scrollback is the
 * transcript and it never asks this server to describe a conversation back to it. A console is not
 * a terminal, and each read here exists because something in it cannot be built any other way:
 *
 * <ul>
 *   <li>{@link #compactions} came first, and by the same rule. <b>A compaction is the one thing a
 *       conversation does that its own speaker cannot see.</b> It happens inside the next turn's
 *       {@code Compaction.TurnTranscript.before()}, before that turn's first model call, publishes
 *       no {@code JobEvent} (the stream is lifecycle-only and carries no payload by signature), and
 *       is not counted in the {@code modelCalls} the outcome reports. Nothing on the wire changes.
 *       That silence is the failure the design names, and its mitigation is not a better prompt but
 *       that "the person can see the seam and read what was behind it".
 *   <li>{@link #turns} came with the console, and the argument is the reverse of the one above:
 *       what the REPL already had in its scrollback is exactly what a reloaded browser tab does not
 *       have. Its own javadoc carries this.
 *   <li>{@link #list} is what a console opens on, because a person arriving at one has no saved id
 *       to resume from — which is the whole of what a terminal's shell history was doing for the
 *       REPL.
 *   <li>{@link #chat} and {@link #trajectory} came with the MCP surface, and they are the same rule
 *       reaching a third client. <b>A foreign harness could start a Plowshare agent and collect its
 *       result and nothing else</b> — it could not list conversations, read one back or see what a
 *       run did — so the archive of folded histories, timings and failed attempts behind every
 *       result was invisible to exactly the client that has no scrollback of its own. The two are
 *       the two readings {@code EntryStore} already had and neither had a door: {@code
 *       thatProjectFor} is what a model is shown and {@code forConversation} is what happened.
 *   <li>{@link #context} is the one read whose answer <em>cannot</em> be computed by a client,
 *       rather than merely being inconvenient to. A token count exists in one place — {@code
 *       usage.prompt_tokens} on a completion — and never reaches one; a browser counting the same
 *       prompt would be counting characters and calling them tokens.
 *   <li>{@link #projection} is the opposite case from {@link #context}'s, and worth holding beside
 *       it for the contrast: what a request would contain <em>can</em> be computed here, because
 *       both of its inputs — the log and the agent definition — are on this box already, and {@code
 *       Compaction.projectionFor} is the same computation a real turn performs. What it must never
 *       do is answer by actually sending that request, which would evict the prefix the next real
 *       turn reuses to buy a measurement for a screen.
 * </ul>
 *
 * <p><b>There is still no {@code GET /v1/conversations/&#123;id&#125;} and no way to close one</b>,
 * and both absences are the rule still working. {@link #context} is not a counter-example and is
 * worth distinguishing from one: it answers about a conversation's <em>cost</em>, which is not on
 * the row and is not in {@link #list}, and it declines to answer the parts of that cost nobody can
 * measure rather than reporting the row again under another name. A single conversation's own row
 * carries the id, the home and the budget, and {@link #list} answers all three for every
 * conversation in a tier; a caller that has an id and wants only what that id names is a caller
 * nothing has needed. Closing one is not deferred pending a caller but absent by design: a
 * conversation ends when its allowance does, and there is no state a person could set that would
 * mean anything the budget does not already say.
 *
 * <p><b>An id that names no conversation is refused at the first turn</b>, by {@code
 * ConversationStore.find} through {@code Turn.speak}, as a 404 naming the id. That has not stopped
 * being true and it is why nothing here is a precondition check for a caller about to speak: {@link
 * #turns} looks the row up to tell an empty history from a missing one, not to save the next
 * utterance a check it does anyway.
 *
 * <h2>The one write that is not opening a conversation</h2>
 *
 * <p>{@link #resume} starts a turn, which every other endpoint that starts one does through {@code
 * AgentController} — and it is here for the reason the reads are: <b>the job it would otherwise
 * hang off is gone.</b> A run that stopped is a finished job, a handle in one process's map that a
 * restart does not keep, and what a person is looking at is the conversation. Its own javadoc
 * argues why {@code POST /v1/jobs/&#123;id&#125;/limits} is not the door.
 *
 * <h2>Which agent answers is not decided here, and is now recorded</h2>
 *
 * <p><b>A person's conversation names no agent, and that has not changed.</b> The agent is named
 * per turn, in the path of {@code POST /v1/agents/&#123;name&#125;/runs}, which is what lets
 * somebody open one conversation and put two different agents' turns in it. {@code
 * conversations_a_person_s_conversation_names_no_agent} is that sentence held in the schema:
 * exactly the {@code turn}-origin rows have a NULL agent column.
 *
 * <p><b>What has changed is the second half of the old argument.</b> This paragraph used to end
 * "pinning an agent onto the row would be a column with no reader today", and that stopped being
 * true when every run got a conversation. A delegated child, a curator's ruling and a submission
 * each have exactly one agent for the whole life of the conversation — a fact about the row and not
 * about a turn in it — and {@code turns.agent} records who answered each turn of a person's. Both
 * columns arrived in {@code V17__conversation_origin.sql}, which argues why it is two columns and
 * not one.
 *
 * <p>{@link #resume} used to be the price of that absence and is now the reader: it continues a
 * stopped run <b>as the agent that answered it</b> rather than as the one a caller names, which
 * removes a way to be wrong that nothing noticed — a resumption opens with the stopped run's whole
 * history, and continuing it under a different agent put one agent's working in front of another.
 * {@link #context} reads the same fact for its default. Neither takes an opinion about which agent
 * a conversation <em>belongs</em> to; they read which one was actually there.
 */
@RestController
public class ConversationController {

  /**
   * The most entries one page of a chat or a trajectory can hold, whatever is asked for — {@link
   * RequestedWindow#MOST_ENTRIES_A_PAGE}, which is where the number and the argument for it now
   * live.
   *
   * <p><b>Named here as well because this is the spelling its readers use.</b> {@code
   * ConversationControllerTest} and the console's {@code trajectory.ts} both say {@code
   * ConversationController.MOST_ENTRIES_A_PAGE} when they mean "the cap these endpoints apply", and
   * the cap is a fact about these endpoints whoever owns the arithmetic. It is a reference and not
   * a second copy: there is one number, in {@link RequestedWindow}, beside the refusals that read
   * it.
   */
  private io.aeyer.plowshare.server.information.InformationJobs information;

  @org.springframework.beans.factory.annotation.Autowired
  public void useInformationInputs(io.aeyer.plowshare.server.information.InformationJobs inputs) {
    information = inputs;
  }

  private EntryStore entryReads() {
    return information == null
        ? entries
        : entries.forAccount(io.aeyer.plowshare.server.information.InformationCaller.account());
  }

  private void requireInformation(String log) {
    if (information != null)
      information.requireLog(
          log, io.aeyer.plowshare.server.information.InformationCaller.account());
  }

  public static final int MOST_ENTRIES_A_PAGE = RequestedWindow.MOST_ENTRIES_A_PAGE;

  private io.aeyer.plowshare.server.personal.PersonalSpaces personal;

  @org.springframework.beans.factory.annotation.Autowired
  public void usePersonalSpaces(io.aeyer.plowshare.server.personal.PersonalSpaces personal) {
    this.personal = personal;
  }

  private final CallerAccess access;
  private final ConversationStore conversations;
  private final CompactionStore compactions;
  private final TurnStore turns;
  private final EntryStore entries;
  private final JobRuntime runtime;
  private final Turn speaking;
  private final ObjectProvider<AgentRegistry> agents;
  private final ConversationsProperties properties;

  /**
   * The domain rules a conversation is read and continued under — which agent answered, which agent
   * may continue a stopped run, and whether an id names a conversation at all. Moved out to {@link
   * Conversations} so a future frame dispatcher can reach the same rules without going through this
   * controller.
   */
  private final Conversations rules;

  /**
   * What {@link #projection} reads to assemble a conversation's next prompt.
   *
   * <p><b>Not {@link #compactions}, and the two names are one letter apart on purpose</b> — {@code
   * turn}/{@code turns} and {@code entry}/{@code entries} are this package's own convention for
   * "the one" beside "the store of them", and {@link Compaction} is a service with a computation on
   * it, never a row. {@link #compactions} answers what already happened to a conversation; this
   * answers what would happen to its next prompt, and neither can stand in for the other.
   */
  private final Compaction compaction;

  /** How this deployment counts tokens; see {@link ContextView.Prefix}. */
  private final Tokenizer tokenizer;

  /**
   * {@code log.open} and {@code log.close}: a person's conversation opens and closes through this
   * (spec 2026-09-28-hooks-reach-the-log §3).
   */
  private final LogStages logStages;

  public ConversationController(
      ConversationStore conversations,
      CompactionStore compactions,
      TurnStore turns,
      EntryStore entries,
      JobRuntime runtime,
      Turn speaking,
      ObjectProvider<AgentRegistry> agents,
      ConversationsProperties properties,
      Compaction compaction,
      Tokenizer tokenizer,
      Conversations rules,
      LogStages logStages,
      CallerAccess access) {
    this.access = access;
    this.conversations = conversations;
    this.compactions = compactions;
    this.turns = turns;
    this.entries = entries;
    this.runtime = runtime;
    this.speaking = speaking;
    this.agents = agents;
    this.properties = properties;
    this.compaction = compaction;
    this.tokenizer = tokenizer;
    this.rules = rules;
    this.logStages = logStages;
  }

  /**
   * {@code POST /v1/conversations} — open one, and answer with the id to speak into.
   *
   * <p>200 and not 201, matching every other write in this server: {@code POST /v1/memories} mints
   * a memory and answers 200, and a single endpoint answering 201 would make a client branch on a
   * status that means the same thing here as the one beside it.
   *
   * <p><b>The allowance is optional and the default is an operator's.</b> This paragraph said the
   * opposite — "required, and there is no configured default", because "a number invented in a
   * property file would be folklore" — and the half of that which was right is kept: nothing here
   * computes an allowance, because how much a person is going to say has no arithmetic behind it,
   * and the refusal was correct about a <em>server</em> picking a number for a caller. What it was
   * wrong about is who the refusal reached. A console cannot open a conversation without asking a
   * person for a model-call count first, and that is a question with no method behind it either —
   * the owner's judgement on the field, recorded, is that "it should just inherit that from the
   * system". {@link ConversationsProperties} is where an operator says it once, for their own box,
   * beside every other cost bound this server takes. A body that names {@code maxModelCalls} is
   * still honoured exactly as before.
   *
   * <p><b>The turn cap is optional and the asymmetry is the point.</b> Every agent's file already
   * names a number for it, so there is a level above this one with a real answer; there is no such
   * level for the budget, which is why that one has to be asked for. A conversation that names a
   * cap is saying something about every turn in it, and one that sends {@code noTurnCap} is saying
   * its turns are bounded by cost rather than by count.
   *
   * <p><b>The budget may say the same thing about itself, and the deployment default is unmoved by
   * it.</b> {@code noBudget} is the one caller opting out of a ceiling entirely, per conversation;
   * {@code allowance} refuses a body that names it beside {@code maxModelCalls}, and an operator's
   * {@code default-budget} goes on answering every body that names neither.
   */
  @PostMapping("/v1/conversations")
  public ResponseEntity<ConversationView> open(
      @RequestBody OpenConversationRequest request,
      @RequestAttribute(name = AuthFilter.HANDLE_ATTRIBUTE, required = false) String handle) {
    // The middle of the three levels a turn cap can be decided at, and the
    // one that outlives a run. Null for a conversation that decides nothing,
    // which is the ordinary shape and is not the same as one that decided
    // there is to be no cap.
    TurnCap turnCap =
        RequestedTurnCap.in(
            request.maxTurns(), request.noTurnCap(), "each turn in this conversation");
    Budget budget =
        RequestedBudget.in(
            request.maxModelCalls(), request.noBudget(), properties.getDefaultBudget());
    // The signed-in account owns it: a hook's notify reaches them (amendment 2).
    ConversationRecord opened =
        conversations.open(
            personal == null
                ? RequestedHome.in(request.project())
                : personal.home(request.project(), handle),
            budget,
            turnCap,
            handle);
    // log.open: the row is committed and no turn has started (spec 2026-09-28 §3).
    logStages.opened(LogStages.LogOpened.ofConversation(opened));
    return ResponseEntity.ok(ConversationView.of(opened));
  }

  /**
   * {@code GET /v1/conversations?project=} — what is open in one tier.
   *
   * <p>One tier at a time, like every other read in this archive: a person resuming one project's
   * conversation must not be offered another project's. <b>An omitted {@code project} is the global
   * tier and not "all tiers"</b> — {@link Home} refuses to let global be a name a project could
   * take, so a listing that spanned tiers would have no home to put on each row, and two
   * conversations opened in two projects would be indistinguishable in it. It is the same reading
   * {@link #open} gives the same absent field, which is what keeps a console from having to know
   * that one verb's silence means something different from the other's.
   *
   * <p><b>A person's conversations and not every conversation in the tier</b>, which is a filter
   * this endpoint did not need until every run got one. Before V17 the only writer of this table
   * was {@link #open}, so a tier's rows and a person's rows were the same set; a delegated child, a
   * curator's ruling and a submission are all conversations now, and a curator pass over a few
   * hundred memories is a few hundred of them. Without the filter the screen a person opens on
   * would be a machine's log with their own conversations somewhere in it.
   *
   * <p><b>Filtered on {@code origin} and not on "has no parent"</b>, and the difference is the
   * whole reason {@code origin} is a column. A submission is parentless too and is not a person's
   * conversation — nobody opened it, and nobody is going to speak into it — so a parent-based
   * filter would leave every one-shot in the listing. {@code ConversationStore.inHome} carries the
   * {@code WHERE} and {@code Origin} carries the argument.
   *
   * <p>Ordered by {@code ConversationStore.inHome}, oldest first, and not paged. The bound is a
   * person's own talking: a tier holds as many conversations as somebody opened, and a limit here
   * would be a number invented rather than measured. That bound is exactly what the filter
   * preserves — it was true of a table only people wrote to and would not have survived one every
   * run writes to.
   */
  @GetMapping("/v1/conversations")
  public ResponseEntity<List<ConversationView>> list(
      @RequestParam(required = false) String project,
      @RequestParam(required = false) String lifecycle) {
    return ResponseEntity.ok(
        conversations.inHome(RequestedHome.in(project), RequestedLifecycle.in(lifecycle)).stream()
            .map(ConversationView::of)
            .toList());
  }

  /**
   * {@code PUT /v1/conversations/{id}/lifecycle} — archive one, put it back, mark it to go, or
   * cancel the mark.
   *
   * <h2>One verb and not four</h2>
   *
   * <p>{@code /archive}, {@code /unarchive}, {@code /mark} and {@code /cancel} would be four
   * endpoints over one column, each having to state the same table of what may follow what, and a
   * fifth the day a state is added. <b>The transition table has one home</b> — {@code
   * ConversationLifecycle.reachedFrom}, which {@code ConversationStore.moveTo} splices into its own
   * WHERE — and this verb names a destination and lets that decide. A caller reading the refusal is
   * told which states may become the one they asked for, which is the table itself rather than a
   * paraphrase of it.
   *
   * <p>{@code PUT} because it is a state a caller is putting the conversation into and asking twice
   * is asking once: moving an already-archived conversation to {@code archived} is refused rather
   * than silently accepted, which is the one place this parts company with idempotence — and it
   * parts company on purpose, because "somebody else archived this a moment ago" and "you archived
   * this" are different facts and only one of them is a no-op worth reporting as success.
   *
   * <h2>What it refuses and where</h2>
   *
   * <p>A state nothing spells is a 400 here. An id nothing opened is a 404, a delegated child is a
   * 409 and a move the table does not allow is a 409, and all three come from {@code
   * ConversationStore.moveTo} — because all three are facts about a row rather than about the
   * request, and the sentences that explain them belong beside the transition table they come from.
   *
   * <p><b>{@link Origin#TURN} is not required and that is deliberate.</b> A person marking their
   * own conversation is the ordinary use, but an operator marking a machine's log by hand — a
   * curator trace they know they are done with, on a box where no retention age is set — is the
   * same act on the same column, and refusing it would make the manual path narrower than the
   * automatic one for no reason anybody could give.
   */
  @PutMapping("/v1/conversations/{id}/lifecycle")
  public ResponseEntity<LifecycleView> lifecycle(
      @PathVariable String id, @RequestBody LifecycleRequest request) {
    requireInformation(id);
    ConversationRecord moved = conversations.moveTo(id, RequestedLifecycle.in(request.lifecycle()));
    // A person's conversation closes on its first move out of ACTIVE (spec §3, log.close).
    logStages.moved(moved);
    return ResponseEntity.ok(LifecycleView.of(moved));
  }

  /**
   * {@code GET /v1/conversations/{id}/turns} — everything said in one conversation, in the order it
   * was said.
   *
   * <p><b>Why a console needs this and the REPL did not.</b> A terminal keeps its own scrollback:
   * it typed every utterance, watched every lifecycle event and read every answer off {@code GET
   * /v1/jobs/&#123;id&#125;}, so its transcript is the conversation verbatim and asking the server
   * to describe one back to it would be asking for what it already has. <b>A browser tab that is
   * reloaded has none of that.</b> Without this endpoint a refresh is amnesia — the conversation
   * goes on being correct on the server, with its whole history in the next turn's prompt, while
   * the person is shown an empty one. That is the worst of both: they cannot see what was said, and
   * the model can.
   *
   * <p><b>An id nothing opened is a 404 and not an empty history</b>, for the reason {@link
   * #compactions} looks its row up: {@code TurnStore.forConversation} answers the empty list both
   * for a conversation nobody has spoken into and for one that was never opened, and those are
   * opposite facts. Here the distinction is what the endpoint is for — a tab restored with an id
   * the archive no longer holds would render a blank conversation that looks resumable, and the
   * refusal would arrive from {@code Turn.speak} one utterance later, after the person had written
   * it.
   *
   * <p>Answers the turns and not the seams. {@link #compactions} answers those, and a console
   * renders a folded history by asking both: a fold is a fact <em>about</em> the history rather
   * than an entry in it, and every turn a seam stands for is still here at its own ordinal with its
   * own text.
   */
  @GetMapping("/v1/conversations/{id}/turns")
  public ResponseEntity<List<TurnView>> turns(@PathVariable String id) {
    requireInformation(id);
    // The rule and not a read, exactly as in compactions below: nothing in
    // the answer comes from that row. Conversations.requireExistsOrThereIsNo
    // takes the whole trailing phrase because this sentence and compactions'
    // are not "<noun> to read", which is the only shape the older
    // requireExists could build and the reason these two stayed inline until
    // it had a wider door. ConversationTurnsHandler asks the same way.
    rules.requireExistsOrThereIsNo(id, "history to read back");
    return ResponseEntity.ok(turns.forConversation(id).stream().map(TurnView::of).toList());
  }

  /**
   * {@code GET /v1/conversations/{id}/compactions} — every seam this conversation has had, oldest
   * first.
   *
   * <p>An empty list is the ordinary answer and says something: this conversation has never been
   * folded. <b>A conversation nothing opened is a 404 and not an empty list</b>, which is the whole
   * reason the row is looked up before the seams are read — {@code CompactionStore.forConversation}
   * cannot tell the two apart, and answering {@code []} to an id nobody minted would tell a caller
   * that a conversation it invented has a clean history. {@link #turns} looks its row up for the
   * same reason and says so.
   *
   * <p>The list is not paged and there is no {@code since} parameter. A conversation folds when its
   * history outgrows a context window, which is a handful of times across a long conversation and
   * not a stream; a caller that wants only the newest compares the last element's {@code
   * throughOrdinal} with the one it saw before, which is what the REPL does after each turn.
   */
  @GetMapping("/v1/conversations/{id}/compactions")
  public ResponseEntity<List<CompactionView>> compactions(@PathVariable String id) {
    requireInformation(id);
    // The rule and not a read: nothing in the answer comes from that row. It
    // is here so that "no such conversation" and "nothing was ever folded"
    // reach the caller as different statuses rather than as the same empty
    // list. The phrase is passed whole for the reason turns above gives.
    rules.requireExistsOrThereIsNo(id, "transcript to read the seams of");
    return ResponseEntity.ok(
        compactions.forConversation(id).stream().map(CompactionView::of).toList());
  }

  /**
   * {@code GET /v1/conversations/{id}/chat} — what the model is shown, one page at a time.
   *
   * <h2>The half of the pair a client renders as the conversation</h2>
   *
   * <p>{@code EntryStore.pageOfProjection} is the read, which is {@code thatProjectFor}'s question
   * bounded: superseded rows gone, kinds that carry no role gone, in the order a model reads them.
   * <b>The distinction between this and {@link #trajectory} was already load-bearing in the code
   * and had no way out of it</b> — those two store methods are the whole of what a chat and a
   * trajectory are, and until now the only caller of either was the turn loop.
   *
   * <p><b>Not the same as {@link #turns}, and both are wanted.</b> A turn is what a person said and
   * what the run came to, which is the shape a history screen renders; this is every message in
   * between, including the assistant turns that were entirely tool calls and the results that
   * answered them. The first is a conversation as a person had it and the second is a conversation
   * as the model reads it, and a client showing a fold's effect needs this one: the summary entry
   * is here, in the place the turns it stands for used to be.
   *
   * <p><b>It is not byte-for-byte what a request contained</b>, which is worth saying rather than
   * leaving to be discovered. {@code Compaction.whatWasSaidAndWhatCameBack} substitutes a reference
   * line for an older turn's tool result one layer above this read, so a request carries a line
   * where this page carries the result. That is the more useful of the two answers here — a person
   * checking what a reference stood for wants the result — and the reference line is
   * reconstructible from the page, since the tool, the size and the handle are all on the row.
   */
  @GetMapping("/v1/conversations/{id}/chat")
  public ResponseEntity<EntryPageView> chat(
      @PathVariable String id,
      @RequestParam(required = false) Integer offset,
      @RequestParam(required = false) Integer limit) {
    requireInformation(id);

    RequestedWindow window = RequestedWindow.in(offset, limit);
    rules.requireExists(id, "chat");
    return ResponseEntity.ok(
        EntryPageView.of(
            entries.pageOfProjection(id, window.skip(), window.most()),
            window.skip(),
            window.most()));
  }

  /**
   * {@code GET /v1/conversations/{id}/trajectory} — everything that happened, one page at a time.
   *
   * <h2>Everything this system measures and nothing could read back</h2>
   *
   * <p>{@code EntryStore.pageOfLog} is the read, which is {@code forConversation}'s question
   * bounded — and that method's own javadoc says what this endpoint changes: "nothing in production
   * reads the whole log today", its callers being the tests that assert what a fold did to it. So
   * every fact on this page is one the server already wrote down and nobody could ask for: which
   * entries a fold covered and which summary covered them, the {@code diagnostic} rows the
   * machinery leaves about a conversation, the {@code attempt_failed} that is how a stopped run is
   * legible at all, the handles, and the timings.
   *
   * <p><b>The timings are why this is not merely the chat with more rows.</b> {@code recorded_at}
   * and {@code took_ms} exist on every entry and had no reader; a run could be watched and could
   * not be reviewed. They are on the chat's rows too — the same column — and the reason they belong
   * to this endpoint's argument is that the entries whose timing is interesting are mostly the ones
   * the chat drops.
   *
   * <p>A conversation with nothing in it answers with an empty page and a total of zero, which is a
   * true and ordinary state; {@code Conversations.requireExists} is what keeps it from being
   * confused with an id nothing opened.
   *
   * <p>{@code after} reads only what was written after that ordinal — what a client that has shown
   * the log through it has not seen — and every page says how far the log reaches in {@code
   * through}.
   *
   * <p>{@code before}, or {@code tail=true} for the log's end, reads backwards instead: newest
   * first, and the page says in {@code oldest} and {@code more} where to go back from and whether
   * there is anywhere to go. {@code kinds} narrows either reading — the rows and the total alike —
   * to the kinds a client draws, so a chat's tail is forty things it shows rather than forty rows
   * it mostly hides. {@code drawn=true} goes the rest of the way: the answers that asked for tools
   * are answers no chat draws, and are left out of the rows and the counts too.
   */
  @GetMapping("/v1/conversations/{id}/trajectory")
  public ResponseEntity<EntryPageView> trajectory(
      @PathVariable String id,
      @RequestParam(required = false) Integer offset,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) Integer after,
      @RequestParam(required = false) Integer before,
      @RequestParam(required = false) Boolean tail,
      @RequestParam(required = false) List<String> kinds,
      @RequestParam(required = false) Boolean drawn) {
    requireInformation(id);

    RequestedWindow window = RequestedWindow.in(offset, limit);
    int from = RequestedAfter.in(after);
    int below = RequestedBefore.in(before, tail, after);
    Set<EntryKind> narrowed = RequestedKinds.in(kinds);
    boolean onlyDrawn = Boolean.TRUE.equals(drawn);
    rules.requireExists(id, "trajectory");
    return ResponseEntity.ok(
        EntryPageView.of(
            below != RequestedBefore.FORWARD
                ? entries.pageOfLogBefore(
                    id, below, narrowed, onlyDrawn, window.skip(), window.most())
                : from == 0 && narrowed.equals(EntryStore.EVERY_KIND) && !onlyDrawn
                    ? entries.pageOfLog(id, window.skip(), window.most())
                    : entries.pageOfLogAfter(
                        id, from, narrowed, onlyDrawn, window.skip(), window.most()),
            window.skip(),
            window.most()));
  }

  /**
   * {@code GET /v1/conversations/{id}/context?agent=} — what this conversation's prompt costs, and
   * every part of it that cannot honestly be priced.
   *
   * <h2>Why this is server-side and could not be anything else</h2>
   *
   * <p><b>A token count exists in exactly one place and never reaches a client.</b> It is {@code
   * usage.prompt_tokens} on a completion, read by {@code Transcript.promptMeasured} and persisted
   * on the turn; a browser counting the same prompt would be counting characters and calling them
   * tokens. That is the whole reason this is an endpoint and not a renderer's arithmetic, and it is
   * the same reason the split below is refused rather than approximated.
   *
   * <p>{@link ContextView} carries the argument for each absence and the evidence behind it. The
   * short version is that there is no tokenizer on this box, that separating the parts would take a
   * model call whose cost is unbounded and whose prefix would evict the one the endpoint is
   * reusing, and that <b>nothing records which agent answered a turn</b> — so even an exact
   * per-agent measurement could not be subtracted from this conversation's total without a guess.
   *
   * <h2>The agent is a parameter because the conversation has none</h2>
   *
   * <p>This class's own javadoc has said since conversations existed that a conversation names no
   * agent: it is chosen per turn, which is what lets one conversation hold two agents' work. {@link
   * #resume} already pays that price by taking an agent in its body, and this read pays it the same
   * way. <b>Omitting it is the ordinary case and answers without a {@code prefix}</b> rather than
   * refusing, because what the conversation itself measured is worth having on its own.
   *
   * <p>An agent nothing is called is a 400 naming the agents that exist, on {@code
   * AgentController.run}'s reasoning: the caller is choosing from a list and the list is the
   * correction.
   */
  @GetMapping("/v1/conversations/{id}/context")
  public ResponseEntity<ContextView> context(
      @PathVariable String id, @RequestParam(required = false) String agent) {
    requireInformation(id);

    rules.requireExists(id, "context");
    List<TurnRecord> spoken = turns.forConversation(id);
    String priced = agent != null ? agent : rules.whoAnswered(id, spoken);
    return ResponseEntity.ok(ContextView.of(spoken, priced == null ? null : priced(id, priced)));
  }

  /**
   * {@code GET /v1/conversations/{id}/projection?agent=&turn=} — the exact message list this
   * conversation's next prompt would carry, or the one a turn it has already had was shown.
   *
   * <h2>The history is derived; the system block is the one thing that is not</h2>
   *
   * <p><b>No entry kind holds the system prompt.</b> {@code entries_kind_is_known} is closed over
   * eight kinds and none of them is one — the agent's own prompt is assembled at request time from
   * the definition and is never logged, which {@link EntryKind}'s own javadoc says in as many
   * words. So the history is computed with {@code Compaction.projectionFor}: the same log {@link
   * #chat} already projects, with the agent's prompt hoisted to the front exactly as {@code
   * JobRuntime.oneSystemMessageFirst} does for a real turn — the identical arrangement a turn opens
   * with, assembled by the identical code.
   *
   * <p><b>That everything here is derived was true of this route and is no longer, and the change
   * is the point of {@code V32__turn_system_prompt.sql}.</b> This javadoc used to argue that
   * storing the prompt "would have been a migration for a fact this server already has everything
   * it needs to compute", and the argument was wrong in exactly one place: the definition is a file
   * an operator edits, so computing it later answers a question about today under a turn number
   * from last week. A turn now records the block it went out with, and the section below says what
   * this route does with that.
   *
   * <h2>It must never send, and that is the rule this route exists to keep</h2>
   *
   * <p><b>This endpoint makes no request to a model, on any path.</b> The obvious alternative —
   * send the assembled prompt and let the endpoint's own usage answer with its size — costs exactly
   * what {@link ContextView} already refuses to pay to split {@link ContextView#sent}: it would
   * evict the prompt prefix the next real turn is reusing, and it would buy a generation of
   * unbounded length for what is supposed to be a read. Computing the projection needs no model at
   * all, which is cheaper than the rejected alternative and not merely safer than it.
   *
   * <h2>The agent, on {@link #context}'s own terms</h2>
   *
   * <p>A conversation names no agent of its own — this class's own javadoc has said so since
   * conversations existed — so assembling a system message needs one, and asking which one is the
   * question {@link #context} already asks to price a prefix. <b>This mirrors that endpoint rather
   * than inventing a second convention</b>: an omitted {@code agent} resolves through {@code
   * Conversations.whoAnswered}, and a named agent nothing is called is a 400 naming the agents that
   * exist, from {@link RequestedAgent}. The one case {@link #context} does not have to face and
   * this does: a person's conversation with no turn yet and no agent named answers {@link #context}
   * with no prefix, which that view can hold; a projection with no agent has nothing to build a
   * system message out of, so that case is refused here rather than answered with an empty one.
   *
   * <h2>{@code turn}, and what "as of" buys</h2>
   *
   * <p><b>Omitted, this answers the next prompt — which is what it has always answered and goes on
   * answering unchanged.</b> Named, it answers what that turn opened with, through {@code
   * Compaction.projectionAsOf}. The difference is not a bound on the same read: a fold is recorded
   * on the rows it covered with no note of when it ran, and a summary's turn number is the span it
   * stands for rather than the moment it was written, so neither today's fold state nor a
   * turn-number comparison can say what a past turn saw. {@code EntryStore.thatProjectedAt} is
   * where that is argued and where the predicate that avoids it lives.
   *
   * <p><b>The list ends at that turn's utterance.</b> What the turn went on to answer, and the
   * results it collected on the way, are not in it — they are what the turn produced rather than
   * what it was shown, and a reader comparing a prompt against an answer needs the two kept apart.
   * The trajectory read is where the rest of the turn is.
   *
   * <p><b>A turn the conversation never reached is refused, not clamped.</b> Asking for turn 40 of
   * a conversation that had 12 is a question with no answer, and answering the nearest one would
   * hand a reader a record of a turn that never happened, on the screen they came to precisely
   * because they did not want to be told a plausible story. So the refusal names the turns there
   * are, which is the correction — {@code RequestedAgent}'s reasoning for naming the agents that
   * exist, and {@link #context}'s for refusing an agent nothing is called.
   *
   * <p><b>The turn is checked before the agent is resolved</b>, because whether a turn happened is
   * a fact about the conversation alone: a caller who asked for a turn that does not exist should
   * be told that, and not be told first about an agent whose prompt would have gone over a turn
   * there is no history for.
   *
   * <h2>The system block, and the field that says which one this is</h2>
   *
   * <p><b>A turn records the block it went out with, and this answers with that one when it has
   * it.</b> {@code turns.system_block} — {@code V32__turn_system_prompt.sql} — holds the agent's
   * prompt as it stood when the turn ran, content-addressed so a conversation does not carry a
   * megabyte of the same 5 kB. On the as-of-turn path this route answers with the recorded block,
   * so a projection of a turn from last week opens with last week's prompt however many times the
   * file has been edited since.
   *
   * <p><b>And when the turn recorded none, the answer says so rather than looking the same.</b>
   * Every turn written before V32 has no block, and nothing can recover one — the migration argues
   * at length why a backfill from today's files was refused as the very assertion this route exists
   * to stop making. Those turns are answered with the definition as it stands now, with {@link
   * ProjectionView#systemBlockAsSent} false, which is what the console draws its per-turn caveat
   * from. The <em>rows</em> under it are the ones that turn could see either way, so the flag
   * narrows what is uncertain about the block to the block rather than letting it hang over the
   * whole answer — with the tools' own gap below, which no flag on this response covers.
   *
   * <h2>What this is not</h2>
   *
   * <p><b>With no {@code turn}, not a record of anything that was sent.</b> The message list is
   * computed from the definition as it stands right now and from the log as it stands right now, so
   * it answers "what would this conversation's next prompt be" — a question about a request nobody
   * has made. {@link ProjectionView#systemBlockAsSent} is false on that path as a statement rather
   * than a default, and {@link ProjectionView#turn} is null, so a saved next-prompt answer and a
   * saved turn answer are told apart on the response and not only on the request that produced
   * them.
   *
   * <p><b>With a {@code turn}, not byte-identical to what was sent, and what is pinned is worth
   * naming exactly.</b> Pinned: the <em>rows</em> — {@code EntryStore.thatProjectedAt} reconstructs
   * precisely the entries that turn could see — and, for a turn that recorded one, the system
   * block. Not pinned: the agent's {@code tools:}, which nothing records per turn. {@link
   * #context}'s own {@code agent} parameter exists for the same distinction, and {@link
   * ProjectionView#agent} carries the answer to "whose prompt is this" so a reader is never left
   * guessing.
   *
   * <p><b>The tool schemas are not versioned and this does not claim they are</b> — deliberately,
   * since versioning them is a change to the write path and not to this read. This route puts no
   * tools on the wire, so the answer asserts nothing about the schemas themselves; what it cannot
   * avoid asserting is <em>two things the tool list decides about the history</em>, and both follow
   * the file as it stands at the moment of the read rather than as it stood at the turn:
   *
   * <ul>
   *   <li><b>the wording of a seam.</b> {@code Compaction.seam} is chosen from {@code canList}, so
   *       an agent that has lost {@code result_list} since renders the plain seam where the turn
   *       was sent the one naming the tool that lists stored results;
   *   <li><b>whether an older tool result is shown in full.</b> {@code
   *       Compaction.whatWasSaidAndWhatCameBack} is chosen from {@code canRedeem}, so an agent that
   *       has gained {@code result_read} since replaces results the turn read in full with
   *       reference lines.
   * </ul>
   *
   * <p>Both are the same class of error as the prompt file V32 closed — a file today is a fact
   * about today — one field over, and this route states the gap rather than pretending the tool
   * list is out of the picture because the schemas are.
   */
  @GetMapping("/v1/conversations/{id}/projection")
  public ResponseEntity<ProjectionView> projection(
      @PathVariable String id,
      @RequestParam(required = false) String agent,
      @RequestParam(required = false) Integer turn) {
    requireInformation(id);

    rules.requireExists(id, "projection");
    List<TurnRecord> spoken = turns.forConversation(id);
    if (turn != null) {
      rules.aTurnThatHappened(id, spoken, turn);
    }
    String named = rules.whoToProjectAs(id, agent, spoken);
    AgentDefinition definition = RequestedAgent.toRead(agents, named);
    if (turn == null)
      definition =
          runtime.withAgentRules(definition, conversations.find(id).orElseThrow().home(), null, id);
    // Two calls and two view factories rather than one of each with a
    // nullable turn threaded through, because the two answers differ in what
    // they can assert: only a past turn can have been sent a block, so only
    // the second has a `systemBlockAsSent` that can be true. Collapsing them
    // would put that decision behind a ternary in a view constructor, which
    // is where a false would quietly become a default.
    return ResponseEntity.ok(
        turn == null
            ? ProjectionView.of(named, compaction.projectionFor(id, definition))
            : ProjectionView.asOf(named, turn, compaction.projectionAsOf(id, definition, turn)));
  }

  /**
   * What one named agent's fixed block costs, in characters of the JSON a request carries.
   *
   * <p>{@code JobRuntime.schemasOfferedTo} is what answers, and it is the production assembly
   * rather than a second reading of the {@code tools:} line: two of the tools built per run have a
   * schema that depends on something — {@code agent_run}'s description names what this definition
   * may delegate to — so a list assembled here would describe different tools from the ones the
   * model is shown.
   */
  private ContextView.Prefix priced(String conversation, String agent) {
    AgentDefinition definition = RequestedAgent.toRead(agents, agent);
    definition =
        runtime.withAgentRules(
            definition, conversations.find(conversation).orElseThrow().home(), null, conversation);
    return ContextView.Prefix.of(
        definition,
        runtime.schemasOfferedTo(definition, conversations.find(conversation).orElseThrow().home()),
        tokenizer,
        compaction.contextLengthOf(definition));
  }

  /**
   * {@code GET /v1/entries/search?q=&amp;project=} — where in one tier's conversations something
   * was said.
   *
   * <h2>The question the log could not be asked</h2>
   *
   * <p>Every other read of {@code entries} on this controller begins by naming a conversation, so
   * until now the log could only answer questions about a conversation somebody had already
   * identified. {@code entries.content} carried no index of any kind before {@code V23}; {@code
   * implementation rationale} §3.3 is where that gap is recorded, and this is the door onto the
   * column that closes it.
   *
   * <h2>Why the path is not under a conversation</h2>
   *
   * <p><b>The scope is a tier and the measurement is why.</b> Against 200 000 entries in 501
   * conversations, a search of a whole tier is a bitmap scan on {@code entries_by_text}; the same
   * search narrowed to one conversation is served by the primary key alone, because one
   * conversation is a few hundred rows and there is nothing for a GIN scan to improve on. A {@code
   * /v1/conversations/&#123;id&#125;/search} would therefore be a read that never touched the index
   * built for it, answering a question {@link #trajectory} already answers by paging. {@code
   * EntryStore.search} and {@code V23} carry the numbers.
   *
   * <p>The tier itself is {@code project}, resolved exactly as {@link #list} resolves it: omitted
   * is the global tier and not "everywhere", because a search that crossed the boundary would be
   * the one read in this server that mixed a project's conversations with the global tier's, in the
   * place it would be least visible.
   *
   * <h2>What the answer says about what it could not read</h2>
   *
   * <p>{@link LogSearchView#reach} travels with every answer. An ejected payload cannot be a hit —
   * the bytes are gone, so there is nothing to match on and nothing to rank — and a search that
   * simply omitted those rows would be the confident-empty answer this project keeps deleting.
   * {@code DocumentController.search} sends {@code searchable}/{@code unsearchable} for the same
   * reason; a log needs three numbers rather than two, because the kinds that never reach a model
   * are a third way to be out of reach.
   *
   * @param q the question in prose. <b>Required and not blank</b>: the empty string is what an
   *     unset field arrives as, and a search that read it as "no filter" would answer with a page
   *     of the whole tier under a question nobody asked
   */
  @GetMapping("/v1/entries/search")
  public ResponseEntity<LogSearchView> searchEntries(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String project,
      @RequestParam(required = false) Integer offset,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String mode,
      @RequestParam(required = false) String snapshot) {
    if (mode == null && snapshot == null) return searchEntries(q, project, offset, limit);
    String question = RequestedLogQuestion.in(q);
    RequestedWindow window = RequestedWindow.in(offset, limit);
    return ResponseEntity.ok(
        entryReads()
            .searchView(
                RequestedHome.in(project), question, window.skip(), window.most(), mode, snapshot));
  }

  public ResponseEntity<LogSearchView> searchEntries(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String project,
      @RequestParam(required = false) Integer offset,
      @RequestParam(required = false) Integer limit) {

    String question = RequestedLogQuestion.in(q);
    RequestedWindow window = RequestedWindow.in(offset, limit);
    return ResponseEntity.ok(
        LogSearchView.of(
            entryReads().search(RequestedHome.in(project), question, window.skip(), window.most()),
            window.skip(),
            window.most()));
  }

  /**
   * {@code POST /v1/conversations/{id}/resume} — continue a run that stopped for want of allowance,
   * and answer with the new job.
   *
   * <h2>Why a verb of its own, beside a door that already moves limits</h2>
   *
   * <p>{@code POST /v1/jobs/&#123;id&#125;/limits} <b>cannot be used and should not be</b>. It
   * refuses a finished job deliberately, and its reasoning is right: "raising the ceiling on one is
   * asking for something that cannot happen, and answering 200 would say it had". This is a
   * different verb. It does not move a limit on a live run; it starts a new run continuing a
   * stopped one, and it is the second thing in this server that begins a turn.
   *
   * <p><b>The conversation is the subject because the job is gone.</b> A job is a handle in one
   * process's map and a conversation is a row that outlives every job in it, so the thing a person
   * is looking at a week later — and the thing the archive can still be asked about — is the
   * conversation. That is the same reason this controller exists at all.
   *
   * <p><b>202 and the job, like every other endpoint that starts a run.</b> What a caller wants
   * next is the handle to poll at {@code GET /v1/jobs/&#123;id&#125;} and to stop at {@code POST
   * /v1/jobs/&#123;id&#125;/cancel} — a continued run is a job in every way that matters to a
   * caller.
   *
   * <h2>What it refuses, and where each refusal is decided</h2>
   *
   * <p>The body's own mistakes are answered here — an agent nothing is called, a turn cap said two
   * ways, a session that names nothing — because they are things the caller can correct from the
   * message. <b>Which endings a grant continues is not a fact about the request</b>, and {@code
   * Turn.resume} decides it: an ending is a property of the conversation's last turn, the refusal
   * is a 409 about a row that is there, and the table of which is which belongs beside the code
   * that continues one.
   *
   * <p>A budget that cannot record what has already been spent comes back from that layer as a
   * {@link io.aeyer.plowshare.server.faults.CallerFault} and is answered 400, exactly as {@code
   * Budget.of}'s and {@code TurnCap.of}'s refusals are everywhere else in this package: a number
   * the caller can correct is a message at the door, not a conflict about the row.
   *
   * <p><b>So does an allowance with no ceiling for that number to raise.</b> {@code
   * Budget.changeTo} refuses a lifted budget with {@code IllegalStateException} — a sentence
   * written for the operator granting more calls to a run that was never going to run out — and
   * {@code Turn.grant} converts it at the call itself, for the reason set out there: catching
   * {@code IllegalStateException} around the whole of {@code Turn.resume} would answer 400 for
   * every failure underneath it, including ones this caller cannot do anything about.
   *
   * <p><b>Neither is caught here, and that is the change.</b> Both used to arrive as {@code
   * IllegalArgumentException} and be restated as this surface's own {@code BadRequestException},
   * which is a status decided in a controller for a refusal the domain had already made. {@code
   * Turn.grant} now raises {@code CallerFault}, {@code Faults} maps it to the same 400 on whichever
   * surface asked, and a frame handler calling {@code Turn.resume} gets that answer without this
   * method existing — where the old type had no row in that table at all and would have been
   * answered 500.
   */
  @PostMapping("/v1/conversations/{id}/resume")
  public ResponseEntity<StartedJob> resume(
      @PathVariable String id,
      @RequestBody ResumeRunRequest request,
      @RequestAttribute(name = AuthFilter.HANDLE_ATTRIBUTE, required = false) String handle) {
    requireInformation(id);

    AgentDefinition definition =
        RequestedAgent.toRun(agents, rules.whoToContinueAs(id, request.agent()));
    // The narrowest of the three levels, exactly as an utterance says it.
    // Null for a body that decides nothing, which leaves the conversation's
    // answer, or the agent's when the conversation has none -- and a new run
    // counts its turns from zero, so that is a full grant again.
    TurnCap turnCap = RequestedTurnCap.in(request.maxTurns(), request.noTurnCap(), "this run");
    String session = RequestedSession.in(request.session());
    access.requireSession(session, handle);
    var home = speaking.homeOf(id);
    if (home.isGlobal())
      throw new io.aeyer.plowshare.server.faults.CallerFault(
          "Global holds shared setup and resources; conversations run in Personal or a project");
    access.requireWork(home.project(), handle);
    String job = speaking.resume(id, definition, session, turnCap, request.maxModelCalls());
    return ResponseEntity.accepted().body(new StartedJob(job, definition.name()));
  }
}
