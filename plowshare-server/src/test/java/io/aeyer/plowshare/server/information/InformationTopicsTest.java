package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPortProperties;
import java.util.HashSet;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/**
 * The derived information topic name: that it is the name this subsystem actually uses, that it
 * separates scopes without colliding, that it is stable, and that it discloses nothing.
 *
 * <p>The schema constraint it has to satisfy is deliberately <b>not</b> pinned here. Asserting a
 * regular expression in Java would only restate the one in the migration, and the question is
 * whether PostgreSQL accepts the name — so that pin inserts it through the real schema, in {@code
 * RelayInformationEventsDatabaseTest}.
 */
class InformationTopicsTest {
  /**
   * A project ID with no significance beyond being the one {@code RelayInformationEventsTest} uses.
   */
  private static final long PROJECT = 7;

  /**
   * The expected name, written out rather than derived.
   *
   * <p>This is the one place the literal and the derivation are tied together, and it is what makes
   * every other pin that spells the name a pin rather than a tautology. It also fixes the recipe:
   * the digest input is {@code 'plowshare.information.events/1:project:7'}, which is what {@code
   * V190__information_topic_derived_name.sql} reconstructs in SQL from {@code scope_key}, so a
   * change to the namespace, the prefix or the scope spelling fails here and is then visible as a
   * migration that no longer agrees.
   */
  private static final String EXPECTED =
      "information.events.54d797420829941468803660fe5007bd0d8a59dacf07b04f3df76ae82f9b7e32";

  @Test
  void the_name_is_the_documented_derivation_of_the_scope_and_nothing_else() {
    assertEquals(EXPECTED, InformationTopics.of(new Relay.ProjectScope(PROJECT)));
  }

  @Test
  void the_same_scope_derives_the_same_name_however_often_it_is_asked() {
    var scope = new Relay.ProjectScope(PROJECT);
    // Publisher and consumer derive independently, in different requests and different processes,
    // and a cursor is kept per topic name: a derivation that varied would strand it.
    assertEquals(InformationTopics.of(scope), InformationTopics.of(scope));
    assertEquals(
        InformationTopics.of(scope),
        InformationTopics.of(new Relay.ProjectScope(PROJECT)),
        "equal scopes are the same scope; identity of the object must not matter");
  }

  @Test
  void different_scopes_derive_different_names() {
    var names = new HashSet<String>();
    LongStream.rangeClosed(1, 64)
        .forEach(project -> names.add(InformationTopics.of(new Relay.ProjectScope(project))));
    names.add(InformationTopics.of(Relay.SystemScope.SERVER));
    assertEquals(
        65,
        names.size(),
        "two scopes sharing a topic would mix two audiences into one subscription");
  }

  @Test
  void the_name_spells_neither_a_project_name_nor_an_account_handle() {
    // The scope's identity is numeric and the digest consumes it, so there is nothing legible
    // left. Asserted against the names a real deployment would use rather than against a shape.
    String name = InformationTopics.of(new Relay.ProjectScope(PROJECT));
    for (String secret : List.of("atlas", "author", "viewer", "personal", "client:acme"))
      assertFalse(name.contains(secret), "the topic name must not carry " + secret);
    assertEquals(
        "information.events.",
        name.substring(0, "information.events.".length()),
        "the family prefix stays legible so an operator can tell which subsystem owns the topic");
    assertTrue(
        name.substring("information.events.".length()).matches("[0-9a-f]{64}"),
        "and the rest is a digest, not a rendering of anything");
  }

  /**
   * The port grant the derived name withdraws.
   *
   * <p>{@code RelayPortProperties.permits} is pure data: Relay reserves no topic names, so a
   * deployment that bound this topic as an external ingress port would be permitted to publish to
   * it. That is the contract nobody intended to offer, and it was reachable only because the name
   * was a literal anyone could spell. The pin lives here, in the package that owns the name,
   * because moving it anywhere else would require that package to know the name again.
   */
  @Test
  void no_configuration_opens_the_derived_topic_as_an_external_port() {
    String topic = InformationTopics.of(new Relay.ProjectScope(PROJECT));
    var shipped = new RelayPortProperties();
    for (var direction : RelayPortProperties.Direction.values())
      assertFalse(
          shipped.permits("reviewer", "atlas", topic, direction, null),
          "the shipped default grants no port at all");

    // And a deployment that binds its own topics does not reach this one by doing so.
    var configured = new RelayPortProperties();
    configured.setBindings(
        List.of(
            new RelayPortProperties.Binding(
                "atlas",
                "custom.topic",
                "reviewer",
                RelayPortProperties.Direction.INGRESS,
                List.of())));
    for (var direction : RelayPortProperties.Direction.values())
      assertFalse(
          configured.permits("reviewer", "atlas", topic, direction, null),
          "an information event is published by the server, never through a port");
  }
}
