package io.aeyer.plowshare.server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RelayMessageIngressTest {
  private static final UUID ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");
  private final MessageIngress messages = mock(MessageIngress.class);
  private final MessageRelayRepository repository = mock(MessageRelayRepository.class);
  private final MessageRelayDefinitions definitions = mock(MessageRelayDefinitions.class);
  private final MessageRelayProperties properties = new MessageRelayProperties();
  private final Runnable signal = mock(Runnable.class);
  private final MessageRelayPages pages =
      new MessageRelayPages(messages, repository, UnitOfWork.NONE);
  private final RelayMessageIngress ingress =
      new RelayMessageIngress(
          messages, repository, pages, definitions, UnitOfWork.NONE, properties, signal);
  private final MessageRelayRepository.Family family =
      new MessageRelayRepository.Family(
          "relay-channel",
          "alice",
          "project",
          9,
          RelayPort.publisher("alice"),
          "commands",
          "subscriber",
          ID,
          "shared",
          null,
          "worker",
          "results",
          ID);

  private MessageRelayIngress.Request request(MessageRelayPayload.Command command) {
    return new MessageRelayIngress.Request(
        new MessageRelayRepository.Intent(
            ID,
            family,
            command.client(),
            command.requestId(),
            "source-event",
            "message."
                + (command instanceof MessageRelayPayload.Receive
                    ? "receive"
                    : command instanceof MessageRelayPayload.Cancel ? "cancel" : "inspect"),
            RelayPort.hash("fingerprint"),
            RelayCausation.root("root"),
            AT),
        command);
  }

  private MessageRelayIngress.Request receive() {
    return request(
        new MessageRelayPayload.Receive(
            "plowshare-message-request/1", ID, "client", null, "Question", null));
  }

  private MessageIngress.Task task() {
    return new MessageIngress.Task(
        ID, ID, ID, 1, "worker", "bdm_Request", "SUBMITTED", null, null, List.of(), AT);
  }

  @Test
  void native_admission_uses_only_authenticated_pins_and_one_authorized_snapshot() {
    when(messages.receive(anyString(), any())).thenReturn(task());
    var accepted = ingress.accept(receive());
    var nativeRequest = ArgumentCaptor.forClass(MessageIngress.Request.class);
    verify(messages).receive(eq("alice"), nativeRequest.capture());
    assertEquals("project", nativeRequest.getValue().project());
    assertEquals("relay-channel", nativeRequest.getValue().channel());
    assertEquals("worker", nativeRequest.getValue().agent());
    assertEquals("source-event", nativeRequest.getValue().source().identity());
    assertEquals(RelayCausation.root("root"), nativeRequest.getValue().causation());
    assertEquals(ID, accepted.task().id());
    verify(messages, never()).summary(anyString(), anyString(), anyString(), anyString(), any());
    verify(repository, never()).migrate(any(), anyString());
    verify(signal).run();
  }

  @Test
  void immutable_prior_query_or_receive_receipt_never_reexecutes_native_work() {
    var retained = mock(MessageRelayPayload.Snapshot.class);
    var request = receive();
    when(repository.prior(request.intent())).thenReturn(Optional.of(retained));
    assertEquals(retained, ingress.accept(request));
    verifyNoInteractions(messages, definitions, signal);
  }

  @Test
  void current_command_refusal_retains_typed_negative_without_native_admission() {
    doThrow(new CallerFault("no current command grant")).when(definitions).require(family, null);
    var refused = ingress.accept(receive());
    assertEquals("COMMAND_REFUSED", refused.refusal());
    assertNull(refused.task());
    verifyNoInteractions(messages);
    verify(repository).retain(receive().intent(), refused);
  }

  @Test
  void native_refusal_recovers_committed_receipt_or_retains_scoped_negative_after_rollback() {
    when(messages.receive(anyString(), any())).thenThrow(new MessageRefused("closed context"));
    var refused = ingress.accept(receive());
    assertEquals("NOT_ACCESSIBLE", refused.refusal());
    verify(repository).retain(receive().intent(), refused);
  }

  @Test
  void cancel_and_inspect_resolve_only_the_shared_principal_channel_client_scope() {
    when(repository.task(family, "client", ID, null)).thenReturn(Optional.of(ID));
    when(messages.summary("alice", "project", "relay-channel", "client", ID)).thenReturn(task());
    var cancel =
        request(new MessageRelayPayload.Cancel("plowshare-message-cancel/1", ID, "client", ID));
    ingress.accept(cancel);
    verify(messages).cancel("alice", "project", "relay-channel", "client", ID);
    var inspect =
        request(
            new MessageRelayPayload.Inspect(
                "plowshare-message-inspect/1", UUID.randomUUID(), "other-client", null, ID, null));
    assertEquals("NOT_ACCESSIBLE", ingress.accept(inspect).refusal());
    verify(repository).task(family, "other-client", null, ID);
  }

  @Test
  void legacy_adoption_requires_the_complete_operator_mapping() {
    properties.setLegacyMappings(
        List.of(
            new MessageRelayProperties.LegacyMapping(
                "alice",
                "project",
                "other-client",
                "commands",
                "subscriber",
                ID,
                "shared",
                "worker",
                "results",
                ID)));
    when(messages.receive(anyString(), any())).thenReturn(task());
    ingress.accept(receive());
    verify(repository, never()).migrate(any(), anyString());
    properties.setLegacyMappings(
        List.of(
            new MessageRelayProperties.LegacyMapping(
                "alice",
                "project",
                "client",
                "commands",
                "subscriber",
                ID,
                "shared",
                "worker",
                "results",
                ID)));
    ingress.accept(receive());
    verify(repository).migrate(family, "client");
  }
}
