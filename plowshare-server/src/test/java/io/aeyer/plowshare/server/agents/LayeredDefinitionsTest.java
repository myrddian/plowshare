package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LayeredDefinitionsTest {

  /** A source with fixed contents, so these tests are about precedence only. */
  private static DefinitionSource source(String describe, String... namesAndText) {
    List<DefinitionSource.Definition> defs = new java.util.ArrayList<>();
    for (int i = 0; i < namesAndText.length; i += 2) {
      defs.add(
          new DefinitionSource.Definition(
              namesAndText[i], describe + ":" + namesAndText[i], namesAndText[i + 1]));
    }
    return new DefinitionSource() {
      @Override
      public String describe() {
        return describe;
      }

      @Override
      public List<Definition> list() {
        return List.copyOf(defs);
      }
    };
  }

  @Test
  void layers_are_additive_when_nothing_clashes() {
    DefinitionSource layered =
        new LayeredDefinitions(
            List.of(source("project", "mine", "A"), source("seed", "librarian", "B")));

    assertEquals(
        List.of("librarian", "mine"),
        layered.list().stream().map(DefinitionSource.Definition::name).sorted().toList());
  }

  @Test
  void the_earlier_layer_wins_a_clash() {
    DefinitionSource layered =
        new LayeredDefinitions(
            List.of(source("global", "librarian", "GLOBAL"), source("seed", "librarian", "SEED")));

    List<DefinitionSource.Definition> found = layered.list();
    assertEquals(1, found.size());
    assertEquals("GLOBAL", found.get(0).text());
    assertEquals("global:librarian", found.get(0).origin());
  }

  @Test
  void describe_names_every_layer_in_order_so_a_log_line_says_what_was_searched() {
    DefinitionSource layered = new LayeredDefinitions(List.of(source("global"), source("seed")));

    assertTrue(layered.describe().contains("global"), layered.describe());
    assertTrue(layered.describe().contains("seed"), layered.describe());
  }

  @Test
  void no_layers_is_an_empty_set_rather_than_a_failure() {
    assertEquals(List.of(), new LayeredDefinitions(List.of()).list());
  }

  @Test
  void the_layer_list_is_copied_so_a_caller_cannot_change_it_afterwards() {
    var mutableLayers =
        new java.util.ArrayList<>(
            java.util.List.of(source("project", "mine", "A"), source("seed", "librarian", "B")));
    DefinitionSource layered = new LayeredDefinitions(mutableLayers);

    // Verify initial state
    assertEquals(2, layered.list().size());

    // Mutate the original list
    mutableLayers.add(source("client", "extra", "C"));

    // Verify the LayeredDefinitions instance is unaffected
    assertEquals(2, layered.list().size());
    assertEquals(
        List.of("librarian", "mine"),
        layered.list().stream().map(DefinitionSource.Definition::name).sorted().toList());
  }

  @Test
  void the_returned_list_is_immutable_and_rejects_modification() {
    DefinitionSource layered = new LayeredDefinitions(List.of(source("project", "mine", "A")));

    List<DefinitionSource.Definition> returned = layered.list();
    org.junit.jupiter.api.Assertions.assertThrows(
        UnsupportedOperationException.class,
        () -> returned.add(new DefinitionSource.Definition("new", "origin", "text")));
  }
}
