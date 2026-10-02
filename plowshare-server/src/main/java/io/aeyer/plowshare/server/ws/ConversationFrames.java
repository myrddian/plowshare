package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.CallerAccess;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.Compaction;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.LogStages;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.api.ConversationsProperties;
import io.aeyer.plowshare.server.archive.CompactionStore;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Conversations;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.llm.tokens.Tokenizer;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@code ConversationController}'s area of the frame surface — every {@code
 * conversation.*} type this server answers, which as of the breadth plan's
 * Task 1 is all eleven of that controller's endpoints — and, since a bot
 * continues its conversation, one type that is no endpoint of that controller's
 * or of anybody's. {@link FrameTypes#CONVERSATION_LATEST} is where the asymmetry
 * is argued; what it means here is that the twelve entries below are eleven
 * mirrors and one read this surface has of its own.
 *
 * <h2>One constructor, and it is the controller's own</h2>
 *
 * <p>The services below are the same beans {@code ConversationController} is
 * injected with, in the same order, which is what makes a frame's refusal the
 * endpoint's own refusal rather than a second one shaped like it. <b>The whole
 * list is taken even though no single handler wants all of it</b>: a handler
 * takes the two or three it needs, and this class is where the eleven come
 * together, so that "the frame calls what the controller calls" is a fact a
 * reader can check by putting two constructors side by side.
 *
 * <p>One of the {@link FrameArea} classes that javadoc describes. A later
 * breadth task adds <b>its own</b> {@code *Frames.java} rather than editing
 * this one — nothing here is a shared signature.
 */
@Component
public class ConversationFrames implements FrameArea {

    private final CallerAccess access;
    private final Conversations rules;
    private final ConversationStore conversations;
    private final CompactionStore compactions;
    private final TurnStore turns;
    private final EntryStore entries;
    private final JobRuntime runtime;
    private final Turn speaking;
    private final ObjectProvider<AgentRegistry> agents;
    private final ConversationsProperties properties;
    private final Compaction compaction;
    private final Tokenizer tokenizer;
    private final Callers callers;
    private final LogStages logStages;

    /**
     * @param rules the conversation rules six of these handlers ask a question
     *     of — the same bean the controller asks it of
     * @param conversations the store a conversation is opened, listed and moved
     *     through
     * @param compactions the store the seams are read from
     * @param turns the store a history is read from
     * @param entries the store a chat, a trajectory and a search are read from
     * @param runtime what assembles the schemas a definition is really offered
     * @param speaking the service a stopped run is continued through
     * @param agents the registry an agent name is resolved through
     * @param properties the operator's own defaults, which decide an allowance
     *     no caller named
     * @param compaction what assembles a projection, and never sends one
     * @param tokenizer how this deployment counts tokens
     * @param callers what resolves an agent for a conversation and the asking session
     * @param logStages the log stages a conversation opens and closes through
     */
    public ConversationFrames(Conversations rules, ConversationStore conversations,
            CompactionStore compactions, TurnStore turns, EntryStore entries, JobRuntime runtime,
            Turn speaking, ObjectProvider<AgentRegistry> agents,
            ConversationsProperties properties, Compaction compaction, Tokenizer tokenizer,
            Callers callers, LogStages logStages,
            CallerAccess access) {
        this.access = access;
        this.rules = Objects.requireNonNull(rules, "rules");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.compactions = Objects.requireNonNull(compactions, "compactions");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.entries = Objects.requireNonNull(entries, "entries");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.speaking = Objects.requireNonNull(speaking, "speaking");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.compaction = Objects.requireNonNull(compaction, "compaction");
        this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
        this.callers = Objects.requireNonNull(callers, "callers");
        this.logStages = Objects.requireNonNull(logStages, "logStages");
    }

    private io.aeyer.plowshare.server.information.InformationJobs information;
    @org.springframework.beans.factory.annotation.Autowired
    public void useInformationInputs(io.aeyer.plowshare.server.information.InformationJobs inputs) { information=inputs; }

    @Override
    public Map<String, FrameHandler> frames() {
        Map<String,FrameHandler> handlers = Map.ofEntries(
                Map.entry(FrameTypes.CONVERSATION_OPEN,
                        new ConversationOpenHandler(conversations, properties, logStages, access)),
                Map.entry(FrameTypes.CONVERSATION_LIST,
                        new ConversationListHandler(conversations)),
                Map.entry(FrameTypes.CONVERSATION_LATEST,
                        new ConversationLatestHandler(rules)),
                Map.entry(FrameTypes.CONVERSATION_LIFECYCLE,
                        new ConversationLifecycleHandler(conversations, logStages)),
                Map.entry(FrameTypes.CONVERSATION_TURNS,
                        new ConversationTurnsHandler(rules, turns)),
                Map.entry(FrameTypes.CONVERSATION_COMPACTIONS,
                        new ConversationCompactionsHandler(rules, compactions)),
                Map.entry(FrameTypes.CONVERSATION_CHAT,
                        new ConversationChatHandler(rules, entries)),
                Map.entry(FrameTypes.CONVERSATION_TRAJECTORY,
                        new ConversationTrajectoryHandler(rules, entries)),
                Map.entry(FrameTypes.CONVERSATION_CONTEXT,
                        new ConversationContextHandler(rules, turns, runtime, callers, tokenizer,
                                compaction)),
                Map.entry(FrameTypes.CONVERSATION_PROJECTION,
                        new ConversationProjectionHandler(rules, turns, agents, compaction)),
                Map.entry(FrameTypes.CONVERSATION_SEARCH,
                        new ConversationSearchHandler(entries)),
                Map.entry(FrameTypes.CONVERSATION_RESUME,
                        new ConversationResumeHandler(rules, agents, speaking, access)));
        if(information==null) return handlers;
        Map<String,FrameHandler> secured=new java.util.LinkedHashMap<>();
        handlers.forEach((type,handler) -> secured.put(type,(payload,asking) -> {
            if(payload!=null && payload.get("conversation") instanceof String log && !log.isBlank())
                information.requireLog(log,asking.handle());
            if(type.equals(FrameTypes.CONVERSATION_SEARCH))
                return new ConversationSearchHandler(entries.forAccount(asking.handle())).handle(payload,asking);
            return handler.handle(payload,asking);
        }));
        return Map.copyOf(secured);
    }
}
