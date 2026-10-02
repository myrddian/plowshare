package io.aeyer.plowshare.server.ws;

import java.util.regex.Pattern;

/**
 * The shape a frame's {@code type} must have: a dotted discriminator, such as
 * {@code "conversation.turns"} — spec §3.1's own example, and the shape
 * {@link FrameRouter}'s registration map is keyed by.
 *
 * <h2>Why this exists before any real type is registered</h2>
 *
 * <p>Nothing about {@code Map<String, FrameHandler>} stops a {@code
 * @Configuration} class from registering {@code "conversationTurns"} or
 * {@code "conversation_turns"} — both compile, both route perfectly well, and
 * neither is the wire namespace the spec promises a client. {@link
 * #requireWellFormed(String)} is where that drift is refused at wiring time
 * instead of shipped and discovered later by a client parsing a dot out of a
 * string that does not have one. {@link FrameRouter}'s constructor calls it
 * once per registered type, so a typo fails the boot rather than fails a
 * request.
 *
 * <h2>Where the actual names live</h2>
 *
 * <p>Here, on {@code FileRequest.op}'s precedent (ROOTS, READ, GLOB, …): beside
 * the validator that already knows what shape they must have, rather than as
 * string literals scattered across a {@code @Configuration} class, its tests,
 * and {@code Capabilities}. {@link #PROJECT_DEFINE} is the first, and the
 * breadth plan's remaining types belong beside it.
 *
 * <p>{@link #CONVERSATION_TURNS} was reported here as blocked rather than
 * written, and the block is worth keeping a record of because it is the shape
 * the breadth plan will meet again: {@code ConversationController.turns} made
 * its existence check inline, in a sentence {@code Conversations.requireExists}
 * could not build, so a frame calling the service would have refused the same
 * request in different words. <b>The answer was to widen the service and not to
 * copy the sentence</b> — {@code Conversations.requireExistsOrThereIsNo} takes
 * the whole trailing phrase now, both readings ask through it, and the
 * constant below names a handler that really exists.
 */
public final class FrameTypes {

    private static final Pattern DOTTED = Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+$");

    /**
     * The {@code type} a response carries when the request's own type could not
     * be read — a frame that was not JSON at all, or one whose {@code type} was
     * missing or was not a string.
     *
     * <h2>Why a response needs a type it was not asked under</h2>
     *
     * <p>A response is an {@link io.aeyer.plowshare.protocol.frames.Envelope}
     * like any other frame, and {@code Envelope}'s own compact constructor
     * refuses to exist without a {@code type} — deliberately, because a frame
     * with no destination routes nowhere. A response to a frame this server
     * could not read has no type to echo, so it needs one of its own, and this
     * is it.
     *
     * <p><b>Nothing routes on it, and nothing may register it.</b> It travels
     * server-to-client only. It is shaped like a real discriminator — {@link
     * #requireWellFormed(String)} accepts it — so a client parsing a dot out of
     * every {@code type} it sees does not meet its one exception here; that is
     * the whole of why it is not something like {@code "?"}.
     */
    public static final String REFUSED = "frame.refused";

    /**
     * Name a project's workspace and its exclusions, replacing whatever it had
     * — {@code POST /v1/projects}' own verb, and the first type this surface
     * really answers. {@link ProjectDefineHandler} is what claims it.
     */
    public static final String PROJECT_DEFINE = "project.define";

    /**
     * Every leash that has been set — {@code GET /v1/projects}, which is what
     * the console's projects screen opens on and what no MCP tool reaches at
     * all. {@link ProjectListHandler} is what claims it.
     */
    public static final String PROJECT_LIST = "project.list";
    public static final String PROJECT_MEMBER_ADD = "project.member.add";
    public static final String PROJECT_MEMBER_REMOVE = "project.member.remove";

    /**
     * Let a project also reach further directories on this server, without
     * disturbing where it is — {@code POST
     * /v1/projects/&#123;name&#125;/lend}. {@link ProjectLendHandler} is what
     * claims it.
     */
    public static final String PROJECT_LEND = "project.lend";

    /**
     * Stop lending directories — {@code POST
     * /v1/projects/&#123;name&#125;/unlend}.
     *
     * <p><b>A type of its own rather than a direction field on {@link
     * #PROJECT_LEND}</b>, which is the endpoint's own decision kept: one type
     * taking a direction would make "lend this and unlend it" a frame with no
     * defensible answer, and a caller who filled in the wrong field would do
     * the opposite of what they meant with nothing to say so. {@link
     * ProjectUnlendHandler} is what claims it.
     */
    public static final String PROJECT_UNLEND = "project.unlend";

    /**
     * Point an existing project at a different directory — {@code POST
     * /v1/projects/&#123;name&#125;/workspace}.
     *
     * <p><b>Named {@code workspace} where the controller's method is {@code
     * setWorkspace}</b>, on {@link #DOCUMENT_DETAIL}'s precedent that a type is
     * named for what a client is asking rather than copied from the method
     * blindly: a dotted type's verb is already a verb, so {@code
     * project.setWorkspace} would say it twice and would be the one type in
     * this namespace spelt in camel case. {@link ProjectWorkspaceHandler} is
     * what claims it.
     */
    public static final String PROJECT_WORKSPACE = "project.workspace";

    /**
     * Give a project a different name, and its whole archive with it — {@code
     * POST /v1/projects/&#123;name&#125;/move}, which answers {@link
     * io.aeyer.plowshare.protocol.frames.Code#NO_CONTENT} and not {@code OK}:
     * nothing about the project is new except what it is called, so a body
     * would describe a change that did not happen. {@link ProjectMoveHandler}
     * is what claims it.
     */
    public static final String PROJECT_MOVE = "project.move";

    /**
     * Drop a project's workspace, leaving its archive untouched — {@code DELETE
     * /v1/projects/&#123;name&#125;}, this area's second {@link
     * io.aeyer.plowshare.protocol.frames.Code#NO_CONTENT} answer. {@link
     * ProjectForgetHandler} is what claims it.
     */
    public static final String PROJECT_FORGET = "project.forget";

    /**
     * Read one conversation's turns back, oldest first — {@code GET
     * /v1/conversations/&#123;id&#125;/turns}' own verb, spec §3.1's worked
     * example, and the read half of the two pilots. {@link
     * ConversationTurnsHandler} is what claims it.
     */
    public static final String CONVERSATION_TURNS = "conversation.turns";

    /**
     * Open a conversation, and answer with the id to speak into — {@code POST
     * /v1/conversations}. {@link ConversationOpenHandler} is what claims it.
     */
    public static final String CONVERSATION_OPEN = "conversation.open";

    /**
     * What is open in one tier — {@code GET /v1/conversations}, which is what a
     * console opens on. {@link ConversationListHandler} is what claims it.
     */
    public static final String CONVERSATION_LIST = "conversation.list";

    /**
     * The conversation one agent is still having in one tier — the newest it
     * has answered in, which is what talking to a bot continues. {@link
     * ConversationLatestHandler} is what claims it.
     *
     * <h2>The one type in this area with no endpoint behind it</h2>
     *
     * <p>Every other {@code conversation.*} type names a route of {@code
     * ConversationController}; this one names none, and that is a decision this
     * constant is the place to record. {@code GET /v1/conversations} answers a
     * tier oldest-first and unlimited, which is the order the console's opening
     * screen is built on — so answering "which one do I carry on with" out of it
     * would mean either reversing that shared order or handing a client a whole
     * tier to take the last row of. Neither is a listing's question. The HTTP
     * surface has no caller for this: the console opens a conversation from a
     * list a person is looking at, and a terminal has no list to look at.
     *
     * <p><b>So the socket answers a read HTTP does not</b>, which is the first
     * time the asymmetry runs that way. It is recorded in {@code
     * client.Capabilities} beside the frames it sits with, so the register says
     * it rather than a later parity sweep discovering it.
     */
    public static final String CONVERSATION_LATEST = "conversation.latest";

    /**
     * Archive a conversation, put it back, mark it to go, or cancel the mark —
     * {@code PUT /v1/conversations/&#123;id&#125;/lifecycle}, one verb naming a
     * destination. {@link ConversationLifecycleHandler} is what claims it.
     */
    public static final String CONVERSATION_LIFECYCLE = "conversation.lifecycle";

    /**
     * Every seam a conversation has had — {@code GET
     * /v1/conversations/&#123;id&#125;/compactions}. {@link
     * ConversationCompactionsHandler} is what claims it.
     */
    public static final String CONVERSATION_COMPACTIONS = "conversation.compactions";

    /**
     * What the model is shown, one page at a time — {@code GET
     * /v1/conversations/&#123;id&#125;/chat}. {@link ConversationChatHandler}
     * is what claims it.
     */
    public static final String CONVERSATION_CHAT = "conversation.chat";

    /**
     * Everything that happened, one page at a time — {@code GET
     * /v1/conversations/&#123;id&#125;/trajectory}. {@link
     * ConversationTrajectoryHandler} is what claims it.
     */
    public static final String CONVERSATION_TRAJECTORY = "conversation.trajectory";

    /**
     * What a conversation's prompt costs, and every part of it that cannot
     * honestly be priced — {@code GET
     * /v1/conversations/&#123;id&#125;/context}. {@link
     * ConversationContextHandler} is what claims it.
     */
    public static final String CONVERSATION_CONTEXT = "conversation.context";

    /**
     * The exact message list a conversation's next prompt would carry, or the
     * one a past turn was shown — {@code GET
     * /v1/conversations/&#123;id&#125;/projection}. {@link
     * ConversationProjectionHandler} is what claims it.
     */
    public static final String CONVERSATION_PROJECTION = "conversation.projection";

    /**
     * Where in one tier's conversations something was said — {@code GET
     * /v1/entries/search}.
     *
     * <p><b>Named for the conversation and not for the entry</b>, though the
     * endpoint's path says {@code /v1/entries/search}: the dotted namespace's
     * noun is the thing a client is asking about, this search is scoped to one
     * tier's conversations and answers hits carrying the conversation each was
     * said in, and {@code ConversationController} is the class that answers it.
     * An {@code entry.search} would be the only noun in this namespace with one
     * verb and no owner. {@link ConversationSearchHandler} is what claims it.
     */
    public static final String CONVERSATION_SEARCH = "conversation.search";

    /**
     * Continue a run that stopped for want of allowance, and answer with the
     * new job — {@code POST /v1/conversations/&#123;id&#125;/resume}, which
     * answers {@link io.aeyer.plowshare.protocol.frames.Code#ACCEPTED} and not
     * {@code OK}. {@link ConversationResumeHandler} is what claims it.
     */
    public static final String CONVERSATION_RESUME = "conversation.resume";

    /**
     * Follow conversation logs: from now on this session is pushed {@code
     * conversation.appended} — the conversation and its highest ordinal, no content — whenever
     * entries are committed to it. {@code {conversation}} replaces earlier follows with
     * one log; {@code {conversations: [...]}} replaces the complete set for a multi-view
     * client, and {@code {conversations: []}} removes every follow. The two fields are
     * mutually exclusive. All named logs are validated before subscriptions change.
     *
     * <p><b>No HTTP twin</b>, for {@link #JOB_STREAM}'s reason: a subscription only means
     * anything on a connection that stays open. {@link ConversationFollowHandler} claims it.
     */
    public static final String CONVERSATION_FOLLOW = "conversation.follow";

    /**
     * Ask one document a question and deliberate on the answer — {@code POST
     * /v1/documents/&#123;id&#125;/ask}, which answers {@link
     * io.aeyer.plowshare.protocol.frames.Code#ACCEPTED} and not {@code OK}: a
     * pass is three model calls in series and what comes back is a handle to
     * poll. {@link DocumentAskHandler} is what claims it.
     */
    public static final String DOCUMENT_ASK = "document.ask";

    /**
     * A passage, and what the paper around it argues, in one round trip —
     * {@code POST /v1/documents/retrieve}. {@link DocumentRetrieveHandler} is
     * what claims it.
     */
    public static final String DOCUMENT_RETRIEVE = "document.retrieve";

    /**
     * What the corpus holds — {@code GET /v1/documents}, the read a person who
     * has forgotten what they uploaded has nowhere else to make. {@link
     * DocumentListHandler} is what claims it.
     */
    public static final String DOCUMENT_LIST = "document.list";

    /**
     * One document's structure, with none of its text in it — {@code GET
     * /v1/documents/&#123;id&#125;}.
     *
     * <p><b>Named for the method and the record rather than for the path</b>,
     * which has no verb to borrow: the endpoint is {@code detail}, its answer
     * is {@code DocumentDetailResponse}, and every other type in this area is
     * its controller method's own name. A {@code document.outline} would match
     * the MCP tool's spelling and be the one type here a reader could not find
     * by looking at the controller. {@link DocumentDetailHandler} is what
     * claims it.
     */
    public static final String DOCUMENT_DETAIL = "document.detail";

    /**
     * One chunk and everything above it — {@code GET
     * /v1/documents/chunks/&#123;id&#125;}. {@link DocumentChunkHandler} is
     * what claims it.
     */
    public static final String DOCUMENT_CHUNK = "document.chunk";

    /**
     * Which papers are about this — {@code POST /v1/documents/rank}, which
     * answers with documents where {@link #DOCUMENT_SEARCH} answers with
     * passages. {@link DocumentRankHandler} is what claims it.
     */
    public static final String DOCUMENT_RANK = "document.rank";

    /**
     * A cheap guess at what one paper says about a claim, with no model call at
     * all — {@code POST /v1/documents/&#123;id&#125;/stance}. {@link
     * DocumentStanceHandler} is what claims it.
     */
    public static final String DOCUMENT_STANCE = "document.stance";

    /**
     * What answers have said they took from the corpus — {@code GET
     * /v1/documents/citations}, one question asked of three different keys.
     * {@link DocumentCitationsHandler} is what claims it.
     */
    public static final String DOCUMENT_CITATIONS = "document.citations";

    /**
     * The chunks of the corpus nearest a question, each carrying the paragraph
     * to cite — {@code POST /v1/documents/search}. {@link
     * DocumentSearchHandler} is what claims it.
     */
    public static final String DOCUMENT_SEARCH = "document.search";

    /**
     * Start one declared agent on one task, and answer with the id at once —
     * {@code POST /v1/agents/&#123;name&#125;/runs}, which answers {@link
     * io.aeyer.plowshare.protocol.frames.Code#ACCEPTED} and not {@code OK}: a
     * run is minutes and what comes back is a handle to poll. {@link
     * AgentRunHandler} is what claims it.
     *
     * <p>This is also the type {@code CallerParityTest} was written against
     * before it existed — the endpoint whose <em>answer</em> depends on which
     * caller asked, and the reason a handler is handed an {@link Asking} and
     * never a {@code DefinitionResolver.Caller}.
     */
    public static final String AGENT_RUN = "agent.run";

    /**
     * Start a curator pass over one project — {@code POST /v1/curate}, the
     * second of this area's two {@link
     * io.aeyer.plowshare.protocol.frames.Code#ACCEPTED} answers. {@link
     * AgentCurateHandler} is what claims it.
     *
     * <p><b>Named for the agent and not for a curator</b>, though a pass has no
     * declared agent behind it: {@code AgentController} is the class that
     * answers it, a pass is submitted, polled and cancelled through exactly the
     * job types below, and a {@code curator.pass} would be the only noun in
     * this namespace with one verb and no owner — {@link #CONVERSATION_SEARCH}
     * records the same argument for the same reason.
     */
    public static final String AGENT_CURATE = "agent.curate";

    /**
     * Write one definition to disk, and answer with what it resolves to —
     * {@code POST /v1/agents}. <b>The one type on this surface whose success
     * code is not fixed</b>: {@link
     * io.aeyer.plowshare.protocol.frames.Code#CREATED} for a name that did not
     * exist and {@code OK} for one that did and was replaced, which is the
     * endpoint's own 201-or-200 and not a rounding of it. {@link
     * AgentDefineHandler} is what claims it.
     */
    public static final String AGENT_DEFINE = "agent.define";

    /**
     * What can be run, and what each one may do — {@code GET /v1/agents}, the
     * console's agent picker.
     *
     * <p><b>Named {@code list} where the controller's method is {@code
     * agents}</b>, on {@link #DOCUMENT_DETAIL}'s precedent that a type is named
     * for what a client is asking rather than copied from the method blindly:
     * {@code agent.agents} would say the noun twice, and every other listing on
     * this surface is already {@code list}. {@link AgentListHandler} is what
     * claims it.
     */
    public static final String AGENT_LIST = "agent.list";

    /**
     * Every job this process is holding — {@code GET /v1/jobs}, which is what
     * the console's live job view reconciles against. {@link JobListHandler} is
     * what claims it.
     */
    public static final String JOB_LIST = "job.list";

    /**
     * How a run is going, and how it ended once it has — {@code GET
     * /v1/jobs/&#123;id&#125;}, one endpoint for both questions.
     *
     * <p><b>Named {@code status} where the controller's method is {@code
     * job}</b>, because {@code job.job} is not a sentence. {@code status} is
     * the word this capability already carries on the CLI side, where the verb
     * is {@code job status}, so it is a spelling a client has met rather than a
     * third one. {@link JobStatusHandler} is what claims it.
     */
    public static final String JOB_STATUS = "job.status";

    /**
     * Ask a run to stop at its next turn boundary — {@code POST
     * /v1/jobs/&#123;id&#125;/cancel}, which answers with the job still reading
     * {@code RUNNING}. {@link JobCancelHandler} is what claims it.
     */
    public static final String JOB_CANCEL = "job.cancel";

    /**
     * Ask to be sent the tokens as a model produces them, or to stop.
     *
     * <p><b>No HTTP twin, and unlike {@code conversation.latest} there could not
     * be one.</b> A subscription only means anything on a connection that stays
     * open; there is no request-response shape for "and keep telling me".
     */
    public static final String JOB_STREAM = "job.stream";

    /**
     * Move a running job's ceilings without restarting it — {@code POST
     * /v1/jobs/&#123;id&#125;/limits}. {@link JobLimitsHandler} is what claims
     * it.
     */
    public static final String JOB_LIMITS = "job.limits";
    public static final String BOARD_TOPICS = "board.topics";
    public static final String BOARD_MESSAGES = "board.messages";
    public static final String SWARM_STATUS = "swarm.status";
    public static final String BOARD_TOPUP = "board.topup";

    /**
     * File a claim worth remembering, under the verdict the scribe makes for it
     * — {@code POST /v1/memories}.
     *
     * <p>Always {@code OK}, which is the endpoint's own contract and not a
     * rounding of it: nothing is refused at this layer, only judged for shape,
     * and what shape it was filed under travels in the answer.
     * {@link MemoryWriteHandler} is what claims it.
     */
    public static final String MEMORY_WRITE = "memory.write";

    /**
     * The memories nearest a question — {@code POST /v1/memories/recall}, the
     * read an agent makes before it starts guessing. {@link
     * MemoryRecallHandler} is what claims it.
     */
    public static final String MEMORY_RECALL = "memory.recall";

    /**
     * Give a vector to every live memory in one tier that has none — {@code
     * POST /v1/memories/reembed}, the repair for what a recall's {@code
     * unsearchable} count makes visible. {@link MemoryReembedHandler} is what
     * claims it.
     */
    public static final String MEMORY_REEMBED = "memory.reembed";

    /**
     * One memory, in full, by id — {@code GET
     * /v1/memories/&#123;id&#125;}.
     *
     * <p><b>Named {@code read} where the controller's method is {@code get}</b>,
     * on {@link #DOCUMENT_DETAIL}'s precedent that a type is named for what a
     * client is asking rather than copied from the method blindly. {@code read}
     * is the {@code Archive} door this really goes through — the counting one,
     * deliberately, because a lookup by id is a use — and it is the spelling
     * this capability already carries everywhere else a caller meets it, in
     * {@code memory_read} and {@code plowshare memory read}. A {@code
     * memory.get} would be a third word for one operation. {@link
     * MemoryReadHandler} is what claims it.
     */
    public static final String MEMORY_READ = "memory.read";

    /**
     * One tier's index: its active memories as summary lines, with no bodies —
     * {@code GET /v1/memories/index}, which is what a person surveying what is
     * remembered opens on. {@link MemoryIndexHandler} is what claims it.
     */
    public static final String MEMORY_INDEX = "memory.index";

    /**
     * Record that a memory stopped being true, keeping the memory — {@code POST
     * /v1/memories/&#123;id&#125;/invalidate}, which answers {@code OK} with
     * the memory it kept and not {@link
     * io.aeyer.plowshare.protocol.frames.Code#NO_CONTENT}: this is not a
     * deletion, and the answer is the tombstone. {@link
     * MemoryInvalidateHandler} is what claims it.
     */
    public static final String MEMORY_INVALIDATE = "memory.invalidate";

    /**
     * Ask one tier's digests a question and walk them for an answer — {@code
     * POST /v1/memories/navigate}.
     *
     * <p><b>{@code DigestController}'s endpoint and not {@code
     * MemoryController}'s</b>, despite the path it is served under: a path
     * prefix is not a controller, and the breadth plan filed this one wrong
     * once already. {@link DigestFrames} claims it; {@link MemoryFrames} says
     * in its own javadoc that it does not.
     *
     * <p><b>Answers {@code OK} for a walk that found nothing</b>, which is a
     * ruling and not an oversight. {@code Navigator.Result} carries {@code
     * complete} and the prose it got as far as, this endpoint has always
     * answered 200 with that inside, and clients read it there — so a frame
     * promoting the embedded refusal to a failure {@link
     * io.aeyer.plowshare.protocol.frames.Code} would be a second contract for
     * one read. {@link MemoryNavigateHandler} is what claims it.
     */
    public static final String MEMORY_NAVIGATE = "memory.navigate";

    /**
     * Fold one tier's memories into digests — {@code POST
     * /v1/memories/digest}, which answers {@link
     * io.aeyer.plowshare.protocol.frames.Code#ACCEPTED} and not {@code OK}: a
     * pass is many model calls in series and what comes back is a handle to
     * poll. {@code DigestController}'s, on {@link #MEMORY_NAVIGATE}'s note.
     * {@link MemoryDigestHandler} is what claims it.
     */
    public static final String MEMORY_DIGEST = "memory.digest";

    /**
     * What is waiting on a promotion decision in one tier — {@code GET
     * /v1/proposals}, which is what the console's proposals screen opens on.
     *
     * <p><b>Named {@code list} where the controller's method is {@code
     * waiting}</b>, on {@link #AGENT_LIST}'s precedent: every listing on this
     * surface is {@code list}, and a second word for one shape is a fact a
     * client would have to learn twice. {@link ProposalListHandler} is what
     * claims it.
     */
    public static final String PROPOSAL_LIST = "proposal.list";

    /**
     * Put every ruling the curator made by itself back in front of a person —
     * {@code POST /v1/proposals/reconsider}, the escape hatch for a wrong
     * {@code keep}.
     *
     * <p>Answers both lists, reopened and refused, because a re-open can be
     * refused when the row's waiting place has been taken since it was settled
     * — the endpoint's own reason for not answering a count. {@link
     * ProposalReconsiderHandler} is what claims it.
     */
    public static final String PROPOSAL_RECONSIDER = "proposal.reconsider";

    /**
     * Settle one proposal, once — {@code POST
     * /v1/proposals/&#123;id&#125;/resolve}.
     *
     * <p>The one type in this area whose payload decides which service method
     * runs: {@code accept} chooses between promoting the memory into global and
     * recording the refusal, and the two answer different halves of one record.
     * That branch is the endpoint's own and is named in the request, so it is
     * copied rather than moved — and {@code ProposalFramesTest} drives both
     * arms, which is what makes copying it safe. {@link ProposalResolveHandler}
     * is what claims it.
     */
    public static final String PROPOSAL_RESOLVE = "proposal.resolve";

    /**
     * Every registered search provider, in the store's own order — {@code GET
     * /v1/search/providers}. {@link ProviderListHandler} is what claims it.
     */
    public static final String PROVIDER_LIST = "provider.list";

    /**
     * Take one provider off the ladder — {@code DELETE
     * /v1/search/providers/&#123;key&#125;}, which answers {@link
     * io.aeyer.plowshare.protocol.frames.Code#NO_CONTENT}: the row is gone, so
     * a body would describe something that is not there.
     *
     * <p><b>There is deliberately no {@code provider.register} beside it.</b>
     * {@code POST /v1/search/providers} is operational — the curl-shaped target
     * of {@code bin/plowshare-searxng}, and the call that makes this server
     * dial a URL of the caller's choosing — and §4.1 of the socket design keeps
     * HTTP for what ops genuinely needs. {@code client.Capabilities} carries
     * that as a decision rather than a gap. {@link ProviderDeregisterHandler}
     * is what claims this one.
     */
    public static final String PROVIDER_DEREGISTER = "provider.deregister";

    /**
     * Ask the open web a question — {@code POST /v1/search}.
     *
     * <h2>{@code web} and not {@code search}, which is a decision</h2>
     *
     * <p>Every other noun in this namespace is a thing this deployment holds: a
     * conversation, a document, a project, a memory. These two — this and
     * {@link #WEB_FETCH} — are the ones that reach <em>outside</em> it, for
     * content this server did not generate and does not archive, which is the
     * distinction both capabilities' own console notes already draw. So the
     * noun is what a client is asking about rather than the controller's name,
     * on {@link #CONVERSATION_SEARCH}'s precedent, and the alternative was
     * worse in both directions: {@code search.search} is not a sentence, and
     * {@code search.query} would name the payload's field where every other
     * type in this namespace names a verb.
     *
     * <p><b>Answers {@code OK} for a domain miss</b>, on {@link
     * #MEMORY_NAVIGATE}'s ruling: an exhausted ladder or an expired stored set
     * is {@code SearchPage.refusal} carried in a 200, because that text is
     * prose a model reads and acts on. {@link SearchQueryHandler} is what
     * claims it.
     */
    public static final String WEB_SEARCH = "web.search";

    /**
     * Read a page from outside this deployment, a window at a time — {@code
     * POST /v1/fetch}.
     *
     * <p>{@link #WEB_SEARCH}'s noun for {@link #WEB_SEARCH}'s reason, and the
     * two are siblings in the source as well: {@code FetchController}'s own
     * javadoc says it is {@code SearchController}'s shape verbatim. Answers
     * {@code OK} for a suppressed domain or a dead host, which {@code
     * FetchWindow.refusal} carries inside the body. <b>Two areas, though</b> —
     * one per controller, so a later change to either stays local. {@link
     * WebFetchHandler} is what claims this one.
     */
    public static final String WEB_FETCH = "web.fetch";

    /**
     * Eject what was marked, then mark what policy selects, and say what
     * happened — {@code POST /v1/retention/sweep}.
     *
     * <p><b>Answers {@code OK} and a report, not {@link
     * io.aeyer.plowshare.protocol.frames.Code#ACCEPTED} and a job</b>, which is
     * the endpoint's own decision and the one a reader of the noun would most
     * likely get wrong: a sweep calls no model, and an operator who has just
     * asked for data to be removed wants to be told what was removed rather
     * than handed a handle. {@link RetentionSweepHandler} is what claims it.
     */
    public static final String RETENTION_SWEEP = "retention.sweep";

    /**
     * Reclaim every expired row in both buffers, and say how many each lost —
     * {@code POST /v1/buffers/purge}.
     *
     * <p><b>Answers {@code OK} and a report, not {@link
     * io.aeyer.plowshare.protocol.frames.Code#NO_CONTENT}.</b> The verb whose
     * name makes an empty answer sound right, and its answer is the two numbers
     * an operator ran it for. {@link BufferPurgeHandler} is what claims it.
     */
    public static final String BUFFER_PURGE = "buffer.purge";

    /** Define or replace a schedule by name. {@link ScheduleDefineHandler}. No HTTP twin. */
    public static final String SCHEDULE_DEFINE = "schedule.define";

    /** Every schedule, with its next fire time. {@link ScheduleListHandler}. */
    public static final String SCHEDULE_LIST = "schedule.list";

    /** Pause or resume a schedule. {@link SchedulePauseHandler}. */
    public static final String SCHEDULE_PAUSE = "schedule.pause";

    /** Delete a schedule. {@link ScheduleForgetHandler}. */
    public static final String SCHEDULE_FORGET = "schedule.forget";

    /**
     * Read one sentence into a proposed schedule and trigger, saving nothing. {@link
     * ScheduleReadHandler}. The client confirms with a person and then sends {@link
     * #SCHEDULE_DEFINE} and {@link #TRIGGER_DEFINE}.
     */
    public static final String SCHEDULE_READ = "schedule.read";

    /** Define or replace a trigger by name, owned by the signed-in account. {@link TriggerDefineHandler}. */
    public static final String TRIGGER_DEFINE = "trigger.define";

    /** Every trigger. {@link TriggerListHandler}. */
    public static final String TRIGGER_LIST = "trigger.list";

    /** Pause or resume a trigger. {@link TriggerPauseHandler}. */
    public static final String TRIGGER_PAUSE = "trigger.pause";

    /** Delete a trigger and refuse what it had waiting. {@link TriggerForgetHandler}. */
    public static final String TRIGGER_FORGET = "trigger.forget";

    /** Raise an event by hand, through the same intake a tick uses. {@link EventFireHandler}. */
    public static final String EVENT_FIRE = "event.fire";

    /** What arrived, what it started, and why anything was refused. {@link FiringListHandler}. */
    public static final String FIRING_LIST = "firing.list";

    /** The signed-in account's user-inbox, with its unread count. {@link InboxListHandler}. */
    public static final String INBOX_LIST = "inbox.list";

    /** Mark user-inbox items read; the account's other sockets are told. {@link InboxReadHandler}. */
    public static final String INBOX_READ = "inbox.read";

    /** Open questions of a conversation, or a project's standing approvals. {@link ApprovalFrames}. */
    public static final String APPROVAL_LIST = "approval.list";

    /** Answer a command a run asked a person about, and continue the turn. {@link ApprovalFrames}. */
    public static final String APPROVAL_ANSWER = "approval.answer";

    /** Revoke a standing project approval. {@link ApprovalFrames}. */
    public static final String APPROVAL_REVOKE = "approval.revoke";

    /** Union projects — spec 2026-09-14-a-project-can-be-a-union. */
    public static final String UNION_STATUS = "union.status";
    public static final String UNION_ENABLE = "union.enable";
    public static final String UNION_BEGIN = "union.begin";
    public static final String UNION_READY = "union.ready";
    public static final String UNION_ABORT = "union.abort";
    public static final String UNION_DISABLE = "union.disable";
    public static final String UNION_HIDDEN = "union.hidden";
    public static final String UNION_CONFLICT_OPEN = "union.conflict.open";
    public static final String UNION_CONFLICT_LIST = "union.conflict.list";
    public static final String UNION_CONFLICT_RESOLVE = "union.conflict.resolve";

    /** A conversation's todo list. {@link TodosReadHandler}; {@code todos.changed} is a bare push. */
    public static final String TODOS_READ = "todos.read";

    /** The orchestrations a project can reach, served and refused alike. {@link OrchestrationFrames}. */
    public static final String ORCHESTRATION_START = "orchestration.start";
    public static final String ORCHESTRATION_RECEIPT = "orchestration.receipt";

    public static final String ORCHESTRATION_DEFINITIONS = "orchestration.definitions";

    /** One account's orchestration runs, newest first. {@link OrchestrationFrames}. */
    public static final String ORCHESTRATION_LIST = "orchestration.list";

    /** One run, its todos and its messages. {@link OrchestrationFrames}. */
    public static final String ORCHESTRATION_STATUS = "orchestration.status";

    /** Answer a question a run's conductor asked. {@link OrchestrationFrames}. */
    public static final String ORCHESTRATION_ANSWER = "orchestration.answer";

    /** Cancel a run. {@link OrchestrationFrames}. */
    public static final String ORCHESTRATION_CANCEL = "orchestration.cancel";

    /** A project's caps, read and applied to its live runs — spec 2026-09-29 §2. {@link
     *  CapsFrames}. */
    public static final String ORCHESTRATION_CAPS = "orchestration.caps";

    /** A run tree's record, a page at a time; {@code orchestration.recorded} is a bare push.
     *  {@link RecordFrames}. */
    public static final String ORCHESTRATION_RECORD = "orchestration.record";

    public static final String USAGE_CONVERSATION = "usage.conversation";
    public static final String USAGE_PROJECT = "usage.project";
    public static final String USAGE_AGENT = "usage.agent";
    public static final String USAGE_RUN = "usage.run";
    public static final String USAGE_ORCHESTRATION = "usage.orchestration";
    public static final String USAGE_MODELS = "usage.models";
    public static final String USAGE_POOLS = "usage.pools";
    public static final String USAGE_CALLS = "usage.calls";
    public static final String USAGE_SUBSCRIBE = "usage.subscribe";
    public static final String USAGE_UNSUBSCRIBE = "usage.unsubscribe";
    public static final String CONVERSATION_CONTEXT_COUNT = "conversation.context.count";
    /** Authenticated information controls; no corresponding REST application routes. */
    public static final String INFORMATION_MIGRATION_LIST = "information.migration.list";
    public static final String INFORMATION_MIGRATION_ADOPT = "information.migration.adopt";
    public static final String INFORMATION_INVENTORY = "information.inventory";
    public static final String INFORMATION_ACQUISITIONS = "information.acquisitions";
    public static final String INFORMATION_EVENTS = "information.events";
    public static final String INFORMATION_REBUILD = "information.rebuild";
    public static final String INFORMATION_ALLOWANCE = "information.allowance";
    public static final String INFORMATION_UPLOAD = "information.upload";
    public static final String INFORMATION_ACQUIRE = "information.acquire";
    public static final String INFORMATION_LIST = "information.list";
    public static final String INFORMATION_STATUS = "information.status";
    public static final String INFORMATION_READ = "information.read";
    public static final String INFORMATION_SEARCH = "information.search";
    public static final String INFORMATION_RANK = "information.rank";
    public static final String INFORMATION_ASK = "information.ask";
    public static final String INFORMATION_EVIDENCE_RECORD = "information.evidence.record";
    public static final String INFORMATION_EVIDENCE_READ = "information.evidence.read";
    public static final String INFORMATION_RECORD_REPORT = "information.record.report";
    public static final String INFORMATION_FINALISE = "information.finalise";
    public static final String INFORMATION_LINK = "information.link";
    public static final String INFORMATION_UNLINK = "information.unlink";
    public static final String INFORMATION_SHARE = "information.share";
    public static final String INFORMATION_UNSHARE = "information.unshare";
    public static final String INFORMATION_WITHDRAW = "information.withdraw";
    public static final String INFORMATION_UNEXCLUDE = "information.unexclude";
    public static final String INFORMATION_MIGRATION_INSPECT = "information.migration.inspect";
    public static final String INFORMATION_MIGRATION_RELEASE = "information.migration.release";
    public static final String INFORMATION_EXCLUDE = "information.exclude";
    public static final String INFORMATION_RESTORE = "information.restore";
    public static final String INFORMATION_DELETE = "information.delete";
    public static final String INFORMATION_RETRY = "information.retry";
    public static final String INFORMATION_REVISE = "information.revise";
    public static final String INFORMATION_REPLACE = "information.replace";
    public static final String INFORMATION_REFRESH = "information.refresh";

    private FrameTypes() {}

    /**
     * {@code type}, unchanged, once it is confirmed to look like {@code
     * "noun.verb"} — lowercase segments separated by dots, at least one dot.
     *
     * @throws IllegalArgumentException if {@code type} is not shaped that way
     */
    public static String requireWellFormed(String type) {
        if (type == null || !DOTTED.matcher(type).matches()) {
            throw new IllegalArgumentException("\"" + type + "\" is not a dotted discriminator"
                    + " like \"conversation.turns\" — see spec §3.1");
        }
        return type;
    }
}
