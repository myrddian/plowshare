package io.aeyer.plowshare.server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.messaging.MessagePublications.Binding;
import io.aeyer.plowshare.server.messaging.MessagePublications.Participant;
import io.aeyer.plowshare.server.messaging.MessagePublications.Reply;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPayload;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RelayMessagePublicationsTest {
  private static final Instant NOW = Instant.parse("2026-11-13T01:02:03.123456Z");
  private static final String PUBLISHER =
      "messaging:2bd806c97f0e00af1a1fc3328fa763a9269723c8db8fac4f93af71db186d6e90";
  private static final Relay.TopicKey TOPIC = new Relay.TopicKey(9, "inbox.fixture");
  private static final Participant SENDER = new Participant("ins_Sender", "alice", 7, "cnv_Source");
  private static final Participant RECIPIENT =
      new Participant("ins_Recipient", "alice", 9, "cnv_Target");
  private static final Binding BINDING = new Binding(TOPIC, SENDER, RECIPIENT, null);
  private final Relay relay = mock(Relay.class);
  private final MessagePayloadCodec codec = spy(new MessagePayloadCodec(1024));
  private final MessagePublications messages = new RelayMessagePublications(relay, codec);

  private static MessagePayload request(String body) {
    return new MessagePayload(
        "bdm_Request",
        SENDER.address(),
        RECIPIENT.address(),
        new MessagePayload.Inline(body),
        null,
        RECIPIENT.conversation(),
        true,
        false);
  }

  private static Relay.Publication publication(String publisher, String id, RelayPayload payload) {
    return new Relay.Publication(
        TOPIC, 1, NOW, new Relay.Draft(id, publisher, NOW, null, null, payload));
  }

  @Test
  void publication_uses_the_message_identity_and_general_text_without_an_envelope_extension() {
    var payload = request("Body");
    var causation = new RelayCausation("root", "parent", 2);
    when(relay.publish(eq(TOPIC), any()))
        .thenAnswer(call -> new Relay.Publication(TOPIC, 1, NOW, call.getArgument(1)));

    Relay.Publication published = messages.publish(BINDING, payload, NOW, causation);

    assertEquals("bdm_Request", published.event().eventId());
    assertEquals(PUBLISHER, published.event().publisher());
    assertEquals(NOW, published.event().occurredAt());
    assertEquals(causation, published.event().causation());
    assertNull(published.event().correlationId());
    assertNull(published.event().causationId());
    assertEquals(new RelayPayload.Text(codec.write(payload)), published.event().payload());
    assertEquals(Optional.of(payload), messages.read("alice", published));
    verify(relay).publish(eq(TOPIC), any());
    verifyNoMoreInteractions(relay); // No topic registration, permission refetch or cursor change.
  }

  @Test
  void forged_sender_recipient_conversation_or_reply_is_refused_before_publication() {
    for (var payload :
        List.of(
            new MessagePayload(
                "bdm_Request",
                "ins_Forged",
                RECIPIENT.address(),
                new MessagePayload.Inline("Body"),
                null,
                RECIPIENT.conversation(),
                false,
                false),
            new MessagePayload(
                "bdm_Request",
                SENDER.address(),
                "ins_Other",
                new MessagePayload.Inline("Body"),
                null,
                RECIPIENT.conversation(),
                false,
                false),
            new MessagePayload(
                "bdm_Request",
                SENDER.address(),
                RECIPIENT.address(),
                new MessagePayload.Inline("Body"),
                null,
                "cnv_Other",
                false,
                false),
            new MessagePayload(
                "bdm_Request",
                SENDER.address(),
                RECIPIENT.address(),
                new MessagePayload.Inline("Body"),
                "bdm_Original",
                RECIPIENT.conversation(),
                false,
                true))) {
      assertThrows(
          IllegalArgumentException.class, () -> messages.publish(BINDING, payload, NOW, null));
    }
    verifyNoInteractions(relay);
  }

  @Test
  void foreign_accounts_wrong_project_scopes_and_forged_reply_routes_cannot_form_a_binding() {
    var foreign = new Participant("ins_Foreign", "bob", 9, "cnv_Other");
    assertThrows(IllegalArgumentException.class, () -> new Binding(TOPIC, SENDER, foreign, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Binding(new Relay.TopicKey(7, "inbox.fixture"), SENDER, RECIPIENT, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Binding(
                new Relay.TopicKey(Relay.SystemScope.SERVER, "inbox.fixture"),
                SENDER,
                RECIPIENT,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Binding(
                TOPIC,
                SENDER,
                RECIPIENT,
                new Reply("bdm_Original", SENDER.address(), RECIPIENT.address())));
    verifyNoInteractions(relay);
  }

  @Test
  void a_reply_uses_only_the_original_recipient_and_retained_return_address() {
    var inbox = new Relay.TopicKey(7, "sender.fixture");
    var binding =
        new Binding(
            inbox,
            RECIPIENT,
            SENDER,
            new Reply("bdm_Request", SENDER.address(), RECIPIENT.address()));
    var payload =
        new MessagePayload(
            "bdm_Reply",
            RECIPIENT.address(),
            SENDER.address(),
            new MessagePayload.Inline("Answer"),
            "bdm_Request",
            SENDER.conversation(),
            false,
            true);
    when(relay.publish(eq(inbox), any()))
        .thenAnswer(call -> new Relay.Publication(inbox, 1, NOW, call.getArgument(1)));
    var published = messages.publish(binding, payload, NOW, null);
    assertEquals(Optional.of(payload), messages.read("alice", published));
    verify(relay).publish(eq(inbox), any());
    verifyNoMoreInteractions(relay);
  }

  @Test
  void oversize_inline_content_writes_nothing_and_retained_outcomes_do_not_need_inline_space() {
    var small = new RelayMessagePublications(relay, new MessagePayloadCodec(4));
    assertThrows(
        IllegalArgumentException.class, () -> small.publish(BINDING, request("😀a"), NOW, null));
    verifyNoInteractions(relay);
    var retained =
        new MessagePayload(
            "bdm_Request",
            SENDER.address(),
            RECIPIENT.address(),
            new MessagePayload.Retained("bdm_Request"),
            null,
            RECIPIENT.conversation(),
            false,
            false);
    when(relay.publish(eq(TOPIC), any()))
        .thenAnswer(call -> new Relay.Publication(TOPIC, 1, NOW, call.getArgument(1)));
    var published = small.publish(BINDING, retained, NOW, null);
    assertEquals(Optional.of(retained), small.read("alice", published));
  }

  @Test
  void foreign_publishers_are_skipped_before_decoding_even_malformed_or_wrong_kind_content() {
    for (String publisher :
        List.of(
            RelayPort.publisher("alice"),
            "messaging:" + RelayPort.hash("bob"),
            "messaging:forged")) {
      for (RelayPayload payload :
          List.of(new RelayPayload.Text("not JSON"), new RelayPayload.Empty())) {
        assertTrue(
            messages.read("alice", publication(publisher, "bdm_Request", payload)).isEmpty());
      }
    }
    verify(codec, never()).read(anyString());
    verifyNoInteractions(relay); // The owning consumer, not this codec, advances past skipped rows.
  }

  @Test
  void own_publisher_corruption_and_message_identity_mismatch_fail_visibly() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            messages.read(
                "alice", publication(PUBLISHER, "bdm_Request", new RelayPayload.Text("not JSON"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            messages.read(
                "alice", publication(PUBLISHER, "bdm_Request", new RelayPayload.Empty())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            messages.read(
                "alice",
                publication(
                    PUBLISHER, "bdm_Other", new RelayPayload.Text(codec.write(request("Body"))))));
    verifyNoInteractions(relay);
  }

  @Test
  void equal_reuse_is_identical_and_conflicting_reuse_is_left_to_the_broker() {
    // Model only the Relay interface's retained-event rule. SQL constraints and locking remain
    // covered by Relay's owning tests, not by this stub's comparison.
    var retained = new AtomicReference<Relay.Publication>();
    when(relay.publish(eq(TOPIC), any()))
        .thenAnswer(
            call -> {
              var draft = call.getArgument(1, Relay.Draft.class);
              var prior = retained.get();
              if (prior != null) {
                if (!prior.event().equals(draft))
                  throw new IllegalStateException("event identity conflict");
                return prior;
              }
              var first = new Relay.Publication(TOPIC, 1, NOW, draft);
              retained.set(first);
              return first;
            });
    var payload = request("Body");
    var first = messages.publish(BINDING, payload, NOW, null);
    assertSame(first, messages.publish(BINDING, payload, NOW, null));
    assertThrows(
        IllegalStateException.class,
        () -> messages.publish(BINDING, request("Changed"), NOW, null));
    verify(relay, times(3)).publish(eq(TOPIC), any());
    verifyNoMoreInteractions(relay);
  }

  @Test
  void uncertain_delivery_is_not_replayed_and_reconciliation_is_read_only() {
    var payload = request("Body");
    var uncertain = new IllegalStateException("delivery uncertain");
    when(relay.publish(eq(TOPIC), any())).thenThrow(uncertain);
    assertSame(
        uncertain,
        assertThrows(
            IllegalStateException.class, () -> messages.publish(BINDING, payload, NOW, null)));
    when(relay.retained(TOPIC, payload.message())).thenReturn(Optional.empty());
    assertFalse(messages.retained(BINDING, payload, NOW, null).isPresent());
    var order = inOrder(relay);
    order.verify(relay).publish(eq(TOPIC), any());
    order.verify(relay).retained(TOPIC, payload.message());
    verifyNoMoreInteractions(relay);
  }

  @Test
  void reconciliation_recovers_only_an_exact_owned_publication_without_a_new_send() {
    var payload = request("Body");
    var receipt =
        publication(PUBLISHER, payload.message(), new RelayPayload.Text(codec.write(payload)));
    when(relay.retained(TOPIC, payload.message())).thenReturn(Optional.of(receipt));
    assertEquals(Optional.of(receipt), messages.retained(BINDING, payload, NOW, null));
    for (var conflict :
        List.of(
            publication(RelayPort.publisher("alice"), payload.message(), receipt.event().payload()),
            publication(
                PUBLISHER, payload.message(), new RelayPayload.Text(codec.write(request("Other")))),
            publication(PUBLISHER, "bdm_Other", receipt.event().payload()))) {
      when(relay.retained(TOPIC, payload.message())).thenReturn(Optional.of(conflict));
      assertThrows(
          IllegalStateException.class, () -> messages.retained(BINDING, payload, NOW, null));
    }
    verify(relay, times(4)).retained(TOPIC, payload.message());
    verifyNoMoreInteractions(relay);
  }

  @Test
  void owned_messages_on_system_scope_are_corruption_rather_than_an_inbox_fallback() {
    var payload = request("Body");
    var event =
        publication(PUBLISHER, payload.message(), new RelayPayload.Text(codec.write(payload)))
            .event();
    var system =
        new Relay.Publication(
            new Relay.TopicKey(Relay.SystemScope.SERVER, "inbox.fixture"), 1, NOW, event);
    assertThrows(IllegalArgumentException.class, () -> messages.read("alice", system));
    verify(codec, never()).read(anyString());
    verifyNoInteractions(relay);
  }
}
