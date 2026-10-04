package io.aeyer.plowshare.server.ws;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.aeyer.plowshare.server.agents.Compaction;
import io.aeyer.plowshare.server.agents.Watchers;
import io.aeyer.plowshare.server.agents.learner.Learner;
import io.aeyer.plowshare.server.archive.EntryStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** The push is wired to both writers of a log that are not a turn's own run. */
class AppendedConfigTest {

  @Test
  void the_log_s_writers_are_handed_the_push_that_tells_followers() {
    Compaction compaction = mock(Compaction.class);
    Learner learner = mock(Learner.class);

    ConversationAppended appended =
        new AppendedConfig()
            .conversationAppended(
                new Watchers(),
                mock(EntryStore.class),
                providerOf(SessionPushes.class, null),
                providerOf(Compaction.class, compaction),
                providerOf(Learner.class, learner));

    verify(compaction).useGrowth(appended);
    verify(learner).useGrowth(appended);
  }

  private static <T> ObjectProvider<T> providerOf(Class<T> type, T bean) {
    DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
    if (bean != null) {
      factory.registerSingleton(type.getName(), bean);
    }
    return factory.getBeanProvider(type);
  }
}
