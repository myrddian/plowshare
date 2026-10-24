package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.relay.Relay;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The pin that stops the scheduled-occurrence topic name leaking back into a shared place, and that
 * stops the withdrawn literal reappearing as a topic.
 *
 * <p>Structural rather than behavioural, for the reason given on {@link
 * FiredEventTopicNameIsPrivateTest}: "nothing outside this subsystem knows the topic name" is a
 * statement about every file including ones added later, and no behavioural test can make it. The
 * file-walking machinery is shared with that class.
 */
class ScheduleTopicNameIsPrivateTest {
  /** The literal the name used to be, spelled so this file is not its own counterexample. */
  private static final String WITHDRAWN = String.join(".", "schedule", "due");

  private static final Path ROOT = SourceScan.root();

  /**
   * Where the withdrawn literal may still legitimately appear as a quoted string.
   *
   * <p>{@code RelayRouteCodec} parses a routing manifest's {@code kind} field, where {@code
   * schedule.due} is the wire spelling of the {@code SCHEDULE_DUE} payload kind alongside {@code
   * empty}, {@code text} and {@code lifecycle}. That is a genuinely public identifier that
   * operators write into manifests, which is exactly what a topic name is not; {@code
   * RelayRouteProgramTest} spells it for the same reason. Renaming it would be a change to the
   * manifest contract rather than to this subsystem's private vocabulary, and the payload kind is
   * explicitly out of scope. The migrations name the old topic by necessity: V117 declared the
   * agreement, V188 and V189 refer to it, and V191's job is to rename it.
   */
  private static final List<String> SPELLS_THE_PAYLOAD_KIND =
      List.of(
          "plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/RelayRouteCodec.java",
          "plowshare-server/src/test/java/io/aeyer/plowshare/server/relay/RelayRouteProgramTest.java");

  /**
   * The derived name itself, anywhere in the repository.
   *
   * <p>Both scopes, because both are live for this family: a file-managed schedule publishes under
   * its project and a projectless one under the server scope.
   */
  @Test
  void the_derived_topic_name_appears_in_no_file_outside_the_subsystem_that_derives_it() {
    for (Relay.Scope scope : List.of(Relay.SystemScope.SERVER, new Relay.ProjectScope(7))) {
      String derived = EventTopics.scheduled(scope);
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
   * And the withdrawn literal names no Relay topic in the server any more.
   *
   * <p>Quoted strings only: a comment or a javadoc that explains the history is not an address, and
   * forbidding prose would mean deleting the explanation of why the name is gone. The two files
   * that spell the payload kind are exempt and documented above, and the shipped migrations are
   * excluded because they are never edited.
   */
  @Test
  void the_withdrawn_literal_names_no_relay_topic_anywhere_in_the_server() {
    assertEquals(
        List.of(),
        SourceScan.containing(ROOT.resolve("plowshare-server/src"), "\"" + WITHDRAWN + "\"")
            .stream()
            .map(path -> ROOT.relativize(path).toString().replace('\\', '/'))
            .filter(path -> !path.contains("/db/migration/"))
            .filter(path -> !SPELLS_THE_PAYLOAD_KIND.contains(path))
            .sorted()
            .toList(),
        "the schedule topic's old literal must not survive as a topic name");
  }

  /**
   * The wake topics are untouched, and must stay that way.
   *
   * <p>They are the counter-case this change had to be careful not to sweep up: {@code
   * message.wake.requested} and {@code board.wake.requested} are Relay's <em>own</em> topics,
   * published by {@code RelayInternalWorkers} and read by native consumers, so the broker naming
   * them is a system naming its internals rather than policing a consumer's vocabulary. A later
   * change that derived these too would be wrong, and this says so.
   */
  @Test
  void the_wake_topics_are_still_named_in_relay_core() {
    for (String core :
        List.of(
            "plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/Relay.java",
            "plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/RelayInternalWorkers.java")) {
      String text = SourceScan.text(ROOT.resolve(core));
      assertTrue(text.contains("\"message.wake.requested\""), core);
      assertTrue(text.contains("\"board.wake.requested\""), core);
    }
    // And the agreement that binds them to their payload family is still enforced there.
    assertTrue(
        SourceScan.text(
                ROOT.resolve(
                    "plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/Relay.java"))
            .contains("Native wake families require their private system topics"));
  }

  /** The scan is capable of finding something, so an empty result above means something. */
  @Test
  void the_scan_reaches_the_files_it_claims_to_check() {
    var found =
        SourceScan.containing(ROOT, "\"" + WITHDRAWN + "\"").stream()
            .map(path -> ROOT.relativize(path).toString().replace('\\', '/'))
            .toList();
    assertTrue(
        found.contains(SPELLS_THE_PAYLOAD_KIND.getFirst()),
        "the payload kind's wire spelling must be reachable by this scan: " + found);
  }
}
