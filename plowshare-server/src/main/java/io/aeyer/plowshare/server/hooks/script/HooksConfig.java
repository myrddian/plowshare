package io.aeyer.plowshare.server.hooks.script;

import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.LocalHookSetStore;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.session.SessionRegistry;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The project and local layers of the hook chain. {@code AgentsConfig.runHooks} finds them by
 * the names {@code projectHooks} and {@code localHooks} and puts the harness in front of them.
 *
 * <p><b>Shutdown needs nothing from either layer.</b> {@code hookEngine}'s
 * {@code close} calls {@code Engine.close(true)}, which closes every context built
 * on it, and {@link ScriptHooks}' timer thread is a daemon. If Spring infers
 * {@code close} on a {@code ScriptHooks} instance anyway, that is harmless: it
 * retires pools the engine then closes.
 */
@Configuration
@EnableConfigurationProperties(HooksProperties.class)
public class HooksConfig {

    @Bean(destroyMethod = "close")
    public HookEngine hookEngine() {
        return new HookEngine();
    }

    @Bean("projectHooks")
    public Hooks projectHooks(ProjectStore projects, DataLayout data, HookEngine engine,
            HooksProperties properties) {
        if (!properties.isEnabled() || !data.keepsAnything()) {
            return Hooks.NONE;
        }
        return new ScriptHooks(projects::id, data::hooksFor, engine, properties, Instant::now);
    }

    /**
     * The local layer: a person's own hooks from the snapshot each log opened with, run after the
     * project's (spec 2026-09-30-local-hooks-are-served decisions 1 and 5). {@code
     * AgentsConfig.runHooks} and {@code LogStagesConfig.logStages} chain it after {@code
     * projectHooks}. {@link Hooks#NONE} when hooks are switched off or no archive holds the pins.
     *
     * <p>A local {@code allow} counts only on the log owner's own machine: the session serving the
     * command is held, in {@link SessionRegistry}, by the account that owns the log (decision 6,
     * as ruled). The registry is asked when an allow is weighed; with none, no allow counts.
     */
    @Bean("localHooks")
    public Hooks localHooks(ObjectProvider<ConversationStore> conversations,
            ObjectProvider<LocalHookSetStore> sets, ObjectProvider<SessionRegistry> sessions,
            HookEngine engine, HooksProperties properties) {
        ConversationStore rows = conversations.getIfAvailable();
        LocalHookSetStore stored = sets.getIfAvailable();
        if (!properties.isEnabled() || rows == null || stored == null) {
            return Hooks.NONE;
        }
        return ScriptHooks.local(rows::localHooksOf, rows::ownerOf, stored::find,
                (log, session) -> ownersMachine(rows, sessions.getIfAvailable(), log, session),
                engine, properties, Instant::now);
    }

    /** Whether {@code session} is held by the account that owns {@code log}; nobody's is no one's. */
    static boolean ownersMachine(ConversationStore rows, SessionRegistry sessions, String log,
            String session) {
        if (sessions == null) {
            return false;
        }
        Optional<String> owner = rows.ownerOf(log);
        return owner.isPresent() && owner.equals(sessions.accountOf(session));
    }
}
