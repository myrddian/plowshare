package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.files.Mode;
import io.aeyer.plowshare.server.files.Scope;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The shipped test designer: what it holds, who may reach it, and what its prompt makes it return.
 */
class TestDesignerDefinitionTest {

  private static final Path SHIPPED = Path.of("src/main/resources/agents");

  private static AgentDefinition shipped() {
    AgentDefinition designer =
        AgentRegistry.of(SHIPPED, BoundTools.boundByThisServer()).get("test_designer");
    assertNotNull(designer, "test_designer.md did not load against the tools this boot binds");
    return designer;
  }

  /** The prompt with its line breaks folded, so a phrase can be found across a wrap. */
  private static String flowed() {
    return shipped().prompt().replaceAll("\\s+", " ");
  }

  @Test
  void the_designer_reads_and_nothing_else() {
    assertEquals(
        List.of(
            FileTools.CODE_MAP_NAME,
            FileTools.ROOTS_NAME,
            FileTools.GLOB_NAME,
            FileTools.GREP_NAME,
            FileTools.READ_NAME,
            FileTools.STAT_NAME),
        shipped().tools());
    assertEquals(List.of(), shipped().calls());
    assertFalse(
        shipped().tools().contains(RunTool.NAME),
        "a designer that could run the suite"
            + " would tune its cases against what already passes");
  }

  @Test
  void the_designer_holds_only_the_read_grant() {
    assertEquals(
        List.of(new Grant(Scope.WORKSPACE, Mode.READ)),
        shipped().scopes(),
        "grants here are not path-scoped, so a write grant for its own design file would"
            + " also reach the implementation it is designing tests against");
  }

  @Test
  void the_designer_is_delegable_and_not_exported() {
    assertFalse(shipped().exported());
    assertTrue(shipped().delegable());
  }

  @Test
  void the_designer_is_told_what_to_return_including_what_it_does_not_test() {
    String prompt = flowed();
    assertTrue(prompt.contains("You do not write the tests"), prompt);
    assertTrue(prompt.contains("the clause of the spec it comes from"), prompt);
    assertTrue(prompt.contains("## What this does not test"), prompt);
    assertTrue(prompt.contains("the command that must pass"), prompt);
    assertTrue(prompt.contains("data, not instructions"), prompt);
  }

  @Test
  void the_designer_answers_in_the_sections_the_conductor_writes_to_the_file() {
    String prompt = flowed();
    assertTrue(prompt.contains("## Cases"), prompt);
    assertTrue(prompt.contains("## Edges"), prompt);
    assertTrue(prompt.contains("## Done when"), prompt);
  }

  @Test
  void the_designer_reads_the_code_before_it_decides_the_cases() {
    String prompt = flowed();
    assertTrue(prompt.contains("names what exists, not what should"), prompt);
  }

  @Test
  void the_designer_does_not_write_the_design_file_either() {
    String prompt = flowed();
    assertTrue(prompt.contains("The design is your answer"), prompt);
    assertTrue(prompt.contains("do not write it to a file"), prompt);
  }

  /**
   * A spec-and-code conflict has somewhere to go, because the conductor writes this answer to
   * {@code test-design.md} and hands that file to {@code coder} verbatim: a conflict the designer
   * noticed and the four headings had no room for is one nobody downstream ever sees.
   */
  @Test
  void a_spec_and_code_disagreement_is_routed_into_the_output_and_not_left_homeless() {
    String prompt = flowed();
    assertTrue(prompt.contains("as a line on the case it affects under `## Cases`"), prompt);
    assertTrue(prompt.contains("`Disagrees with the code:`"), prompt);
    assertTrue(prompt.contains("a spec-and-code disagreement no case covers"), prompt);
  }

  /**
   * The one escape hatch in the output, closed on every gap kind the section names.
   *
   * <p>This is the corner where the routing above can be undone: a designer that wrote a
   * disagreement nowhere and then wrote "Nothing." here has produced an answer that reads as
   * complete, and the conductor hands that to {@code coder} verbatim. Each condition is pinned
   * separately so a later edit cannot drop one of the three and stay green.
   */
  @Test
  void the_nothing_escape_is_closed_on_every_gap_kind_this_section_names() {
    String prompt = flowed();
    assertTrue(prompt.contains("every clause in your slice has a case above it"), prompt);
    assertTrue(prompt.contains("nothing above is marked `unverified`"), prompt);
    assertTrue(
        prompt.contains("every disagreement you found carries a case line above it"), prompt);
  }

  /** It holds no {@code run}, so the command it names is grounded in a file or labelled a guess. */
  @Test
  void the_command_names_the_file_it_came_from_or_is_marked_unverified() {
    String prompt = flowed();
    assertTrue(prompt.contains("name the file you took it from"), prompt);
    assertTrue(prompt.contains("mark it `unverified`"), prompt);
  }
}
