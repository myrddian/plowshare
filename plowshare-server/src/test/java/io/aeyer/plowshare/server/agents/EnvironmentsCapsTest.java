package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.EnvironmentFile;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where a project's caps come from — spec 2026-09-29 §2: the server's file, then the rooting
 * machine's {@code .plowshare/environment.yml}, key by key, and each value says which it was.
 */
class EnvironmentsCapsTest {

  @Test
  void the_person_s_file_wins_key_by_key_over_the_server_s() {
    ProjectCaps caps =
        Environments.merged(
            new EnvironmentFile.Caps(30, 500, null), new EnvironmentFile.Caps(40, null, 3), null);

    assertEquals(new ProjectCaps.Setting(40, ProjectCaps.PROJECT_FILE), caps.steps());
    assertEquals(new ProjectCaps.Setting(500, ProjectCaps.SERVER_FILE), caps.budget());
    assertEquals(new ProjectCaps.Setting(3, ProjectCaps.PROJECT_FILE), caps.autoContinue());
  }

  @Test
  void auto_increase_is_opt_in_and_a_local_false_overrides_the_server_true() {
    var server = EnvironmentFile.parse("caps:\n  auto-increase: true\n").caps();
    var local = EnvironmentFile.parse("caps:\n  auto-increase: false\n").caps();
    assertTrue(Environments.merged(server, null, null).increasesAutomatically());
    org.junit.jupiter.api.Assertions.assertFalse(
        Environments.merged(server, local, null).increasesAutomatically());
    org.junit.jupiter.api.Assertions.assertFalse(ProjectCaps.NONE.increasesAutomatically());
    assertEquals(
        new ProjectCaps.BooleanSetting(false, ProjectCaps.PROJECT_FILE),
        Environments.merged(server, local, null).autoIncrease());
  }

  @Test
  void nothing_set_is_each_definition_s_own() {
    assertEquals(ProjectCaps.NONE, Environments.merged(null, null, null));
  }

  /**
   * Measured 2026-09-30, orc_318DFD3782228160: no time cap is the behaviour as it was, and no
   * failed-checks limit is five — a run is never left to fail its check for hours unasked.
   */
  @Test
  void no_time_cap_is_none_and_no_failed_checks_limit_is_five() {
    ProjectCaps none = Environments.merged(null, null, null);

    assertEquals(ProjectCaps.Setting.UNSET, none.time());
    assertEquals(new ProjectCaps.Setting(5, ProjectCaps.DEFAULT), none.failedChecks());
    assertEquals(5, none.failedChecksLimit());
    assertEquals(ProjectCaps.NONE.failedChecks(), none.failedChecks());
  }

  @Test
  void the_time_cap_and_the_failed_checks_limit_are_read_key_by_key_as_the_others() {
    ProjectCaps caps =
        Environments.merged(
            new EnvironmentFile.Caps(null, null, null, 120, 3),
            new EnvironmentFile.Caps(null, null, null, 90, null),
            null);

    assertEquals(new ProjectCaps.Setting(90, ProjectCaps.PROJECT_FILE), caps.time());
    assertEquals(new ProjectCaps.Setting(3, ProjectCaps.SERVER_FILE), caps.failedChecks());
    assertEquals(3, caps.failedChecksLimit());
  }

  @Test
  void the_time_cap_is_read_from_the_rooting_machine_s_file(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("environment.yml");
    Files.writeString(file, "caps:\n  budget: 800\n");
    FakeFiles machine =
        new FakeFiles()
            .withFile(".plowshare/environment.yml", "caps:\n  time: 45\n  failed-checks: 2\n");

    ProjectCaps caps = new Environments(name -> 7L, id -> file, machine).caps("story", "s-1");

    assertEquals(new ProjectCaps.Setting(45, ProjectCaps.PROJECT_FILE), caps.time());
    assertEquals(new ProjectCaps.Setting(2, ProjectCaps.PROJECT_FILE), caps.failedChecks());
  }

  @Test
  void an_unknown_caps_key_is_refused_as_today(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("environment.yml");
    Files.writeString(file, "caps:\n  minutes: 90\n");

    ProjectCaps caps = new Environments(name -> 7L, id -> file, null).caps("story", null);

    assertEquals(ProjectCaps.Setting.UNSET, caps.time());
    assertTrue(caps.unreadable().contains("'minutes' is not a caps setting"), caps.unreadable());
    assertTrue(caps.unreadable().contains("time, failed-checks"), caps.unreadable());
  }

  @Test
  void the_server_s_file_is_read_for_a_project_nobody_roots(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("environment.yml");
    Files.writeString(file, "caps:\n  budget: 800\n");
    Environments environments = new Environments(name -> 7L, id -> file, null);

    assertEquals(
        new ProjectCaps.Setting(800, ProjectCaps.SERVER_FILE),
        environments.caps("story", null).budget());
    assertEquals(ProjectCaps.NONE, environments.caps(null, null));
  }

  @Test
  void an_unreadable_file_sets_nothing_and_says_why(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("environment.yml");
    Files.writeString(file, "caps:\n  steps: many\n");

    ProjectCaps caps = new Environments(name -> 7L, id -> file, null).caps("story", null);

    assertEquals(ProjectCaps.Setting.UNSET, caps.steps());
    assertTrue(caps.unreadable().contains("line 2"), caps.unreadable());
  }

  @Test
  void the_rooting_machine_s_file_is_read_through_its_session(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("environment.yml");
    Files.writeString(file, "caps:\n  budget: 800\n  steps: 30\n");
    FakeFiles machine =
        new FakeFiles().withFile(".plowshare/environment.yml", "caps:\n  steps: 40\n");

    ProjectCaps caps = new Environments(name -> 7L, id -> file, machine).caps("story", "s-1");

    assertEquals(new ProjectCaps.Setting(40, ProjectCaps.PROJECT_FILE), caps.steps());
    assertEquals(new ProjectCaps.Setting(800, ProjectCaps.SERVER_FILE), caps.budget());
  }

  @Test
  void an_unreadable_file_on_the_rooting_machine_leaves_the_server_s_caps(@TempDir Path dir)
      throws Exception {
    Path file = dir.resolve("environment.yml");
    Files.writeString(file, "caps:\n  budget: 800\n");
    FakeFiles machine =
        new FakeFiles().withFile(".plowshare/environment.yml", "caps:\n  steps: many\n");

    ProjectCaps caps = new Environments(name -> 7L, id -> file, machine).caps("story", "s-1");

    assertEquals(ProjectCaps.Setting.UNSET, caps.steps());
    assertEquals(new ProjectCaps.Setting(800, ProjectCaps.SERVER_FILE), caps.budget());
    assertTrue(caps.unreadable().contains(".plowshare/environment.yml"), caps.unreadable());
  }

  /**
   * Task 12's review: the person's caps no longer vanish when the rooting machine goes away or its
   * read fails — the caps last read stand in, and a failed read says so. A file that is not there
   * is a read of no caps, and replaces them.
   */
  @Test
  void the_person_s_caps_as_last_read_stand_in_when_the_machine_is_gone_or_its_read_fails(
      @TempDir Path dir) {
    FakeFiles machine =
        new FakeFiles().withFile(".plowshare/environment.yml", "caps:\n  steps: 40\n");
    io.aeyer.plowshare.server.files.SessionChannel failing =
        (session, request) -> {
          throw new io.aeyer.plowshare.server.files.WorkspaceUnavailableException(
              "the session went away mid-read");
        };
    java.util.concurrent.atomic.AtomicReference<io.aeyer.plowshare.server.files.SessionChannel>
        now = new java.util.concurrent.atomic.AtomicReference<>(machine);
    Environments environments =
        new Environments(
            name -> 7L,
            id -> dir.resolve("none.yml"),
            (session, request) -> now.get().ask(session, request));

    assertEquals(
        new ProjectCaps.Setting(40, ProjectCaps.PROJECT_FILE),
        environments.caps("story", "s-1").steps());

    assertEquals(
        new ProjectCaps.Setting(40, ProjectCaps.PROJECT_FILE),
        environments.caps("story", null).steps(),
        "nobody roots it now");

    now.set(failing);
    ProjectCaps unread = environments.caps("story", "s-1");
    assertEquals(new ProjectCaps.Setting(40, ProjectCaps.PROJECT_FILE), unread.steps());
    assertTrue(
        unread.unreadable().contains("the session went away mid-read")
            && unread.unreadable().contains("its caps as last read are used"),
        unread.unreadable());

    now.set(new FakeFiles());
    ProjectCaps absent = environments.caps("story", "s-1");
    assertEquals(
        ProjectCaps.NONE,
        absent,
        "a file that is not there sets no caps, and says" + " nothing is wrong");
    assertEquals(ProjectCaps.NONE, environments.caps("story", null));
  }

  @Test
  void a_project_with_no_server_file_has_no_caps_and_nothing_to_say(@TempDir Path dir) {
    ProjectCaps caps =
        new Environments(name -> 7L, id -> dir.resolve("absent.yml"), null).caps("story", null);

    assertEquals(ProjectCaps.NONE, caps);
  }
}
