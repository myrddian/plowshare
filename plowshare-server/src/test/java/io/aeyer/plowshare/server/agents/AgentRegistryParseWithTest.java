package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentRegistryParseWithTest {

  private static final Set<String> TOOLS = Set.of("file_read");

  private static DefinitionSource.Definition entry(String extra) {
    return new DefinitionSource.Definition(
        "conduct",
        "test",
        "---\nname: conduct\n"
            + "description: d\nmodel: m\nmax-turns: 2\nmax-model-calls: 4\n"
            + "tools: [file_read]\n"
            + extra
            + "---\nYou conduct.\n");
  }

  @Test
  void extra_keys_are_set_aside_and_the_rest_parses_as_an_agent() {
    AgentRegistry.Parsed parsed =
        AgentRegistry.parseWith(
            entry("stages:\n  - {id: goal}\nmax-returns: 2\n"),
            TOOLS,
            new LinkedHashMap<>(),
            Set.of("stages", "max-returns"),
            Set.of());

    assertEquals("conduct", parsed.definition().name());
    assertEquals(List.of("file_read"), parsed.definition().tools());
    assertEquals(Set.of("stages", "max-returns"), parsed.extras().keySet());
    assertEquals(2, parsed.extras().get("max-returns"));
  }

  @Test
  void a_refused_key_is_refused_by_name() {
    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                AgentRegistry.parseWith(
                    entry("bot: true\n"),
                    TOOLS,
                    new LinkedHashMap<>(),
                    Set.of("stages"),
                    Set.of("bot")));

    assertEquals(
        "the agent definition 'conduct' (test) has the frontmatter key 'bot', which"
            + " this kind of definition does not take",
        refused.getMessage());
  }

  @Test
  void an_unknown_key_lists_the_extra_keys_and_not_the_refused_ones() {
    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                AgentRegistry.parseWith(
                    entry("stagse: []\n"),
                    TOOLS,
                    new LinkedHashMap<>(),
                    Set.of("stages"),
                    Set.of("bot")));

    assertTrue(refused.getMessage().contains("unrecognised frontmatter key 'stagse'"));
    assertTrue(refused.getMessage().contains("stages"));
    assertTrue(!refused.getMessage().contains(", bot,") && !refused.getMessage().contains("[bot,"));
  }

  @Test
  void an_extra_key_is_still_unknown_to_a_plain_agent() {
    Map<String, String> dropped = new LinkedHashMap<>();
    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                AgentRegistry.parseWith(entry("stages: []\n"), TOOLS, dropped, Set.of(), Set.of()));
    assertTrue(refused.getMessage().contains("unrecognised frontmatter key 'stages'"));
  }

  @Test
  void an_extra_key_that_is_an_agent_key_is_a_programming_error() {
    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                AgentRegistry.parseWith(
                    entry(""), TOOLS, new LinkedHashMap<>(), Set.of("stages", "tools"), Set.of()));
    assertTrue(
        refused.getMessage().startsWith("extra keys may not be agent keys: "),
        refused.getMessage());
    assertTrue(refused.getMessage().contains("tools"), refused.getMessage());
  }
}
