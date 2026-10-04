package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefinitionWriterTest {

  /**
   * {@code DefinitionResolverTest}'s own fixture, reused for the same reason: it is the smallest
   * set every shipped {@code REQUIRED} agent parses against, and {@link #bootSet} reads the real
   * shipped seed.
   */
  private static final Set<String> TOOLS = Set.of("memory_recall", "memory_read", "agent_run");

  private static String definition(String name, String body) {
    return "---\nname: "
        + name
        + "\ndescription: d\nmodel: m\nmax-turns: 1\n"
        + "max-model-calls: 1\nexported: true\n---\n"
        + body
        + "\n";
  }

  /**
   * The real shipped seed, exactly as {@code DefinitionResolverTest}'s own {@code resolverOver}
   * builds its {@code bootSet} — from {@link ClasspathDefinitions} rather than a directory, since
   * there is no directory to name in this suite either. This is what makes {@code librarian}
   * available as an <em>inherited</em> agent for the delegation test below: it is one of the agents
   * this repository ships.
   */
  private static AgentRegistry bootSet() {
    return new AgentRegistry(
        AgentRegistry.read(new ClasspathDefinitions(), TOOLS, AgentsConfig.REQUIRED));
  }

  /**
   * A writer over {@code layout}, judged by the real shipped {@link #bootSet}, {@link #TOOLS},
   * {@link AgentsConfig#REQUIRED} and no fleet checks.
   *
   * <p>{@link DefinitionChecks#NONE} on {@link DefinitionResolverTest}'s own precedent: this suite
   * has no dispatcher and no sampling profile to ask, and none of its cases turn on whether a model
   * is served — that is {@code DefinitionChecks}' own question, asked in its own test. What this
   * suite measures is that {@link DefinitionWriter} runs the checks it is handed, not what any
   * particular fleet answers.
   */
  private static DefinitionWriter writerOver(DataLayout layout) {
    return new DefinitionWriter(
        bootSet(), layout, TOOLS, AgentsConfig.REQUIRED, DefinitionChecks.NONE);
  }

  @Test
  void a_valid_definition_lands_in_the_projects_bots_directory(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    DefinitionWriter writer = writerOver(layout);

    DefinitionWriter.Written written = writer.write(7L, "helper", definition("helper", "hi"));

    assertEquals(layout.botsFor(7L).resolve("helper.md"), written.file());
    assertTrue(Files.readString(written.file()).contains("hi"));
    assertEquals(DefinitionWriter.Disposition.CREATED, written.disposition());
  }

  @Test
  void a_write_naming_no_project_lands_in_global(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();

    DefinitionWriter.Written written =
        writerOver(layout).write(null, "helper", definition("helper", "hi"));

    assertEquals(layout.botsFor(null).resolve("helper.md"), written.file());
  }

  @Test
  void a_definition_that_would_not_load_is_refused_and_nothing_is_written(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    DefinitionWriter writer = writerOver(layout);

    CallerFault refused =
        assertThrows(CallerFault.class, () -> writer.write(7L, "helper", "not frontmatter at all"));

    assertTrue(refused.getMessage().contains("helper"), refused.getMessage());
    assertFalse(
        Files.exists(layout.botsFor(7L).resolve("helper.md")),
        "a refused write left a file behind");
  }

  @Test
  void a_grant_naming_an_unknown_tool_is_refused_by_name(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();

    CallerFault refused =
        assertThrows(
            CallerFault.class,
            () ->
                writerOver(layout)
                    .write(
                        7L,
                        "helper",
                        "---\nname: helper\ndescription: d\nmodel: m\nmax-turns: 1\n"
                            + "max-model-calls: 1\ntools: [no_such_tool]\n---\nb\n"));

    assertTrue(refused.getMessage().contains("no_such_tool"), refused.getMessage());
    assertFalse(
        Files.exists(layout.botsFor(7L).resolve("helper.md")),
        "a refused write left a file behind");
  }

  @Test
  void a_required_name_is_refused_at_the_project_tier(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();

    CallerFault refused =
        assertThrows(
            CallerFault.class,
            () -> writerOver(layout).write(7L, "scribe", definition("scribe", "mine")));

    assertTrue(refused.getMessage().contains("scribe"), refused.getMessage());
    assertFalse(Files.exists(layout.botsFor(7L).resolve("scribe.md")));
  }

  /**
   * Finding 3: the permitted direction. {@link DefinitionResolver} refuses a project file named
   * after a required agent because a project layers over the boot set; {@code global/bots/} is one
   * of the layers the boot set is built <em>out of</em>, so this is the one legitimate way to
   * reconfigure a required agent this repository ships. {@link DefinitionWriter}'s own javadoc, "a
   * required name is a project-tier fault, not a global one".
   */
  @Test
  void a_required_name_is_allowed_at_the_global_tier(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();

    DefinitionWriter.Written written =
        writerOver(layout).write(null, "scribe", definition("scribe", "reconfigured"));

    assertEquals(layout.botsFor(null).resolve("scribe.md"), written.file());
    assertTrue(Files.readString(written.file()).contains("reconfigured"));
  }

  @Test
  void a_name_disagreeing_with_its_frontmatter_is_refused(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();

    assertThrows(
        CallerFault.class,
        () -> writerOver(layout).write(7L, "helper", definition("something_else", "b")));

    assertFalse(
        Files.exists(layout.botsFor(7L).resolve("helper.md")),
        "a refused write left a file behind");
  }

  /**
   * Finding 2: a definition may {@code calls:} an agent the <em>boot set</em> defines and keep that
   * edge, exactly as {@code
   * DefinitionResolverTest.a_project_agent_can_delegate_to_an_inherited_agent} proves of {@link
   * DefinitionResolver}. {@code librarian} is a real shipped agent — see {@link #bootSet} — so this
   * is the same scenario at the write path: without {@code bootSet.names()} passed to {@link
   * AgentRegistry#read} as {@code alsoDefined}, this edge would read as a typo and the whole write
   * would be refused, though the very same file would load fine once it landed.
   */
  @Test
  void a_definition_calling_an_inherited_agent_is_accepted(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();

    DefinitionWriter.Written written =
        writerOver(layout)
            .write(
                9L,
                "delegator",
                "---\nname: delegator\ndescription: d\nmodel: m\nmax-turns: 1\n"
                    + "max-model-calls: 1\ntools: [agent_run]\ncalls: [librarian]\n---\n"
                    + "delegates to the inherited librarian\n");

    assertEquals(layout.botsFor(9L).resolve("delegator.md"), written.file());
    assertTrue(Files.readString(written.file()).contains("delegates"));
  }

  // ------------------------------------------------------------------
  // Finding 1 (CRITICAL): name is a bare filename stem, not a path
  // ------------------------------------------------------------------

  /**
   * The exact scenario the finding names: a project-scoped write whose name and frontmatter agree
   * on a string that resolves outside the project's own tier and into the boot tier. {@code
   * global/bots/} is pre-seeded by a legitimate write first, so a pre-fix run lands the malicious
   * file on an existing directory rather than merely failing on a missing one — proving the write
   * actually escapes rather than merely erroring oddly.
   */
  @Test
  void a_name_with_parent_directory_segments_is_refused_and_cannot_escape_the_tier(
      @TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    writerOver(layout).write(null, "seed", definition("seed", "the real one"));

    String evil = "../../../global/bots/seed";
    CallerFault refused =
        assertThrows(
            CallerFault.class, () -> writerOver(layout).write(7L, evil, definition(evil, "PWNED")));

    assertTrue(refused.getMessage().contains(evil), refused.getMessage());
    assertTrue(
        Files.readString(layout.botsFor(null).resolve("seed.md")).contains("the real one"),
        "a traversal name overwrote a file outside the tier it was scoped to");
    assertFalse(Files.exists(layout.botsFor(7L)), "a refused write left a directory behind");
  }

  @Test
  void a_name_containing_a_slash_is_refused(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();

    assertThrows(
        CallerFault.class, () -> writerOver(layout).write(7L, "a/b", definition("a/b", "x")));

    assertFalse(Files.exists(layout.botsFor(7L)), "a refused write left a directory behind");
  }

  @Test
  void an_absolute_looking_name_is_refused(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();

    assertThrows(
        CallerFault.class,
        () -> writerOver(layout).write(7L, "/etc/passwd", definition("/etc/passwd", "x")));

    assertFalse(Files.exists(layout.botsFor(7L)), "a refused write left a directory behind");
  }

  @Test
  void a_name_that_is_a_single_dot_is_refused(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();

    assertThrows(CallerFault.class, () -> writerOver(layout).write(7L, ".", definition(".", "x")));

    assertFalse(Files.exists(layout.botsFor(7L)), "a refused write left a directory behind");
  }

  @Test
  void a_name_that_is_two_dots_is_refused(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();

    assertThrows(
        CallerFault.class, () -> writerOver(layout).write(7L, "..", definition("..", "x")));

    assertFalse(Files.exists(layout.botsFor(7L)), "a refused write left a directory behind");
  }

  @Test
  void an_empty_name_is_refused(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();

    assertThrows(CallerFault.class, () -> writerOver(layout).write(7L, "", definition("", "x")));

    assertFalse(Files.exists(layout.botsFor(7L)), "a refused write left a directory behind");
  }

  @Test
  void a_name_containing_a_null_byte_is_refused(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    String name = "helper\0evil";

    assertThrows(
        CallerFault.class, () -> writerOver(layout).write(7L, name, definition(name, "x")));

    assertFalse(Files.exists(layout.botsFor(7L)), "a refused write left a directory behind");
  }

  @Test
  void an_over_long_name_is_refused(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    String name = "a".repeat(65);

    assertThrows(
        CallerFault.class, () -> writerOver(layout).write(7L, name, definition(name, "x")));

    assertFalse(Files.exists(layout.botsFor(7L)), "a refused write left a directory behind");
  }

  @Test
  void a_name_with_a_trailing_dot_is_refused(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    String name = "helper.";

    assertThrows(
        CallerFault.class, () -> writerOver(layout).write(7L, name, definition(name, "x")));

    assertFalse(Files.exists(layout.botsFor(7L)), "a refused write left a directory behind");
  }

  @Test
  void a_name_with_a_trailing_space_is_refused(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    String name = "helper ";

    assertThrows(
        CallerFault.class, () -> writerOver(layout).write(7L, name, definition(name, "x")));

    assertFalse(Files.exists(layout.botsFor(7L)), "a refused write left a directory behind");
  }

  // ------------------------------------------------------------------
  // Overwrite semantics (ruling)
  // ------------------------------------------------------------------

  @Test
  void a_second_write_of_the_same_name_is_refused_without_overwrite(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    DefinitionWriter writer = writerOver(layout);
    writer.write(7L, "helper", definition("helper", "original"));

    assertThrows(
        DefinitionAlreadyExistsException.class,
        () -> writer.write(7L, "helper", definition("helper", "clobbered")));

    assertTrue(
        Files.readString(layout.botsFor(7L).resolve("helper.md")).contains("original"),
        "a refused overwrite clobbered the file it was refused for touching");
  }

  @Test
  void an_explicit_overwrite_replaces_the_file_and_is_reported_as_replaced(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    DefinitionWriter writer = writerOver(layout);
    writer.write(7L, "helper", definition("helper", "original"));

    DefinitionWriter.Written written =
        writer.write(7L, "helper", definition("helper", "replaced"), true);

    assertEquals(DefinitionWriter.Disposition.REPLACED, written.disposition());
    assertTrue(Files.readString(written.file()).contains("replaced"));
  }

  // ------------------------------------------------------------------
  // Finding 5: a set-level fault is keyed by the RAW frontmatter name
  // ------------------------------------------------------------------

  /**
   * {@code AgentRegistry.parse} accepts a frontmatter {@code name:} that is merely NFC-equal to the
   * stem, then stores the RAW frontmatter string as the definition's own {@code name()}. A per-file
   * parse fault is caught and keyed by the stem, in {@code read()}'s own per-entry loop, so that
   * lookup was always safe — but a set-level fault such as {@code disagreeingHalves} runs afterward
   * over the {@code enabled} map, keyed by {@code definition.name()}, and disables under that raw
   * key instead. U+212A KELVIN SIGN NFC-normalises to {@code 'K'}, so a candidate named {@code "K"}
   * with frontmatter {@code name: â„ª} parses — the two compare equal — and a self-contradicting
   * {@code tools: [agent_run]} with no {@code calls:} then disables the literal Kelvin sign, a key
   * {@code disabled().get("K")} can never find. Verified to fail against the pre-fix code before
   * the fix landed: the write completed with no exception and left {@code K.md} on disk, exactly
   * the definition the loader would go on to disable.
   */
  @Test
  void a_set_level_fault_keyed_by_the_raw_frontmatter_name_is_still_caught(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    String kelvin = "K"; // KELVIN SIGN, NFC-normalises to 'K'

    assertThrows(
        CallerFault.class,
        () ->
            writerOver(layout)
                .write(
                    7L,
                    "K",
                    "---\nname: "
                        + kelvin
                        + "\ndescription: d\nmodel: m\nmax-turns: 1\n"
                        + "max-model-calls: 1\ntools: [agent_run]\n---\nno calls, has agent_run\n"));

    assertFalse(
        Files.exists(layout.botsFor(7L).resolve("K.md")),
        "a definition the loader will disable was still written to disk");
  }

  // ------------------------------------------------------------------
  // Finding 7: DefinitionChecks coverage
  // ------------------------------------------------------------------

  /**
   * A {@link DefinitionChecks} that disables whatever it is handed, with the given reason — a fake
   * fleet that always says no.
   */
  private static DefinitionChecks alwaysDisables(String reason) {
    return (loaded, source) -> {
      AgentRegistry.Loaded result = loaded;
      for (String name : loaded.enabled().keySet()) {
        result = result.without(name, reason);
      }
      return result;
    };
  }

  @Test
  void a_checks_disablement_is_refused_and_nothing_is_written(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    DefinitionWriter writer =
        new DefinitionWriter(
            bootSet(),
            layout,
            TOOLS,
            AgentsConfig.REQUIRED,
            alwaysDisables("the fleet serves no pool for this model"));

    CallerFault refused =
        assertThrows(
            CallerFault.class, () -> writer.write(7L, "helper", definition("helper", "hi")));

    assertTrue(refused.getMessage().contains("the fleet serves no pool"), refused.getMessage());
    assertFalse(
        Files.exists(layout.botsFor(7L).resolve("helper.md")),
        "a refused write left a file behind");
  }

  /**
   * {@link DefinitionChecks}' own contract is "may disable, may never withhold" — nothing in this
   * codebase builds one that withholds. This constructs {@link AgentRegistry.Loaded} directly,
   * bypassing the interface's contract on purpose, to prove {@link DefinitionWriter}'s own
   * re-inspection after {@code checks.applyTo} — documented in its class javadoc as defence against
   * exactly this — actually catches a violation rather than silently accepting it.
   */
  @Test
  void a_checks_implementation_that_withholds_is_still_caught(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    DefinitionChecks violatesItsOwnContract =
        (loaded, source) ->
            new AgentRegistry.Loaded(
                loaded.enabled(),
                loaded.disabled(),
                loaded.withheldEdges(),
                Map.of(
                    "helper: mystery_tool",
                    "the tool 'mystery_tool' was withheld by a checks implementation that should"
                        + " never withhold"));
    DefinitionWriter writer =
        new DefinitionWriter(
            bootSet(), layout, TOOLS, AgentsConfig.REQUIRED, violatesItsOwnContract);

    CallerFault refused =
        assertThrows(
            CallerFault.class, () -> writer.write(7L, "helper", definition("helper", "hi")));

    assertTrue(refused.getMessage().contains("mystery_tool"), refused.getMessage());
    assertFalse(
        Files.exists(layout.botsFor(7L).resolve("helper.md")),
        "a refused write left a file behind");
  }

  // ------------------------------------------------------------------
  // Minor: a definition is written world-readable
  // ------------------------------------------------------------------

  @Test
  void a_written_definition_is_readable_by_more_than_its_owner(@TempDir Path data)
      throws Exception {
    Assumptions.assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        "no POSIX permissions on this filesystem");
    DataLayout layout = new DataLayout(data).initialise();

    DefinitionWriter.Written written =
        writerOver(layout).write(7L, "helper", definition("helper", "hi"));

    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(written.file());
    assertTrue(
        permissions.contains(PosixFilePermission.OTHERS_READ),
        "an operator's own definition should not need root to read: " + permissions);
  }

  // ------------------------------------------------------------------
  // The size ceiling, which came down out of AgentController
  // ------------------------------------------------------------------

  /**
   * Nothing bounds a JSON body the way the multipart limits bound an upload, so without this any
   * caller that may write at all could fill the data directory one write at a time. It is the
   * writer's ceiling now, so every caller of {@link DefinitionWriter#write} has it and not only the
   * one that remembered to check.
   */
  @Test
  void text_over_the_ceiling_is_refused_and_nothing_is_written(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    String huge = "a".repeat(DefinitionWriter.MAX_DEFINITION_BYTES + 1);

    CallerFault refused =
        assertThrows(CallerFault.class, () -> writerOver(layout).write(7L, "helper", huge));

    assertTrue(
        refused.getMessage().contains(String.valueOf(DefinitionWriter.MAX_DEFINITION_BYTES)),
        refused.getMessage());
    assertTrue(refused.getMessage().contains("Nothing was written"), refused.getMessage());
    assertFalse(Files.exists(layout.botsFor(7L).resolve("helper.md")));
  }

  /**
   * Measured in UTF-8 bytes, matching what the write itself encodes — a character count would
   * under-measure a definition whose prose is not ASCII.
   */
  @Test
  void the_ceiling_counts_utf8_bytes_and_not_characters(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    // Two bytes per character, so this is under the ceiling counted as
    // characters and over it counted as bytes.
    String huge = "é".repeat(DefinitionWriter.MAX_DEFINITION_BYTES / 2 + 1);

    CallerFault refused =
        assertThrows(CallerFault.class, () -> writerOver(layout).write(7L, "helper", huge));

    assertTrue(refused.getMessage().contains("bytes"), refused.getMessage());
  }

  /**
   * The ceiling is measured before anything else this method asks, which is the order it ran in
   * when it was the caller's check: an oversized body on a deployment that keeps nothing is told
   * about its size, not about the data directory.
   */
  @Test
  void the_ceiling_is_measured_before_the_deployments_own_refusal() {
    String huge = "a".repeat(DefinitionWriter.MAX_DEFINITION_BYTES + 1);

    CallerFault refused =
        assertThrows(
            CallerFault.class, () -> writerOver(DataLayout.NONE).write(7L, "helper", huge));

    assertTrue(refused.getMessage().contains("will not write more than"), refused.getMessage());
    assertFalse(refused.getMessage().contains("keeps no data directory"), refused.getMessage());
  }

  /**
   * A definition exactly at the ceiling is written: the bound is inclusive, as the refusal's own
   * "more than" says.
   */
  @Test
  void text_exactly_at_the_ceiling_is_written(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    String head = definition("helper", "");
    String body = head + "a".repeat(DefinitionWriter.MAX_DEFINITION_BYTES - head.length());

    DefinitionWriter.Written written = writerOver(layout).write(7L, "helper", body);

    assertTrue(Files.exists(written.file()));
  }
}
