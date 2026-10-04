package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The floor: what this server defines when nobody has configured anything. */
class ClasspathDefinitionsTest {

  @Test
  void the_shipped_definitions_are_all_enumerated() {
    List<DefinitionSource.Definition> found = new ClasspathDefinitions().list();

    Set<String> names =
        found.stream().map(DefinitionSource.Definition::name).collect(Collectors.toSet());

    // The three REQUIRED agents plus the two a person talks to. Asserted by
    // name rather than by count so that adding a definition does not fail
    // this test for the wrong reason.
    assertTrue(
        names.containsAll(
            Set.of("scribe", "promotion_judge", "learner", "interlocutor", "librarian")),
        "shipped set was " + names);
  }

  /**
   * The floor holds what ships in {@code bots/} as well, and it has to.
   *
   * <p>A deployment always has this jar and may have nothing else, so "a fresh deployment has
   * somebody to talk to before anyone writes a definition" is a claim about exactly this list. The
   * seed reads both shipped directories for the reason {@code AgentsConfig} layers both {@code
   * global/} ones: which of the two a definition sits in is an operator's filing and never what the
   * loader reads.
   */
  @Test
  void the_shipped_bot_is_on_the_floor_too() {
    Set<String> names =
        new ClasspathDefinitions()
            .list().stream().map(DefinitionSource.Definition::name).collect(Collectors.toSet());

    Set<String> bots;
    try (java.util.stream.Stream<java.nio.file.Path> files =
        java.nio.file.Files.list(java.nio.file.Path.of("src/main/resources/bots"))) {
      // By file and not by name, so a bot can be renamed, rewritten or
      // replaced without this test knowing who it is.
      bots =
          files
              .map(path -> path.getFileName().toString())
              .filter(file -> file.endsWith(".md"))
              .map(file -> file.substring(0, file.length() - ".md".length()))
              .collect(Collectors.toSet());
    } catch (java.io.IOException unreadable) {
      throw new java.io.UncheckedIOException(unreadable);
    }
    assertTrue(names.containsAll(bots), "shipped set was " + names + ", bots are " + bots);
  }

  @Test
  void a_definition_carries_its_text_and_a_source_that_is_not_a_path() {
    DefinitionSource.Definition scribe =
        new ClasspathDefinitions()
            .list().stream().filter(d -> d.name().equals("scribe")).findFirst().orElseThrow();

    assertTrue(scribe.text().startsWith("---"), "frontmatter missing");
    assertEquals("the shipped definition `scribe`", scribe.origin());
  }

  @Test
  void a_location_with_nothing_in_it_lists_nothing_rather_than_throwing() {
    assertEquals(List.of(), new ClasspathDefinitions("agents-that-do-not-exist").list());
  }

  @Test
  void the_shipped_orchestrations_are_listed_from_their_own_location() {
    List<String> names =
        new ClasspathDefinitions(ClasspathDefinitions.SHIPPED_ORCHESTRATIONS)
            .list().stream().map(DefinitionSource.Definition::name).toList();
    assertTrue(names.contains("code_implementation"), names.toString());
  }
}
