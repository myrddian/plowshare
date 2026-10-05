package io.aeyer.plowshare.server.todos;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.events.AccountPushes;
import io.aeyer.plowshare.server.events.SpeakerHandles;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link TodosConfig#tellClients}, the push {@link TodoBoard} is handed at wiring time — extracted
 * to a package-private static method so its branches are testable without standing up the bean or a
 * database, on {@code Dispatcher}'s own precedent for a lost side effect: logged, never thrown.
 */
class TodosConfigTest {

  @SuppressWarnings("unchecked")
  private final ObjectProvider<AccountPushes> pushes = mock(ObjectProvider.class);

  @SuppressWarnings("unchecked")
  private final ObjectProvider<SpeakerHandles> speakers = mock(ObjectProvider.class);

  @Test
  void a_batch_with_no_speaking_session_tells_nobody() {
    TodosConfig.tellClients(pushes, speakers, null, "cnv_1");

    verifyNoInteractions(pushes, speakers);
  }

  @Test
  void a_push_that_throws_is_swallowed_rather_than_propagated() {
    SpeakerHandles handles = mock(SpeakerHandles.class);
    when(handles.handleOf("s1")).thenReturn(Optional.of("enzo"));
    when(speakers.getIfAvailable(org.mockito.ArgumentMatchers.any())).thenReturn(handles);
    AccountPushes broken = mock(AccountPushes.class);
    org.mockito.Mockito.doThrow(new RuntimeException("socket gone"))
        .when(broken)
        .push("enzo", new io.aeyer.plowshare.protocol.AccountEvent.TodosChanged("cnv_1"));
    when(pushes.getIfAvailable(org.mockito.ArgumentMatchers.any())).thenReturn(broken);

    assertDoesNotThrow(
        () -> TodosConfig.tellClients(pushes, speakers, "s1", "cnv_1"),
        "the batch already committed; a client that could not be told must not turn a"
            + " successful write into a thrown exception");

    verify(broken).push("enzo", new io.aeyer.plowshare.protocol.AccountEvent.TodosChanged("cnv_1"));
  }
}
