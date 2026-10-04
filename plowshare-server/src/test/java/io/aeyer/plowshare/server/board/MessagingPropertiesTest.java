package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class MessagingPropertiesTest {
  @Test
  void personal_routing_defaults_are_enabled_and_configuration_binds_each_direction() {
    var defaults = new MessagingProperties();
    assertTrue(defaults.getPersonal().isSendToAnyProject());
    assertTrue(defaults.getPersonal().isAcceptFromAnyProject());
    var configured =
        new Binder(
                new MapConfigurationPropertySource(
                    Map.of(
                        "plowshare.messaging.personal.send-to-any-project", "false",
                        "plowshare.messaging.personal.accept-from-any-project", "true")))
            .bind("plowshare.messaging", Bindable.of(MessagingProperties.class))
            .get();
    assertFalse(configured.getPersonal().isSendToAnyProject());
    assertTrue(configured.getPersonal().isAcceptFromAnyProject());
  }
}
