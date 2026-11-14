package io.aeyer.plowshare.server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MessageReturnCodecTest {
  private static final String SOURCE = "22222222-2222-2222-2222-222222222222";
  private static final String REQUEST = "11111111-1111-1111-1111-111111111111";
  private static final String VALID =
      "{\"schema\":\"plowshare-message-return/1\",\"requestId\":\""
          + REQUEST
          + "\",\"sourceEvent\":\""
          + SOURCE
          + "\",\"body\":\"Remote answer 😀\",\"finalReply\":true}";

  @Test
  void validates_exact_generic_return_without_adapter_or_instance_authority() {
    assertEquals(
        new MessageExports.Return(
            UUID.fromString(REQUEST), UUID.fromString(SOURCE), "Remote answer 😀", true),
        MessageReturnCodec.read(VALID));
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "[]", "null", "{\"schema\":\"plowshare-message-return/1\"}"})
  void missing_or_nonobject_values_are_refused(String value) {
    assertThrows(IllegalArgumentException.class, () -> MessageReturnCodec.read(value));
  }

  @Test
  void unknown_fields_duplicates_noncanonical_uuid_and_coercion_are_refused() {
    for (String value :
        new String[] {
          VALID.replace("\"body\":", "\"instance\":\"ins_Forged\",\"body\":"),
          VALID.replace("\"body\":", "\"body\":\"Other\",\"body\":"),
          VALID.replace(REQUEST, "1-1-1-1-1"),
          VALID.replace("true", "\"true\""),
          VALID.replace("Remote answer 😀", " "),
          VALID + " {}"
        }) assertThrows(IllegalArgumentException.class, () -> MessageReturnCodec.read(value));
  }
}
