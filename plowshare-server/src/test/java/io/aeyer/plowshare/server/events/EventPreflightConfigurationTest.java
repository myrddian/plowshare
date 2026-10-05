package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EventPreflightConfigurationTest {
  @Test
  void database_copy_connections_are_read_only_without_opening_a_connection() {
    var source =
        EventPreflightConfiguration.databaseCopy(
            "jdbc:postgresql://example.invalid/restored", "reader", "fixture");
    assertEquals(
        "-c default_transaction_read_only=on",
        source.getConnectionProperties().getProperty("options"));
    assertEquals("jdbc:postgresql://example.invalid/restored", source.getUrl());
  }

  @Test
  void positional_connection_settings_are_refused_before_a_connection_can_open() {
    assertThrows(
        IllegalArgumentException.class,
        () -> EventPreflightConfiguration.main(new String[] {"ignored"}));
  }

  @Test
  void urls_cannot_override_read_only_connections_or_embed_credentials() {
    for (String url :
        new String[] {
          "jdbc:postgresql://example.invalid/restored?options=-c%20default_transaction_read_only=off",
          "jdbc:postgresql://example.invalid/restored?o%70tions=anything",
          "jdbc:postgresql://example.invalid/restored?password=private",
          "jdbc:postgresql://reader:private@example.invalid/restored",
          "jdbc:postgresql://example.invalid/restored#private"
        })
      assertThrows(
          IllegalArgumentException.class,
          () -> EventPreflightConfiguration.databaseCopy(url, "reader", "fixture"));
    assertDoesNotThrow(
        () ->
            EventPreflightConfiguration.databaseCopy(
                "jdbc:postgresql://example.invalid/restored?sslmode=verify-full",
                "reader",
                "fixture"));
  }
}
