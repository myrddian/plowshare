package io.aeyer.plowshare.server.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.testpeer.NodeFiles;
import io.aeyer.plowshare.testpeer.TestWorkspace;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A change made on a client's machine, answered as facts across the wire and worded here — the path
 * a model's {@code file_edit} takes when the person's files are on their own laptop.
 *
 * <p>The Java client is real and the disk is real; the socket is a JSON round trip through a mapper
 * configured as the channel's, so a fact that does not survive binding fails here. What arrives is
 * worded by {@link FileWords} and nothing else: the client sends no sentence for a change at all.
 */
class ClientChangeWordsTest {

  private static final ObjectMapper WIRE =
      new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  @TempDir Path tmp;

  private Path repo;
  private RemoteProvider provider;

  @BeforeEach
  void aClientOverOneTree() throws IOException {
    repo = Files.createDirectory(tmp.resolve("repo")).toRealPath();
    TestWorkspace workspace = new TestWorkspace();
    workspace.set(List.of(repo));
    NodeFiles client = new NodeFiles(workspace);
    provider =
        new RemoteProvider(
            (session, request) -> {
              try {
                String sent =
                    WIRE.writeValueAsString(
                        WIRE.readValue(WIRE.writeValueAsString(request), FileRequest.class));
                FileReply answered = client.answer(WIRE.readValue(sent, FileRequest.class));
                return WIRE.readValue(WIRE.writeValueAsString(answered), FileReply.class);
              } catch (IOException broken) {
                throw new UncheckedIOException(broken);
              }
            },
            "session-1",
            List.of(new Grant(Scope.WORKSPACE, Mode.WRITE)));
  }

  @Test
  void an_edit_made_on_the_client_is_shown_in_the_server_s_words() throws IOException {
    Path file = Files.writeString(repo.resolve("A.txt"), "a\nb\nc\n");

    Changed edited = provider.edit(file, "b", "B");

    assertEquals(
        "The new text is on line 1 now, shown with the 3 lines either side:\n"
            + "[The whole file, lines 0 to 2 of 3, counting from 0 as offset does.]\n"
            + "a\nB\nc",
        FileWords.edited(edited.facts()));
    assertTrue(edited.showedWholeFile());
    assertEquals(0, provider.oldClientReplies());
  }

  @Test
  void a_miss_on_the_client_is_explained_in_the_server_s_words() throws IOException {
    Path file = Files.writeString(repo.resolve("A.md"), "a well-known fact\n");

    FileRefusedException refused =
        assertThrows(FileRefusedException.class, () -> provider.edit(file, "well‑known", "famous"));

    assertEquals(
        "the text to replace is not in "
            + file
            + "; it must match the file exactly,"
            + " spaces and line breaks included — read the file again and copy it\n\n"
            + "`old` has U+2011 NON-BREAKING HYPHEN where the file has '-'. The file's text"
            + " there:\n[The whole file, line 0 of 1, counting from 0 as offset does.]\n"
            + "a well-known fact",
        refused.getMessage());
  }

  @Test
  void a_delete_or_move_of_nothing_says_nothing_was_done_on_this_machine() {
    Path gone = repo.resolve("gone.txt");

    WorkspaceRefusedException delete =
        assertThrows(WorkspaceRefusedException.class, () -> provider.delete(gone));
    WorkspaceRefusedException move =
        assertThrows(
            WorkspaceRefusedException.class, () -> provider.move(gone, repo.resolve("x.txt")));
    WorkspaceRefusedException edit =
        assertThrows(WorkspaceRefusedException.class, () -> provider.edit(gone, "a", "b"));

    assertEquals(
        "there is no file at " + gone + " on this machine, so nothing was deleted",
        delete.getMessage());
    assertEquals(
        "there is no file at " + gone + " on this machine, so nothing was moved",
        move.getMessage());
    assertEquals(
        "there is no file at "
            + gone
            + " on this machine, so nothing was edited;"
            + " to create it, send {\"path\", \"content\"} with the whole file",
        edit.getMessage());
  }

  @Test
  void a_write_reports_what_it_wrote_and_a_create_over_a_file_is_refused() throws IOException {
    Path there = Files.writeString(repo.resolve("B.txt"), "still here");

    Changed written = provider.write(repo.resolve("deep/A.txt"), "one\ntwo\n");
    WorkspaceRefusedException refused =
        assertThrows(WorkspaceRefusedException.class, () -> provider.create(there, "replaced"));

    assertEquals(2, written.facts().lines());
    assertEquals(8L, written.facts().bytes());
    assertEquals(
        "path "
            + there
            + " already exists on this machine, and this write may only"
            + " create a file; read it before replacing it",
        refused.getMessage());
    assertEquals("still here", Files.readString(there));
  }
}
