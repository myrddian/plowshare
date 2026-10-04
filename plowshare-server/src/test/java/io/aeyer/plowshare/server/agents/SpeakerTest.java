package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Who spoke an utterance, in the spec's own spellings (2026-09-28-the-log-is-the-source §2). */
class SpeakerTest {

  @Test
  void the_harness_names_its_source_in_the_spec_s_words() {
    assertEquals("orchestration orc_1", Speaker.orchestration("orc_1").name());
    assertEquals("approval apr_1", Speaker.approval("apr_1").name());
    assertEquals("event nightly", Speaker.event("nightly").name());
    assertEquals("harness", Speaker.harness().name());
    assertEquals("harness", Speaker.harness().kind().wireName());
    assertEquals("person", Speaker.person("enzo").kind().wireName());
    assertEquals("enzo", Speaker.person("enzo").name());
  }

  @Test
  void a_person_nobody_can_name_is_still_a_person() {
    assertNull(Speaker.person(null).name());
    assertNull(Speaker.person("  ").name());
    assertEquals(Speaker.Kind.PERSON, Speaker.person(null).kind());
  }

  @Test
  void a_harness_utterance_that_names_no_source_is_refused() {
    assertThrows(IllegalArgumentException.class, () -> new Speaker(Speaker.Kind.HARNESS, null));
    assertThrows(IllegalArgumentException.class, () -> Speaker.Kind.of("model"));
  }

  @Test
  void an_older_utterance_reads_as_a_person_s_and_no_other_kind_has_a_speaker() {
    assertEquals(Speaker.person(null), Speaker.read(EntryKind.UTTERANCE, null, null));
    assertEquals(
        Speaker.orchestration("orc_1"),
        Speaker.read(EntryKind.UTTERANCE, "harness", "orchestration orc_1"));
    assertEquals(Speaker.person("enzo"), Speaker.read(EntryKind.UTTERANCE, "person", "enzo"));
    assertNull(Speaker.read(EntryKind.ANSWER, null, null));
  }

  @Test
  void only_an_utterance_carries_a_speaker_and_a_wither_keeps_it() {
    LoggedEntry said = LoggedEntry.utterance("hi", Speaker.person("enzo"));
    assertEquals(Speaker.person("enzo"), said.speaker());
    assertEquals(Speaker.person("enzo"), said.took(Duration.ofMillis(3)).speaker());
    assertNull(LoggedEntry.utterance("hi", null).speaker());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LoggedEntry(EntryKind.ANSWER, "x", null, List.of(), null, null, Speaker.harness()));
  }
}
