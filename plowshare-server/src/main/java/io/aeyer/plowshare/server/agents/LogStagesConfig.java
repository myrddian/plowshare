package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.LocalHookSetStore;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.events.Inbox;
import io.aeyer.plowshare.server.events.SpeakerHandles;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.hooks.script.HooksProperties;
import io.aeyer.plowshare.server.llm.tokens.TokenCount;
import io.aeyer.plowshare.server.llm.tokens.Tokenizer;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import io.aeyer.plowshare.server.session.SessionRegistry;
import io.aeyer.plowshare.server.ws.FileChannelHandler;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The log stages over the project's hooks, handed to every door that opens, closes or delivers
 * from a log (spec 2026-09-28-hooks-reach-the-log, slice 1). Today that is {@link Compaction},
 * whose {@code logFor} every machine log — {@link JobStore}'s among them — is opened through, and
 * {@code Orchestrations}, whose conductor's log opens after its start commits; the two doors a
 * result leaves a log by, the orchestration {@code Delivery} and the events {@code Dispatcher};
 * and {@code ws.ApprovalFrames}, where a person answers an approval (slice 3).
 *
 * <p><b>Everything but the archive is a provider, asked for when it is used</b>, on {@code
 * AppendedConfig}'s pattern: {@link Turn} is built over {@link Compaction}, so naming it here
 * would be a cycle; and a context without hooks, an inbox or a registry still boots, running no
 * hooks, telling nobody, and saying why at the stage that could not ask.
 *
 * <p><b>No harness layer.</b> Decision 10's {@code plowshare.harness.logs} ships empty, and
 * nothing reads a harness hook at a log stage until one exists; the slot is added with it.
 *
 * <p><b>The local layer</b> (spec 2026-09-30-local-hooks-are-served) runs after the project's,
 * over the snapshot {@link #localHookPins} pinned when the log opened.
 */
@Configuration
public class LogStagesConfig {

    private static final Logger log = LoggerFactory.getLogger(LogStagesConfig.class);

    @Bean
    public LogStages logStages(@Qualifier("projectHooks") ObjectProvider<Hooks> projectHooks,
            @Qualifier("localHooks") ObjectProvider<Hooks> localHooks,
            ObjectProvider<LocalHooks> localPins, ConversationStore conversations,
            TurnStore turns, EntryStore entries,
            ObjectProvider<Turn> speaking, ObjectProvider<Inbox> inbox,
            ObjectProvider<AgentRegistry> agents, ObjectProvider<Compaction> compaction,
            ObjectProvider<io.aeyer.plowshare.server.orchestrations.Orchestrations> orchestrations,
            ObjectProvider<io.aeyer.plowshare.server.orchestrations.Delivery> orchestrationDelivery,
            ObjectProvider<io.aeyer.plowshare.server.events.Dispatcher> dispatcher,
            ObjectProvider<io.aeyer.plowshare.server.ws.ApprovalFrames> approvalFrames,
            ObjectProvider<HooksProperties> hooksProperties, ObjectProvider<Tokenizer> tokenizer) {
        LogStages stages = new HookedLogStages(
                // The system date is first and fixed at log.open, then the two user tiers run in
                // their specified order (spec 2026-09-30-local-hooks-are-served decision 5).
                Hooks.chain(new CurrentDateHook(Instant::now, ZoneId.systemDefault()),
                        projectHooks.getIfAvailable(() -> Hooks.NONE),
                        localHooks.getIfAvailable(() -> Hooks.NONE)),
                conversations, turns,
                // Decision 7: a log with a turn in flight files at that turn. No registry, no
                // turn in flight.
                log -> {
                    Turn turn = speaking.getIfAvailable();
                    return turn != null && turn.isSpeaking(log);
                },
                entries,
                new HookedLogStages.Notifier() {
                    @Override public void notify(String handle,String kind,String text) {
                        required(inbox.getIfAvailable(),"inbox").notify(handle,kind,text);
                    }
                    @Override public void notifyFromLog(String handle,String kind,String text,String log) {
                        required(inbox.getIfAvailable(),"inbox").notifyFromLog(handle,kind,text,log);
                    }
                },
                name -> required(agents.getIfAvailable(), "agent registry").find(name)
                        .map(AgentDefinition::bot).orElse(false),
                Instant::now,
                // Decision 4 (amended 2026-09-29): fold.post's chain shares the one limit every
                // hook call has. A context without the hooks' properties has their default.
                () -> hooksProperties.getIfAvailable(HooksProperties::new).getTimeout(),
                asked(tokenizer),
                // Asked at each open, not now, on this class's pattern: the pin reaches both
                // sockets' beans, and the stages must not wait on the web layer to be built.
                opened -> localPins.getIfAvailable(() -> LocalHooks.NONE).pin(opened));
        compaction.ifAvailable(each -> each.useLogStages(stages));
        orchestrations.ifAvailable(engine -> engine.useLogStages(stages));
        orchestrationDelivery.ifAvailable(delivery -> delivery.useLogStages(stages));
        dispatcher.ifAvailable(events -> events.useLogStages(stages));
        approvalFrames.ifAvailable(frames -> frames.useLogStages(stages));
        return stages;
    }

    /**
     * Snapshots a log's local hooks as it opens (spec 2026-09-30-local-hooks-are-served decisions
     * 3 and 4): over the session's file channel, only for a live session that roots the log's
     * project (the same two questions {@code DefinitionResolver} asks of a session's
     * definitions), held by the log's owner on the registry, the listener and the file channel
     * alike. {@link LocalHooks#NONE} when hooks are off or any collaborator is missing: never
     * {@code PinnedLocalHooks} over a stubbed account, which would refuse every log.
     */
    @Bean
    public LocalHooks localHookPins(ObjectProvider<FileChannelHandler> channel,
            ObjectProvider<SpeakerHandles> speakers, ObjectProvider<SessionRegistry> sessions,
            ObjectProvider<PresenceRegistry> presences, ObjectProvider<ProjectStore> projects,
            ObjectProvider<ConversationStore> conversations, ObjectProvider<LocalHookSetStore> sets,
            ObjectProvider<HooksProperties> hooksProperties) {
        FileChannelHandler files = channel.getIfAvailable();
        SpeakerHandles listeners = speakers.getIfAvailable();
        SessionRegistry registry = sessions.getIfAvailable();
        PresenceRegistry present = presences.getIfAvailable();
        ProjectStore rows = projects.getIfAvailable();
        ConversationStore logs = conversations.getIfAvailable();
        LocalHookSetStore stored = sets.getIfAvailable();
        if (!hooksProperties.getIfAvailable(HooksProperties::new).isEnabled()) {
            return LocalHooks.NONE;
        }
        List<String> missing = new ArrayList<>();
        named(missing, files, "file channel");
        named(missing, listeners, "listener handles");
        named(missing, registry, "session registry");
        named(missing, present, "presence registry");
        named(missing, rows, "project store");
        named(missing, logs, "conversation store");
        named(missing, stored, "local hook set store");
        if (!missing.isEmpty()) {
            log.warn("No log's local hooks are snapshotted, so no log has a local tier: this server"
                    + " has no {}", String.join(", no ", missing));
            return LocalHooks.NONE;
        }
        // The roots are keyed by project id and the pin names the project: a global or nameless
        // log roots nothing, and never reaches the lookup.
        BiPredicate<Long, String> rootsById = AgentsConfig.sessionRoots(rows, present);
        return new PinnedLocalHooks(session -> new ChannelHooks(files, session).read(),
                AgentsConfig.sessionLive(registry),
                (project, session) -> project != null
                        && rootsById.test(rows.id(project), session),
                new PinnedLocalHooks.Accounts(registry::accountOf, listeners::handleOf,
                        files::handleOf),
                logs, stored);
    }

    private static void named(List<String> missing, Object bean, String what) {
        if (bean == null) {
            missing.add(what);
        }
    }

    /**
     * The server's one {@link Tokenizer}, asked for when fold.post's kept text is counted. A
     * context without one refuses the count, and HookedLogStages drops what it could not count
     * and records why (spec 2026-09-28-hooks-reach-the-log §3, amended 2026-09-29).
     */
    private static Tokenizer asked(ObjectProvider<Tokenizer> tokenizer) {
        return new Tokenizer() {
            @Override
            public TokenCount count(String text) {
                return required(tokenizer.getIfAvailable(), "tokenizer").count(text);
            }

            @Override
            public String describe() {
                Tokenizer found = tokenizer.getIfAvailable();
                return found == null ? "no tokenizer" : found.describe();
            }
        };
    }

    /**
     * An absent collaborator is a refusal HookedLogStages logs at the stage that asked, never a
     * silent no: a notice nobody could deliver must say so.
     */
    private static <T> T required(T bean, String what) {
        if (bean == null) {
            throw new IllegalStateException("this server has no " + what);
        }
        return bean;
    }
}
