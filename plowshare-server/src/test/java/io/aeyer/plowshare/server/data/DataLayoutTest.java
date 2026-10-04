package io.aeyer.plowshare.server.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The tree, the marker, and the three arrangements this server will not start on top of.
 *
 * <p>Every fixture is under a {@link TempDir}. Nothing here names {@code data}, the shipped
 * default, for {@code ProjectStoreTest}'s stated reason: a fixture named like the default is one
 * that would still pass against an implementation that ignored the property and hardcoded it.
 *
 * <h2>Why the refusals get more tests than the happy path</h2>
 *
 * <p>The happy path is one {@code createDirectories} and one {@code writeString} and fails loudly
 * when it is wrong. The refusals are the whole reason the marker exists — they are what makes "a
 * fresh install", "an older layout" and "an arrangement somebody made on purpose" three
 * distinguishable things — and every one of them is a place where the wrong answer is
 * <em>silence</em>: adopting a directory this server did not write, and then letting every later
 * migration act on it.
 */
class DataLayoutTest {

  @Test
  void a_deployment_that_keeps_nothing_creates_nothing(@TempDir Path tmp) {
    DataLayout.NONE.initialise();

    assertFalse(DataLayout.NONE.keepsAnything());
    assertEquals(
        null,
        DataLayout.NONE.root(),
        "null is what ProjectStore's fence takes for every entry it has no path"
            + " for, and it means the same thing here");
    assertThrows(
        IllegalStateException.class,
        () -> DataLayout.NONE.exportsFor(7L),
        "a caller that reached this has already decided to write, and answering"
            + " with a relative path would put a person's ejected file body in"
            + " whatever directory the server started in");
    assertEquals(
        0,
        tmp.toFile().list().length,
        "and the temp directory beside it is untouched, which is the property"
            + " every Spring context in this repository depends on");
  }

  @Test
  void a_first_start_makes_the_tree_and_marks_it(@TempDir Path tmp) throws IOException {
    Path root = tmp.resolve("owned");

    DataLayout layout = new DataLayout(root).initialise();

    assertTrue(
        Files.isDirectory(root.resolve("projects")),
        "projects/ is made because the server writes into it");
    assertFalse(
        Files.exists(root.resolve("agents")),
        "and agents/ is NOT, though an early design sketch named it: task 10"
            + " retired the standalone directory key that arrangement would have"
            + " needed, and agent definitions live in global/agents/ instead");
    assertFalse(Files.exists(root.resolve("profiles")), "nor profiles/, for that reason");
    assertEquals(root.resolve("projects").resolve("12").resolve("exports"), layout.exportsFor(12L));
    assertEquals(
        root.resolve("global").resolve("exports"),
        layout.exportsFor(null),
        "the tier that is not a project is a sibling of projects/ and not a"
            + " directory inside it: V1 says global is the absence of a project"
            + " rather than a project named 'global', and projects/global/"
            + " would sit in the namespace of ids");
  }

  @Test
  void the_marker_says_what_layout_it_is_and_what_wrote_it(@TempDir Path tmp) throws IOException {
    Path root = tmp.resolve("owned");

    new DataLayout(root).initialise();

    Path marker = root.resolve(DataLayout.MARKER);
    Properties found = new Properties();
    try (var text = Files.newBufferedReader(marker, StandardCharsets.UTF_8)) {
      found.load(text);
    }
    assertEquals(Integer.toString(DataLayout.VERSION), found.getProperty("layout-version"));
    assertEquals(
        "plowshare-server",
        found.getProperty("written-by"),
        "what wrote it, which is half of what a marker is for: the other half is"
            + " what shape the tree is");
    assertTrue(found.getProperty("written-at").startsWith("20"), "and when");
    String text = Files.readString(marker, StandardCharsets.UTF_8);
    assertTrue(
        text.contains("#"),
        "the file has comments in it, which is why this is properties text and"
            + " not the JSON Lines the manifest uses -- an operator who opens"
            + " it is holding a directory they may not recognise, and JSON has"
            + " nowhere to tell them what it is");
    assertTrue(
        text.contains("refuse to start"),
        "including what happens if they delete the one key that is read");
  }

  @Test
  void a_second_start_adopts_the_tree_and_rewrites_nothing(@TempDir Path tmp) throws IOException {
    Path root = tmp.resolve("owned");
    new DataLayout(root).initialise();
    Path marker = root.resolve(DataLayout.MARKER);
    Files.writeString(
        marker,
        Files.readString(marker, StandardCharsets.UTF_8) + "\n# my own note\n",
        StandardCharsets.UTF_8);
    String asEdited = Files.readString(marker, StandardCharsets.UTF_8);

    new DataLayout(root).initialise();

    assertEquals(
        asEdited,
        Files.readString(marker, StandardCharsets.UTF_8),
        "the marker is never rewritten, so an operator's own note in it survives"
            + " every restart -- which is the whole of why the format has to"
            + " tolerate being edited");
  }

  @Test
  void a_projects_directory_somebody_deleted_comes_back(@TempDir Path tmp) throws IOException {
    Path root = tmp.resolve("owned");
    new DataLayout(root).initialise();
    Files.delete(root.resolve("projects"));

    new DataLayout(root).initialise();

    assertTrue(
        Files.isDirectory(root.resolve("projects")),
        "initialise is idempotent on a marked tree rather than a one-time act:"
            + " the alternative is a server that starts and then fails on the"
            + " first ejection");
  }

  // --- what it will not start on top of --------------------------------------

  /**
   * The arrangement the marker exists for: a directory holding something, written by nobody this
   * server can identify.
   *
   * <p><b>Refused rather than adopted, and adopting is the tempting answer.</b> It would start, and
   * it would then be a tree marked as one this server made — so the next version that moves a
   * subdirectory would migrate an arrangement somebody set up by hand. That is the failure that has
   * no report at all: files move, nothing errors, and the operator finds out by looking.
   */
  @Test
  void a_directory_that_holds_something_and_carries_no_marker_stops_the_boot(@TempDir Path tmp)
      throws IOException {
    Path root = Files.createDirectory(tmp.resolve("owned"));
    Files.writeString(root.resolve("something.txt"), "not ours");

    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> new DataLayout(root).initialise());

    assertTrue(refused.getMessage().contains("will not guess"), refused.getMessage());
    assertTrue(
        refused.getMessage().contains("layout-version=" + DataLayout.VERSION),
        "and it says exactly what to write to adopt the tree, because a refusal"
            + " with no way past it is a server an operator cannot start: "
            + refused.getMessage());
    assertFalse(
        Files.exists(root.resolve(DataLayout.MARKER)),
        "nothing is written on the way out -- a marker left behind by a failed"
            + " boot would make the next one adopt what this one refused");
  }

  /**
   * An empty directory is a fresh one: an operator who made the directory and then started the
   * server has said where, not what.
   */
  @Test
  void an_empty_directory_is_a_first_start(@TempDir Path tmp) throws IOException {
    Path root = Files.createDirectory(tmp.resolve("owned"));

    new DataLayout(root).initialise();

    assertTrue(Files.isRegularFile(root.resolve(DataLayout.MARKER)));
  }

  @Test
  void a_marker_from_a_layout_this_version_does_not_know_stops_the_boot(@TempDir Path tmp)
      throws IOException {
    Path root = Files.createDirectory(tmp.resolve("owned"));
    Files.writeString(root.resolve(DataLayout.MARKER), "layout-version=99\n");

    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> new DataLayout(root).initialise());

    assertTrue(refused.getMessage().contains("99"), refused.getMessage());
    assertTrue(
        refused.getMessage().contains("A later Plowshare wrote this tree"), refused.getMessage());
  }

  /**
   * And a version below every layout this server knows is refused too, rather than read hopefully.
   *
   * <p>There is no migration for it: {@link DataLayout#VERSION} has been 1, 2, 3 and is now 4, so a
   * tree claiming 0 was written by none of them — reading it as one of an unknown shape is the
   * guess the marker exists to prevent in the other direction. (A tree claiming 1, 2 or 3 is a
   * real, once-shipped layout, and is moved forward — see {@code
   * a_layout_1_tree_is_migrated_to_the_current_layout} below.)
   */
  @Test
  void a_marker_from_below_this_version_stops_the_boot_too(@TempDir Path tmp) throws IOException {
    Path root = Files.createDirectory(tmp.resolve("owned"));
    Files.writeString(root.resolve(DataLayout.MARKER), "layout-version=0\n");

    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> new DataLayout(root).initialise());

    assertTrue(refused.getMessage().contains("no migration"), refused.getMessage());
  }

  @Test
  void a_marker_with_the_one_key_missing_stops_the_boot(@TempDir Path tmp) throws IOException {
    Path root = Files.createDirectory(tmp.resolve("owned"));
    Files.writeString(root.resolve(DataLayout.MARKER), "written-by=somebody\n");

    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> new DataLayout(root).initialise());

    assertTrue(refused.getMessage().contains("no such key"), refused.getMessage());
  }

  @Test
  void a_path_that_is_a_file_stops_the_boot(@TempDir Path tmp) throws IOException {
    Path root = Files.writeString(tmp.resolve("owned"), "a file where a tree goes");

    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> new DataLayout(root).initialise());

    assertTrue(refused.getMessage().contains("is not a directory"), refused.getMessage());
  }

  // --- the override, which has to answer in the same shape -------------------

  /**
   * An operator who names their own export directory gets the same tree one root along.
   *
   * <p><b>The alternative was two shapes</b>, and it is what this replaced: the override used to
   * mean "write the trees flat in here", because nothing knew a conversation's project. Keeping
   * that would make "which project is this export of" answerable on one deployment and not on
   * another, and every later question — orphans, retention, what is in here — would have to be
   * asked twice.
   */
  /**
   * The second tenant, and the two are siblings rather than nested.
   *
   * <p>An export comes out of a conversation and is keyed by the tree it came from; an image goes
   * in from outside and is keyed by nothing but its own id. Nesting one under the other would make
   * "what is in here" answerable only by knowing which of the two you meant.
   */
  @Test
  void images_are_a_sibling_of_exports_and_keyed_by_the_same_id(@TempDir Path tmp) {
    Path root = tmp.resolve("owned");
    DataLayout layout = new DataLayout(root).initialise();

    assertEquals(root.resolve("projects").resolve("12").resolve("images"), layout.imagesFor(12L));
    assertEquals(root.resolve("global").resolve("images"), layout.imagesFor(null));
    assertEquals(
        layout.exportsFor(12L).getParent(),
        layout.imagesFor(12L).getParent(),
        "siblings under the project, which is what makes 'this project is gone,"
            + " what of it is still on disk' one directory listing");
    assertFalse(
        Files.exists(layout.imagesFor(12L)),
        "and not created at boot: a server nobody has uploaded to should not leave"
            + " an empty images/ behind to explain");
  }

  @Test
  void a_deployment_that_keeps_nothing_has_nowhere_to_put_an_image() {
    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> DataLayout.NONE.imagesFor(null));

    assertTrue(
        refused.getMessage().contains("PLOWSHARE_DATA_DIR"),
        "and unlike an export there is no per-feature override to fall back on,"
            + " which the refusal says rather than leaving to be discovered: "
            + refused.getMessage());
  }

  @Test
  void a_named_export_directory_is_still_keyed_by_project(@TempDir Path tmp) {
    Path named = tmp.resolve("somewhere-else");

    assertEquals(
        named.toAbsolutePath().normalize().resolve("12"), DataLayout.exportsUnder(named, 12L));
    assertEquals(
        named.toAbsolutePath().normalize().resolve("global"), DataLayout.exportsUnder(named, null));
  }

  // --- definition directories: agents/ and bots/, named by the layout now ----

  @Test
  void definitions_have_a_directory_per_tier_and_global_is_a_sibling(@TempDir Path tmp) {
    Path root = tmp.resolve("owned");
    DataLayout layout = new DataLayout(root).initialise();

    assertEquals(root.resolve("projects").resolve("7").resolve("bots"), layout.botsFor(7L));
    assertEquals(root.resolve("projects").resolve("7").resolve("agents"), layout.agentsFor(7L));
    assertEquals(root.resolve("global").resolve("bots"), layout.botsFor(null));
    assertEquals(root.resolve("global").resolve("agents"), layout.agentsFor(null));
    assertFalse(
        Files.exists(layout.botsFor(7L)),
        "not created at boot, on images/'s reasoning: nothing reads these yet, so"
            + " there is nothing to write into them either");
    assertFalse(Files.exists(layout.agentsFor(null)));
  }

  @Test
  void a_deployment_that_keeps_nothing_has_nowhere_to_look_for_a_definition() {
    assertThrows(IllegalStateException.class, () -> DataLayout.NONE.botsFor(null));
    assertThrows(IllegalStateException.class, () -> DataLayout.NONE.agentsFor(7L));
  }

  @Test
  void orchestrations_sit_beside_agents_and_bots_in_each_tier(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();

    assertEquals(
        layout.agentsFor(7L).resolveSibling("orchestrations"), layout.orchestrationsFor(7L));
    assertEquals(
        layout.agentsFor(null).resolveSibling("orchestrations"), layout.orchestrationsFor(null));
  }

  @Test
  void the_layout_version_moved_because_a_name_is_new(@TempDir Path tmp) throws IOException {
    Path root = tmp.resolve("owned");
    new DataLayout(root).initialise();

    Properties marker = new Properties();
    try (var in =
        Files.newBufferedReader(root.resolve(DataLayout.MARKER), StandardCharsets.UTF_8)) {
      marker.load(in);
    }
    assertEquals(
        "6",
        marker.getProperty("layout-version"),
        "accounting/ is a name layout 5 never reserved, as environment.yml under a project is a name layout 4 never reserved, as"
            + " orchestrations/ at both tiers was a name layout 3 never reserved, as hooks/"
            + " was a name layout 2 never reserved and bots/ and global/agents/ were"
            + " names layout 1 never reserved -- see DataLayout.VERSION for why that"
            + " distinction is what moves this");
  }

  @Test
  void a_projects_hooks_live_beside_its_definitions_and_are_not_created_at_boot(@TempDir Path tmp) {
    Path root = tmp.resolve("owned");
    DataLayout layout = new DataLayout(root).initialise();

    assertEquals(root.resolve("projects").resolve("7").resolve("hooks"), layout.hooksFor(7L));
    assertFalse(Files.exists(layout.hooksFor(7L)));
    assertThrows(IllegalStateException.class, () -> DataLayout.NONE.hooksFor(7L));
  }

  /**
   * A tree an older Plowshare marked is moved forward on boot, one layout at a time, and the marker
   * says so — rather than refusing and making the operator edit a file every time {@link
   * DataLayout#VERSION} moves.
   *
   * <p>Every step so far only added names, so moving forward is rewriting the one key. What must
   * survive is everything else in the file: an operator's own note is theirs, which is why the key
   * is replaced in place rather than the file written afresh.
   */
  @Test
  void a_layout_1_tree_is_migrated_to_the_current_layout(@TempDir Path tmp) throws IOException {
    Path root = tmp.resolve("owned");
    Files.createDirectories(root.resolve("projects"));
    Path marker = root.resolve(DataLayout.MARKER);
    Files.writeString(
        marker,
        "# my own note\nlayout-version=1\nwritten-by=plowshare-server\n",
        StandardCharsets.UTF_8);

    new DataLayout(root).initialise();

    String text = Files.readString(marker, StandardCharsets.UTF_8);
    assertEquals(Integer.toString(DataLayout.VERSION), load(marker).getProperty("layout-version"));
    assertTrue(text.startsWith("# my own note\n"), text);
    assertEquals("plowshare-server", load(marker).getProperty("written-by"));
    assertTrue(text.contains("from layout 1 to 2"), text);
    assertTrue(text.contains("from layout 2 to 3"), text);
    assertTrue(text.contains("from layout 3 to 4"), text);
    assertTrue(text.contains("from layout 4 to 5"), text);
  }

  @Test
  void a_layout_2_tree_is_migrated_and_a_second_boot_rewrites_nothing(@TempDir Path tmp)
      throws IOException {
    Path root = tmp.resolve("owned");
    Files.createDirectories(root.resolve("projects").resolve("7"));
    Path marker = root.resolve(DataLayout.MARKER);
    Files.writeString(marker, "layout-version = 2\n", StandardCharsets.UTF_8);

    new DataLayout(root).initialise();
    String migrated = Files.readString(marker, StandardCharsets.UTF_8);
    new DataLayout(root).initialise();

    assertEquals(Integer.toString(DataLayout.VERSION), load(marker).getProperty("layout-version"));
    assertFalse(migrated.contains("from layout 1"), migrated);
    assertEquals(migrated, Files.readString(marker, StandardCharsets.UTF_8));
  }

  /**
   * The one thing 2 -> 3 cannot move past on its own: a {@code hooks/} that was already under a
   * project. Layout 2 never wrote one, so somebody else did, and layout 3 runs every file in it as
   * that project's code. That is a decision for a person, so the boot stops and the marker is left
   * saying 2.
   */
  @Test
  void a_layout_2_tree_that_already_has_a_hooks_directory_is_refused(@TempDir Path tmp)
      throws IOException {
    Path root = tmp.resolve("owned");
    Path hooks = Files.createDirectories(root.resolve("projects").resolve("7").resolve("hooks"));
    Files.writeString(hooks.resolve("mine.ts"), "// not a hook\n");
    Path marker = root.resolve(DataLayout.MARKER);
    Files.writeString(marker, "layout-version=2\n", StandardCharsets.UTF_8);

    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> new DataLayout(root).initialise());

    assertTrue(refused.getMessage().contains(hooks.toString()), refused.getMessage());
    assertEquals(
        "2",
        load(marker).getProperty("layout-version"),
        "a refused step leaves the marker where it was");
  }

  /**
   * {@code orchestrations/} follows {@code bots/}'s precedent and not {@code hooks/}'s: it is read
   * as a definition and not as code, so unlike the 2 -> 3 move above, a project that already has
   * one is not a person's own arrangement this server must ask about — the step checks nothing and
   * the marker moves regardless of what is already on disk.
   */
  @Test
  void a_tree_at_layout_3_moves_to_4_without_checking_anything(@TempDir Path tmp)
      throws IOException {
    Path root = tmp.resolve("owned");
    Files.createDirectories(root.resolve("projects").resolve("7").resolve("orchestrations"));
    Path marker = root.resolve(DataLayout.MARKER);
    Files.writeString(marker, "layout-version=3\n", StandardCharsets.UTF_8);

    new DataLayout(root).initialise();

    assertEquals(Integer.toString(DataLayout.VERSION), load(marker).getProperty("layout-version"));
  }

  /**
   * A project's environment.yml decides whether commands run, so one already there when a layout 4
   * tree moves to 5 was put there by somebody else — the 2 -> 3 move's reasoning for hooks/, and
   * the same refusal.
   */
  @Test
  void a_layout_4_tree_with_an_environment_file_already_in_a_project_is_refused(@TempDir Path tmp)
      throws IOException {
    Path root = tmp.resolve("owned");
    Path project = Files.createDirectories(root.resolve("projects").resolve("7"));
    Files.writeString(project.resolve("environment.yml"), "server:\n  mode: open\n");
    Path marker = root.resolve(DataLayout.MARKER);
    Files.writeString(marker, "layout-version=4\n", StandardCharsets.UTF_8);

    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> new DataLayout(root).initialise());

    assertTrue(refused.getMessage().contains("environment.yml"), refused.getMessage());
    assertEquals("4", load(marker).getProperty("layout-version"));
  }

  @Test
  void a_projects_environment_file_lives_beside_its_definitions(@TempDir Path tmp) {
    Path root = tmp.resolve("owned");
    DataLayout layout = new DataLayout(root).initialise();

    assertEquals(
        root.resolve("projects").resolve("7").resolve("environment.yml"),
        layout.environmentFor(7L));
    assertFalse(Files.exists(layout.environmentFor(7L)));
  }

  private static Properties load(Path marker) throws IOException {
    Properties found = new Properties();
    try (var in = Files.newBufferedReader(marker, StandardCharsets.UTF_8)) {
      found.load(in);
    }
    return found;
  }

  @Test
  void accounting_is_reserved_without_creating_a_journal_and_requires_a_data_directory(
      @TempDir Path tmp) {
    var layout = new DataLayout(tmp.resolve("owned")).initialise();
    assertEquals(tmp.resolve("owned/accounting"), layout.accounting());
    assertFalse(Files.exists(layout.accounting()));
    assertThrows(IllegalStateException.class, DataLayout.NONE::accounting);
  }

  @Test
  void upgrading_layout_5_refuses_an_operator_owned_accounting_directory(@TempDir Path root)
      throws IOException {
    Files.writeString(root.resolve(DataLayout.MARKER), "layout-version=5\n");
    Files.createDirectory(root.resolve("accounting"));
    Files.writeString(root.resolve("accounting/operator-data"), "keep me");
    var failure =
        assertThrows(IllegalStateException.class, () -> new DataLayout(root).initialise());
    assertTrue(failure.getMessage().contains("accounting/"));
    assertEquals("5", load(root.resolve(DataLayout.MARKER)).getProperty("layout-version"));
    assertEquals("keep me", Files.readString(root.resolve("accounting/operator-data")));
  }

  @Test
  void current_layout_preserves_existing_accounting_on_a_second_boot(@TempDir Path root)
      throws IOException {
    var layout = new DataLayout(root).initialise();
    Files.createDirectory(layout.accounting());
    Files.writeString(layout.accounting().resolve("checkpoint"), "fixture");
    new DataLayout(root).initialise();
    assertEquals("fixture", Files.readString(layout.accounting().resolve("checkpoint")));
  }
}
