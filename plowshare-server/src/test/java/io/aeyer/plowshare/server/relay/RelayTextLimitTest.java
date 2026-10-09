package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.RelayPort;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class RelayTextLimitTest {
  @Test
  void configuration_defaults_to_five_mib_and_binds_up_to_fifty_mib() {
    assertEquals(5 * 1024 * 1024, new RelayProperties().getMaxTextBytes());
    var binder =
        new Binder(
            new MapConfigurationPropertySource(
                Map.of("plowshare.relay.max-text-bytes", "52428800")));
    var properties = binder.bind("plowshare.relay", Bindable.of(RelayProperties.class)).get();
    assertEquals(RelayPort.MAX_TEXT_BYTES, properties.textLimit().bytes());
    for (int invalid : new int[] {-1, 0, RelayPort.MAX_TEXT_BYTES + 1}) {
      assertThrows(
          IllegalArgumentException.class, () -> new RelayProperties().setMaxTextBytes(invalid));
      assertThrows(
          org.springframework.boot.context.properties.bind.BindException.class,
          () ->
              new Binder(
                      new MapConfigurationPropertySource(
                          Map.of("plowshare.relay.max-text-bytes", Integer.toString(invalid))))
                  .bind("plowshare.relay", Bindable.of(RelayProperties.class)));
    }
  }

  @Test
  void selected_allowance_counts_raw_utf8_and_does_not_restrict_other_payload_families() {
    var limit = new RelayTextLimit(4);
    for (String valid : new String[] {"xxxx", "éé", "😀", "\u0001".repeat(4)}) {
      limit.validateNew(new RelayPayload.Text(valid));
      assertThrows(
          IllegalArgumentException.class,
          () -> limit.validateNew(new RelayPayload.Text(valid + "x")));
    }
    limit.validateNew(new RelayPayload.Empty());
    assertThrows(IllegalArgumentException.class, () -> new RelayTextLimit(0));
  }
}
