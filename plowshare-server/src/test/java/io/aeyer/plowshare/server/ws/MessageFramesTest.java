package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.agents.CallerAccess;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.messaging.MessageService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MessageFramesTest {
  private MessageService messages;
  private CallerAccess access;
  private MessageFrames frames;
  private final Asking asking = new Asking("session", "enzo");

  @BeforeEach
  void setup() {
    messages = mock(MessageService.class);
    access = mock(CallerAccess.class);
    frames = new MessageFrames(messages, access);
    when(messages.owned("ins_one", "enzo"))
        .thenReturn(
            new MessageService.Instance(
                "ins_one", "enzo", "p", "reviewer", "cnv_one", "persistent", true));
  }

  @Test
  void account_and_project_authorization_precede_opening_or_stopping() {
    assertThrows(
        CallerFault.class, () -> frames.open(Map.of("project", "p"), new Asking("session")));
    verifyNoInteractions(messages);
    doThrow(new CallerFault("Not a project member")).when(access).requireWork("p", "enzo");
    assertThrows(
        CallerFault.class,
        () -> frames.open(Map.of("project", "p", "agent", "reviewer", "requestId", "key"), asking));
    assertThrows(
        CallerFault.class, () -> frames.stop(Map.of("instance", "ins_one"), asking, false));
    verify(messages, never()).open(any(), any(), any(), anyBoolean(), any());
    verify(messages, never()).stop(any(), any(), anyBoolean());
  }

  @Test
  void instance_address_owns_scope_and_message_controls_check_both_participants() {
    frames.makeDefault(Map.of("instance", "ins_one", "project", "spoof"), asking);
    verify(access).requireWork("p", "enzo");
    verify(messages).makeDefault("ins_one", "enzo");
    when(messages.route("bdm_one"))
        .thenReturn(
            Optional.of(
                new MessageService.Route(
                    "bdm_one", "ins_one", "ins_foreign", null, true, false, false, null, false)));
    doThrow(new CallerFault("Foreign instance")).when(messages).owned("ins_foreign", "enzo");
    assertThrows(CallerFault.class, () -> frames.cancel(Map.of("message", "bdm_one"), asking));
    verify(messages, never()).cancel(any(), any());
  }

  @Test
  void inspection_pages_are_bounded_and_all_controls_are_registered() {
    assertEquals(9, frames.frames().size());
    assertThrows(
        CallerFault.class, () -> frames.instances(Map.of("project", "p", "limit", 201), asking));
    assertThrows(
        CallerFault.class,
        () -> frames.deliveries(Map.of("instance", "ins_one", "offset", -1), asking));
    assertThrows(CallerFault.class, () -> frames.instances(Map.of(), asking));
    verify(messages, never()).listing(any(), any(), anyBoolean(), anyInt(), anyInt());
    verify(messages, never()).deliveries(any(), any(), anyInt(), anyInt());
  }
}
