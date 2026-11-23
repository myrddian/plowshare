package io.aeyer.plowshare.server.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.aeyer.plowshare.protocol.FileAccess;
import io.aeyer.plowshare.protocol.FileResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VerifiedFileMutationsTest {
  @TempDir Path root;
  private final List<FileMutationObservation> observations = new ArrayList<>();
  private static final FileMutationObservation ATTEMPT =
      new FileMutationObservation(null, FileMutationObservation.Kind.ATTEMPTED);

  private VerifiedFileMutations mutation() {
    return new VerifiedFileMutations(
        LocalProvider.over(
            FileAccess.of(List.of(root), List.of()),
            List.of(new Grant(Scope.WORKSPACE, Mode.WRITE))),
        observations::add);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void an_edit_checks_the_exact_bytes_and_preserves_the_provider_facts(boolean changes)
      throws Exception {
    Path path = Files.writeString(root.resolve("notes.txt"), "before\r\nold\r\nafter");
    String replacement = changes ? "new" : "old";
    var result = mutation().edit(path, "old", replacement);
    assertEquals(FileResult.EDITED, result.facts().kind());
    assertEquals("before\r\n" + replacement + "\r\nafter", Files.readString(path));
    assertEquals(
        List.of(
            ATTEMPT,
            new FileMutationObservation(
                null,
                changes
                    ? FileMutationObservation.Kind.LOCAL_CHANGED
                    : FileMutationObservation.Kind.LOCAL_UNCHANGED)),
        observations);
  }

  @Test
  void a_refused_edit_preserves_the_original_refusal_and_proves_no_change() throws Exception {
    Path path = Files.writeString(root.resolve("notes.txt"), "original");
    var refusal =
        assertThrows(FileRefusedException.class, () -> mutation().edit(path, "absent", "new"));
    assertEquals(FileResult.NO_MATCH, refusal.facts().kind());
    assertEquals("original", Files.readString(path));
    assertEquals(
        List.of(
            ATTEMPT,
            new FileMutationObservation(null, FileMutationObservation.Kind.LOCAL_UNCHANGED)),
        observations);
  }

  @Test
  void a_move_and_delete_verify_the_postconditions() throws Exception {
    Path from = Files.writeString(root.resolve("from.txt"), "exact bytes\r\n");
    Path to = root.resolve("to.txt");
    assertEquals(FileResult.MOVED, mutation().move(from, to).facts().kind());
    assertFalse(Files.exists(from));
    assertEquals("exact bytes\r\n", Files.readString(to));
    assertEquals(FileResult.DELETED, mutation().delete(to).facts().kind());
    assertFalse(Files.exists(to));
    var verified = new FileMutationObservation(null, FileMutationObservation.Kind.LOCAL_CHANGED);
    assertEquals(List.of(ATTEMPT, verified, ATTEMPT, verified), observations);
  }
}
