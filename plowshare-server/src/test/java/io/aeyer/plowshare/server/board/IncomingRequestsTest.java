package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Incoming;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IncomingRequestsTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String REQUEST =
      """
      {"project":"project","client":"remote","agent":"interlocutor",
       "requestId":"00000000-0000-0000-0000-000000000001","body":"first\\nsecond",
       "source":{"messageId":"external","role":"ROLE_USER","parts":[{"text":"first"},{"text":"second"}]}}
      """;

  private static Incoming.Receive read(String value) throws Exception {
    Map<String, Object> wire = JSON.readValue(value, new TypeReference<>() {});
    return IncomingRequests.read(wire, Incoming.Receive.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"project", "client", "agent", "body"})
  void declared_text_is_never_coerced_from_a_number(String field) throws Exception {
    var root = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(REQUEST);
    root.put(field, 42);
    assertThrows(CallerFault.class, () -> read(root.toString()));
  }

  @Test
  void provenance_must_match_the_admitted_work_before_dispatch() throws Exception {
    assertEquals("first\nsecond", read(REQUEST).body());
    var source =
        new Incoming.Source(
            "external",
            List.of(new Incoming.TextPart("body")),
            new Incoming.Metadata("/skill:review"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Incoming.Receive(
                "project",
                "remote",
                "interlocutor",
                UUID.randomUUID(),
                null,
                "forged",
                "/skill:review",
                source));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Incoming.Receive(
                "project",
                "remote",
                "interlocutor",
                UUID.randomUUID(),
                null,
                "body",
                "/skill:different",
                source));
    var withContext =
        new Incoming.Source(
            "external",
            "ROLE_USER",
            null,
            UUID.randomUUID(),
            source.parts(),
            source.metadata(),
            null,
            null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Incoming.Receive(
                "project",
                "remote",
                "interlocutor",
                UUID.randomUUID(),
                null,
                "body",
                "/skill:review",
                withContext));
  }

  @Test
  void unknown_nested_source_fields_are_refused_while_future_frame_fields_are_tolerated()
      throws Exception {
    var root = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(REQUEST);
    root.put("futureFrameFlag", true);
    assertNotNull(read(root.toString()));
    root.withObject("source").put("uncheckedInstruction", "execute");
    assertThrows(CallerFault.class, () -> read(root.toString()));
  }
}
