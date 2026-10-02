package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.agents.Compaction;
import io.aeyer.plowshare.server.agents.Watchers;
import io.aeyer.plowshare.server.agents.learner.Learner;
import io.aeyer.plowshare.server.archive.EntryStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Hands the log's writers the push that tells followers. Providers and not beans, so a context
 * with no compaction or learner — a slice, a fixture — still boots; and the channel is looked up
 * per push, so the socket's handler is never needed at wiring time.
 */
@Configuration
public class AppendedConfig {

    @Bean
    public ConversationAppended conversationAppended(Watchers watchers, EntryStore entries,
            ObjectProvider<SessionPushes> pushes, ObjectProvider<Compaction> compaction,
            ObjectProvider<Learner> learner) {
        ConversationAppended appended = new ConversationAppended(watchers, entries,
                (session, body) -> pushes.getIfAvailable(() -> SessionPushes.NONE)
                        .tell(session, body));
        compaction.ifAvailable(each -> each.useGrowth(appended));
        learner.ifAvailable(each -> each.useGrowth(appended));
        return appended;
    }
}
