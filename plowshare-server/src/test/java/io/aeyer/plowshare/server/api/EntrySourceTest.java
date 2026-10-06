package io.aeyer.plowshare.server.api;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.agents.Speaker;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EntrySourceTest {
  @Test
  void identities_are_immediate_sources_and_person_text_cannot_impersonate_a_message() {
    assertEquals(
        new EntryView.SourceView("message", "msg_fixture"),
        EntryView.SourceView.of(Speaker.message("msg_fixture")));
    UUID admission = UUID.randomUUID();
    assertEquals(
        new EntryView.SourceView("relay", admission.toString()),
        EntryView.SourceView.of(Speaker.relay(admission)));
    assertEquals(
        new EntryView.SourceView("relay", admission.toString()),
        EntryView.SourceView.of(Speaker.event("relay " + admission)));
    assertEquals(
        new EntryView.SourceView("event", "relay other"),
        EntryView.SourceView.of(Speaker.event("relay other")));
    assertEquals(
        new EntryView.SourceView("person", "message msg_fixture"),
        EntryView.SourceView.of(Speaker.person("message msg_fixture")));
    assertEquals(
        new EntryView.SourceView("unknown", null), EntryView.SourceView.of(Speaker.harness()));
    assertEquals(
        new EntryView.SourceView("unknown", null), EntryView.SourceView.of(Speaker.person(null)));
    assertNull(EntryView.SourceView.of(null));
  }

  @Test
  void missing_identity_is_not_a_known_source() {
    assertThrows(IllegalArgumentException.class, () -> Speaker.message(" "));
    assertThrows(IllegalArgumentException.class, () -> Speaker.message("msg\nfixture"));
    assertThrows(IllegalArgumentException.class, () -> new EntryView.SourceView("message", null));
    assertThrows(IllegalArgumentException.class, () -> new EntryView.SourceView("unknown", "msg"));
  }
}
