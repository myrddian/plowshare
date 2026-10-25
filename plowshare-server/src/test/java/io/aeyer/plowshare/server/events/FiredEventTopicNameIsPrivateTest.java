package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.relay.Relay;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The pin that stops the fired-event topic name leaking back into a shared place.
 *
 * <p>Structural rather than behavioural, because "nothing outside this subsystem knows the topic
 * name" is a statement about every file including ones added later, and no behavioural test can
 * make it. A derived name withdraws an address only for as long as nobody writes it down again — in
 * a constant, a deployment's configuration, a client, a fixture — and the moment one file does, the
 * property is gone while every other test still passes. {@code InformationTopicNameIsPrivateTest}
 * establishes this shape; the walking machinery is in {@link SourceScan}, shared with {@link
 * ScheduleTopicNameIsPrivateTest}.
 */
class FiredEventTopicNameIsPrivateTest {

  private static final Path ROOT = SourceScan.root();

  /**
   * The derived name itself, anywhere in the repository.
   *
   * <p>The whole tree and not just the server: a topic name leaks by being written into a client,
   * an SDK, a deployment manifest or a document, and those are the places a shared identifier ends
   * up. Both scopes are checked, because the derivation is total over them and either could be
   * copied.
   */
  @Test
  void the_derived_topic_name_appears_in_no_file_outside_the_subsystem_that_derives_it() {
    for (Relay.Scope scope : List.of(Relay.SystemScope.SERVER, new Relay.ProjectScope(7))) {
      String derived = EventTopics.fired(scope);
      assertEquals(
          List.of(),
          SourceScan.containing(ROOT, derived).stream()
              .filter(path -> !SourceScan.ownedByEvents(ROOT, path))
              .map(path -> ROOT.relativize(path).toString())
              .sorted()
              .toList(),
          "a derived topic name written down outside its own subsystem is an address again");
    }
  }

  /**
   * And the legible prefix names nothing outside the subsystem either.
   *
   * <p>The prefix on its own is not an address — nothing can publish to {@code event.fired.} — but
   * a file outside this package that spelled it would be the first half of writing the name down,
   * and is the shape a later change would take if it needed to refer to the topic.
   */
  @Test
  void the_topics_legible_prefix_names_nothing_outside_the_subsystem() {
    String prefix =
        EventTopics.fired(Relay.SystemScope.SERVER).substring(0, "event.fired.".length());
    assertEquals(
        List.of(),
        SourceScan.containing(ROOT, prefix).stream()
            .filter(path -> !SourceScan.ownedByEvents(ROOT, path))
            .map(path -> ROOT.relativize(path).toString())
            .sorted()
            .toList(),
        "the fired-event topic's namespace must not be spelled outside the subsystem that owns it");
  }

  /**
   * The scan is capable of finding something, so an empty result above means something.
   *
   * <p>{@code event.fire} is the operation's name: a genuinely public identifier that the protocol
   * and every SDK hold, which is exactly what a topic name is not. It is therefore the right
   * control — present in many files, and correctly not the topic.
   */
  @Test
  void the_scan_reaches_the_files_it_claims_to_check() {
    String operation = String.join(".", "event", "fire");
    var found =
        SourceScan.containing(ROOT, operation).stream()
            .map(path -> ROOT.relativize(path).toString().replace('\\', '/'))
            .toList();
    assertTrue(
        found.stream().anyMatch(path -> path.startsWith("sdk/")),
        "the operation name must be reachable in the SDKs, which is a different identifier: "
            + found);
    assertTrue(
        found.contains(
            "plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FrameTypes.java"),
        "and in the frame catalogue: " + found);
  }
}
