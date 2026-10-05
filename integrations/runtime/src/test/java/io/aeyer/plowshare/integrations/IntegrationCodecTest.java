package io.aeyer.plowshare.integrations;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.integrations.IntegrationContracts.*;
import io.aeyer.plowshare.protocol.IntegrationPayload.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class IntegrationCodecTest {
  @TempDir Path directory;

  @Test
  void nested_mixed_effects_decode_completely_with_duplicate_keys_and_wrong_fields_refused()
      throws Exception {
    var output =
        IntegrationCodec.output(
            Json.parse(
                """
      {"effects":[{"kind":"action","binding":"house","action":"notify","key":"notify","parameters":{"message":"ok"}},
       {"kind":"read","binding":"house","entities":["temperature"],"key":"read"}],"state":{"count":1}}
      """));
    assertEquals(2, output.effects().size());
    assertInstanceOf(StateNumber.class, output.state().variables().get("count"));
    for (String invalid :
        List.of(
            "{\"effects\":[{\"kind\":\"action\",\"action\":\"notify\",\"key\":\"notify\",\"parameters\":{\"message\":{\"raw\":true}}}],\"state\":{}}",
            "{\"effects\":[],\"state\":{\"nested\":{\"raw\":true}}}",
            "{\"effects\":[],\"state\":{\"list\":[1]}}",
            "{\"effects\":[],\"state\":{},\"unknown\":true}",
            "{\"effects\":[{\"kind\":\"read\",\"entities\":[\"temperature\"],\"key\":\"same\"},{\"kind\":\"read\",\"entities\":[\"temperature\"],\"key\":\"same\"}],\"state\":{}}"))
      assertThrows(
          IllegalArgumentException.class, () -> IntegrationCodec.output(Json.parse(invalid)));
  }

  @Test
  void absent_route_defaults_are_explicit_but_null_and_coerced_values_are_refused()
      throws Exception {
    assertEquals(RouteState.empty(), IntegrationCodec.decode(Json.object(), RouteState.class));
    for (String invalid :
        List.of(
            "{\"high\":\"false\"}",
            "{\"high\":null}",
            "{\"started\":1.5}",
            "{\"started\":\"1\"}",
            "{\"unknown\":1}"))
      assertThrows(
          IllegalArgumentException.class,
          () -> IntegrationCodec.decode(Json.parse(invalid), RouteState.class));
    for (String invalid :
        List.of("{\"script\":null}", "{\"routes\":null}", "{\"script\":{\"secret\":{}}}"))
      assertThrows(
          IllegalArgumentException.class, () -> IntegrationCodec.bindingState(Json.parse(invalid)));
  }

  @Test
  void retained_completion_and_held_wire_shapes_round_trip_without_changing_families()
      throws Exception {
    Draft completion = new Draft();
    completion.kind = "handler";
    completion.binding = "house";
    completion.handler = "onCompletion";
    completion.fingerprint = "pinned";
    completion.status = "QUEUED";
    completion.event =
        new EventEnvelope(new Completion("run-fixture", "completed", "report", null));
    var encoded = IntegrationCodec.tree(completion.build());
    assertFalse(encoded.path("event").has("type"));
    assertEquals(completion.build(), IntegrationCodec.entry(encoded));
    Reading reading =
        new Reading(
            "temperature",
            "30",
            "available",
            Map.of(),
            "°C",
            null,
            null,
            "epoch",
            null,
            Instant.EPOCH.toString(),
            null);
    Draft timer = new Draft();
    timer.kind = "handler";
    timer.binding = "house";
    timer.handler = "onEvent";
    timer.fingerprint = "pinned";
    timer.status = "QUEUED";
    timer.heldRoute = "heat";
    timer.hold = new Hold("event-fixture", "session", "epoch", 1);
    timer.event = new EventEnvelope(new Held(reading, "heat", 10, 1, false));
    assertEquals(timer.build(), IntegrationCodec.entry(IntegrationCodec.tree(timer.build())));
  }

  @Test
  void unsupported_retained_data_blocks_before_recovery_and_retains_original_bytes()
      throws Exception {
    String source =
        """
      {"version":1,"records":{"event-fixture":{"kind":"handler","binding":"house","handler":"onEvent",
       "fingerprint":"pinned","status":"QUEUED","event":{"type":"unregistered","private":"private-fixture"}}},"state":{}}
      """;
    Path file = directory.resolve("journal.json");
    Files.writeString(file, source);
    try (Journal journal = new Journal(directory)) {
      IOException failure = assertThrows(IOException.class, journal::verifyDtos);
      assertFalse(failure.toString().contains("private-fixture"));
      assertNull(failure.getCause());
      assertEquals(source, Files.readString(file));
    }
  }

  @Test
  void parse_errors_never_disclose_payloads_or_accept_duplicate_or_trailing_values() {
    for (String value :
        List.of(
            "{\"state\":{\"secret\":\"private-fixture\"},\"state\":{}}",
            "{} {}",
            "{\"private-fixture\"")) {
      IOException failure =
          assertThrows(IOException.class, () -> IntegrationCodec.read(value, ScriptOutput.class));
      assertFalse(failure.toString().contains("private-fixture"));
      assertNull(failure.getCause());
    }
  }
}
