package io.aeyer.plowshare.client.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.FileResult;
import io.aeyer.plowshare.protocol.Found;
import io.aeyer.plowshare.protocol.Needle;
import io.aeyer.plowshare.protocol.Replacement;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.protocol.Window;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The client's own check, against the workspace this session holds now.
 *
 * <h2>What this file is for, and what {@code FileChannelTest} is for</h2>
 *
 * <p>Everything here is a real directory on a real disk with no socket anywhere: a request goes in
 * and a reply comes out. That is the whole of what makes the client the enforcement point — the
 * socket is only how the request arrived, and it is measured over a real loopback port in {@code
 * FileChannelTest} on the server side, where both halves are on one classpath.
 *
 * <h2>Two spellings of one tree, for the same reason {@code FileAccessTest} has them</h2>
 *
 * <p>{@code @TempDir} hands out a path under {@code /var}, which is itself a symlink to {@code
 * /private/var} on this host, so a workspace stored as it was typed and a candidate resolved
 * through the link are two different strings for one directory. A client that compared them as
 * typed would refuse every file in its own workspace.
 */
class ClientEnforcerTest {

  /**
   * The window every test that is not about windows asks for: the first one of the file, which is
   * what {@link FileRequest#window()} makes of a frame that named none. {@code LocalProviderTest}
   * holds the same constant for the same reason — every fixture outside the windowing section is a
   * handful of lines, so this returns all of them and those tests go on asking what they asked
   * before.
   */
  private static final Window FIRST = Window.of(0, Window.MAX_WINDOW_LINES);

  @TempDir Path tmp;

  private Path repo;
  private Path other;
  private Workspace workspace;
  private ClientEnforcer enforcer;

  @BeforeEach
  void layOutADisk() throws IOException {
    repo = Files.createDirectory(tmp.resolve("repo"));
    other = Files.createDirectory(tmp.resolve("other"));
    workspace = new Workspace();
    enforcer = new ClientEnforcer(workspace);
  }

  // --- roots ---------------------------------------------------------------

  @Test
  void the_roots_this_session_offers_are_the_ones_a_human_set() throws IOException {
    workspace.set(List.of(repo));

    FileReply reply = enforcer.answer(FileRequest.roots("r1"));

    assertEquals(FileReply.OK, reply.outcome());
    assertEquals(List.of(repo.toRealPath().toString()), reply.paths());
  }

  @Test
  void the_roots_are_advertised_canonical_and_not_as_they_were_typed() throws IOException {
    // FileProvider.roots() requires it and ProviderRouter depends on it: a
    // root advertised through a symlink matches no canonicalised candidate,
    // so the server would route every file in this workspace to nobody.
    Path link = Files.createSymbolicLink(tmp.resolve("link-to-repo"), repo);
    workspace.set(List.of(link));

    List<String> advertised = enforcer.answer(FileRequest.roots("r1")).paths();

    assertEquals(List.of(repo.toRealPath().toString()), advertised);
    assertNotEquals(
        List.of(link.toString()),
        advertised,
        "the spelling a human typed is not what the server can compare against");
  }

  @Test
  void a_session_with_no_workspace_answers_an_empty_root_list_rather_than_refusing() {
    // "What can I see?" is the question, and "nothing yet" is a true answer
    // to it. The sentence saying WHY is reached through a search, which is
    // how ProviderRouter.absence asks a provider which state it is in.
    FileReply reply = enforcer.answer(FileRequest.roots("r1"));

    assertEquals(FileReply.OK, reply.outcome());
    assertEquals(List.of(), reply.paths());
  }

  // --- the workspace that moved --------------------------------------------

  @Test
  void a_path_under_the_workspace_this_session_has_now_is_read() throws IOException {
    Files.writeString(repo.resolve("A.java"), "class A {}\n");
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(FileRequest.read("r1", repo.resolve("A.java").toString(), FIRST));

    assertEquals(FileReply.OK, reply.outcome());
    assertEquals(
        List.of("class A {}"),
        reply.span().lines(),
        "the lines are the reply's only carrier now, and a terminator is not part"
            + " of the line it ends");
  }

  @Test
  void a_path_that_was_in_the_workspace_before_it_moved_says_that_it_moved() throws IOException {
    // THE CORRECTABLE REFUSAL THE SPEC ASKS FOR. The server routed on roots
    // it read a round trip ago; the human has since moved the workspace. The
    // client refuses, and the refusal names what happened rather than saying
    // the file is not there — which is what a model would otherwise conclude,
    // having read that same file successfully two turns earlier.
    Files.writeString(repo.resolve("A.java"), "class A {}\n");
    workspace.set(List.of(repo));
    workspace.set(List.of(other));

    FileReply reply =
        enforcer.answer(FileRequest.read("r1", repo.resolve("A.java").toString(), FIRST));

    assertEquals(
        FileReply.REFUSED,
        reply.outcome(),
        "correctable, not an error: every other path on this run still works");
    assertEquals(
        FileResult.WORKSPACE_MOVED,
        rule(reply),
        "the model is told the workspace moved, which it can act on");
    assertEquals(
        List.of(other.toRealPath().toString()),
        reply.result().roots(),
        "and where it moved to, so the next turn is a real one");
  }

  @Test
  void a_path_that_was_never_in_any_workspace_is_not_told_that_one_moved() {
    // Trap 7 from the other side. "The workspace moved" said about a path
    // nobody ever granted names a situation that does not hold, and it would
    // send a model asking for its roots again over and over — the refusal
    // would look correctable and never be corrected.
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(FileRequest.read("r1", other.resolve("secret.txt").toString(), FIRST));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(FileResult.OUTSIDE, rule(reply));
  }

  @Test
  void only_the_last_workspace_counts_as_the_one_it_moved_from() throws IOException {
    // One step back and no further: a full history grows for the life of a
    // session, and the second-to-last workspace is not a fact anybody acts
    // on. This is the instrument for that bound — without it, a session that
    // has moved twice would still claim the first workspace "moved".
    //
    // The third directory has to exist. A workspace pointing at nothing is
    // an OUTAGE and is answered before containment is reached at all, so
    // this test read "no longer there" for both paths and passed its first
    // assertion for the wrong reason — which is what its second assertion
    // caught.
    Path third = Files.createDirectory(tmp.resolve("third"));
    workspace.set(List.of(repo));
    workspace.set(List.of(other));
    workspace.set(List.of(third));

    assertEquals(
        FileResult.OUTSIDE,
        rule(enforcer.answer(FileRequest.read("r1", repo.resolve("A.java").toString(), FIRST))),
        "two moves ago is not where this run was");
    assertEquals(
        FileResult.WORKSPACE_MOVED,
        rule(enforcer.answer(FileRequest.read("r2", other.resolve("A.java").toString(), FIRST))),
        "one move ago is");
  }

  // --- containment ---------------------------------------------------------

  @Test
  void a_path_spelled_through_a_link_out_of_the_workspace_is_refused_by_where_it_points()
      throws IOException {
    // The whole reason the containment core is shared rather than rewritten
    // here: this is FileAccess.canonical's rule, and a client that settled
    // `..` and links textually would hand back a file outside the workspace.
    Files.writeString(other.resolve("secret.txt"), "not yours");
    Files.createSymbolicLink(repo.resolve("escape"), other);
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(
            FileRequest.read("r1", repo.resolve("escape/secret.txt").toString(), FIRST));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(
        null,
        reply.span(),
        "and nothing from the far side of the link came" + " back on the frame either");
  }

  @Test
  void a_write_through_a_link_out_of_the_workspace_leaves_nothing_behind() throws IOException {
    // Measured in FileAccessTest: writing to a dangling link creates its
    // target, outside the root. The check is on the canonical form, so it
    // never gets that far.
    Files.createSymbolicLink(repo.resolve("gate"), other.resolve("gone.txt"));
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(FileRequest.write("r1", repo.resolve("gate").toString(), "landed"));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertFalse(
        Files.exists(other.resolve("gone.txt")),
        "the byte did not land on the far side of the link");
  }

  @Test
  void a_write_inside_the_workspace_lands_on_disk_verbatim() throws IOException {
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(
            FileRequest.write("r1", repo.resolve("deep/new/A.java").toString(), "class A {}\n"));

    assertEquals(FileReply.OK, reply.outcome());
    assertEquals(
        "class A {}\n",
        Files.readString(repo.resolve("deep/new/A.java")),
        "including the trailing newline, which is part of a file");
  }

  @Test
  void an_empty_file_is_an_ordinary_thing_to_write() throws IOException {
    // Truncating a file to nothing is an ordinary edit, and a client that
    // refused blank content would make it impossible.
    Files.writeString(repo.resolve("A.java"), "something that was there");
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(FileRequest.write("r1", repo.resolve("A.java").toString(), ""));

    assertEquals(FileReply.OK, reply.outcome());
    assertEquals(
        "",
        Files.readString(repo.resolve("A.java")),
        "and TRUNCATE_EXISTING, or the old tail would still be there");
  }

  @Test
  void a_write_with_no_content_at_all_is_refused_rather_than_emptying_the_file()
      throws IOException {
    // Absent is not the same as empty, and the two are one character apart
    // in a JSON body. A client that read a missing field as "" would empty a
    // file over a malformed request.
    Files.writeString(repo.resolve("A.java"), "still here");
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(
            new FileRequest(
                "r1",
                FileRequest.WRITE,
                repo.resolve("A.java").toString(),
                null,
                null,
                null,
                null,
                null,
                null));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals("still here", Files.readString(repo.resolve("A.java")));
  }

  @Test
  void a_create_only_write_over_an_existing_file_is_refused_and_leaves_it() throws IOException {
    // How a whole-file write that has not read its target is kept from
    // overwriting it: the server sends create-only, and CREATE_NEW makes the
    // check and the open one step on this machine.
    Files.writeString(repo.resolve("A.java"), "still here");
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(FileRequest.create("r1", repo.resolve("A.java").toString(), "replaced"));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(FileResult.EXISTS, reply.result().reason(), String.valueOf(reply));
    assertNull(reply.sentence(), "a change is answered with facts, never words");
    assertEquals("still here", Files.readString(repo.resolve("A.java")));
  }

  @Test
  void a_create_only_write_of_a_new_file_lands() throws IOException {
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(
            FileRequest.create("r1", repo.resolve("deep/B.java").toString(), "class B {}\n"));

    assertEquals(FileReply.OK, reply.outcome(), reply.sentence());
    assertEquals("class B {}\n", Files.readString(repo.resolve("deep/B.java")));
  }

  // --- edit ----------------------------------------------------------------

  @Test
  void an_edit_of_a_crlf_file_changes_only_the_text_it_names() throws IOException {
    // THE REASON EDIT IS A CHANNEL OP. A read hands the server lines with
    // the carriage returns gone; the bytes are only here.
    Path dos =
        Files.write(
            repo.resolve("dos.txt"), "one\r\ntwo\r\nthree\r\n".getBytes(StandardCharsets.UTF_8));
    workspace.set(List.of(repo));

    FileReply reply = enforcer.answer(FileRequest.edit("r1", dos.toString(), "two", "TWO"));

    assertEquals(FileReply.OK, reply.outcome(), reply.sentence());
    assertEquals(
        "one\r\nTWO\r\nthree\r\n", new String(Files.readAllBytes(dos), StandardCharsets.UTF_8));
  }

  @Test
  void an_edit_of_a_file_with_no_final_newline_does_not_add_one() throws IOException {
    Path bare = Files.writeString(repo.resolve("bare.md"), "alpha\nbeta");
    workspace.set(List.of(repo));

    FileReply reply = enforcer.answer(FileRequest.edit("r1", bare.toString(), "alpha", ""));

    assertEquals(FileReply.OK, reply.outcome(), reply.sentence());
    assertEquals(
        "\nbeta",
        Files.readString(bare),
        "an empty replacement is an ordinary edit, and no newline was added");
  }

  @Test
  void an_edit_whose_text_occurs_twice_is_refused_with_the_count() throws IOException {
    Path file = Files.writeString(repo.resolve("A.java"), "aaa");
    workspace.set(List.of(repo));

    FileReply reply = enforcer.answer(FileRequest.edit("r1", file.toString(), "aa", "b"));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(FileResult.manyMatches(file.toString(), 2), reply.result());
    assertEquals("aaa", Files.readString(file));
  }

  @Test
  void an_edit_whose_text_is_absent_is_refused_and_changes_nothing() throws IOException {
    Path file = Files.writeString(repo.resolve("A.java"), "class A {}\n");
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(FileRequest.edit("r1", file.toString(), "class B", "class C"));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(FileResult.NO_MATCH, reply.result().kind(), String.valueOf(reply));
    assertEquals(file.toString(), reply.result().path());
    assertEquals("class A {}\n", Files.readString(file));
  }

  @Test
  void an_edit_of_an_absent_file_is_refused_and_creates_nothing() {
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(FileRequest.edit("r1", repo.resolve("gone.txt").toString(), "a", "b"));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(FileResult.NO_FILE, reply.result().kind(), String.valueOf(reply));
    assertFalse(Files.exists(repo.resolve("gone.txt")));
  }

  @Test
  void an_edit_of_a_directory_is_refused() throws IOException {
    Files.createDirectory(repo.resolve("src"));
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(FileRequest.edit("r1", repo.resolve("src").toString(), "a", "b"));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(FileResult.DIRECTORY, reply.result().reason(), String.valueOf(reply));
  }

  @Test
  void an_edit_of_a_link_is_refused_and_the_file_it_points_at_is_untouched() throws IOException {
    Path real = Files.writeString(repo.resolve("real.txt"), "keep");
    Path link = Files.createSymbolicLink(repo.resolve("alias.txt"), real);
    workspace.set(List.of(repo));

    FileReply reply = enforcer.answer(FileRequest.edit("r1", link.toString(), "keep", "lost"));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(FileResult.LINK, reply.result().reason(), String.valueOf(reply));
    assertEquals("keep", Files.readString(real));
  }

  @Test
  void an_edit_of_bytes_that_are_not_utf8_is_refused_and_leaves_them() throws IOException {
    byte[] blob = {(byte) 0xC3, (byte) 0x28, 'a'};
    Path file = Files.write(repo.resolve("blob.bin"), blob);
    workspace.set(List.of(repo));

    FileReply reply = enforcer.answer(FileRequest.edit("r1", file.toString(), "a", "b"));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(
        FileResult.notText(FileRequest.EDIT, file.toString(), FileResult.NOT_UTF8), reply.result());
    assertTrue(java.util.Arrays.equals(blob, Files.readAllBytes(file)));
  }

  // --- edit: what the answer shows ---------------------------------------------

  /** {@code l0\n} through {@code l<n-1>\n}. */
  private static String numbered(int n) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < n; i++) {
      out.append('l').append(i).append('\n');
    }
    return out.toString();
  }

  @Test
  void an_edit_answers_with_the_changed_lines_numbered_as_this_machine_reads_them()
      throws IOException {
    Path file = Files.writeString(repo.resolve("A.txt"), numbered(20));
    workspace.set(List.of(repo));

    FileReply reply = enforcer.answer(FileRequest.edit("r1", file.toString(), "l10\n", "A\nB\n"));

    assertEquals(FileReply.OK, reply.outcome(), String.valueOf(reply));
    assertNull(reply.sentence(), "the server words it");
    FileResult result = reply.result();
    assertEquals(FileResult.EDITED, result.kind());
    assertEquals(10, result.first());
    assertEquals(11, result.last());
    assertEquals(7, result.excerpt().from());
    assertEquals(21, result.excerpt().total());
    assertEquals(
        List.of("l7", "l8", "l9", "A", "B", "l11", "l12", "l13"), result.excerpt().lines());
    FileReply read = enforcer.answer(FileRequest.read("r2", file.toString(), Window.of(7, 8)));
    assertEquals(
        List.of("l7", "l8", "l9", "A", "B", "l11", "l12", "l13"),
        read.span().lines(),
        "offset 7 of a read is the first line the edit showed");
  }

  @Test
  void a_long_replacement_is_shown_by_its_ends() throws IOException {
    Path file = Files.writeString(repo.resolve("A.txt"), numbered(20));
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(FileRequest.edit("r1", file.toString(), "l10\n", "n\n".repeat(100)));

    assertEquals(FileReply.OK, reply.outcome(), String.valueOf(reply));
    assertEquals(66, reply.result().excerpt().omitted(), String.valueOf(reply));
    assertEquals(27, reply.result().excerpt().from() + reply.result().excerpt().gap());
    assertTrue(reply.result().excerpt().lines().size() <= Replacement.MAX_SHOWN_LINES);
  }

  @Test
  void a_whole_file_write_answers_what_it_wrote_and_no_words() {
    workspace.set(List.of(repo));
    String named = repo.resolve("W.txt").toString();

    FileReply reply = enforcer.answer(FileRequest.write("r1", named, "a\nb\n"));

    assertEquals(FileReply.OK, reply.outcome());
    assertNull(reply.sentence());
    assertEquals(FileResult.written(named, 4, 2), reply.result());
  }

  @Test
  void an_edit_that_differs_only_in_indentation_says_so_and_shows_the_file_s_text()
      throws IOException {
    Path file = Files.writeString(repo.resolve("A.java"), "class A {\n    int x = 1;\n}\n");
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(
            FileRequest.edit(
                "r1", file.toString(), "class A {\n  int x = 1;", "class A {\n  int x = 2;"));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(FileResult.WHITESPACE, reply.result().near(), String.valueOf(reply));
    assertEquals(List.of("class A {", "    int x = 1;"), reply.result().excerpt().lines());
    assertEquals("class A {\n    int x = 1;\n}\n", Files.readString(file), "shown, never applied");
  }

  @Test
  void an_edit_with_a_look_alike_character_names_it() throws IOException {
    Path file = Files.writeString(repo.resolve("A.md"), "a well-known fact\n");
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(FileRequest.edit("r1", file.toString(), "well‑known", "famous"));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(
        List.of(new FileResult.Difference(0x2011, '-')),
        reply.result().differences(),
        String.valueOf(reply));
    assertEquals("a well-known fact\n", Files.readString(file));
  }

  @Test
  void a_paraphrased_edit_is_shown_the_closest_lines_and_an_unrelated_one_nothing()
      throws IOException {
    Path file =
        Files.writeString(repo.resolve("A.java"), "a();\nif (ready) {\n    go();\n}\nb();\n");
    workspace.set(List.of(repo));

    FileReply near =
        enforcer.answer(
            FileRequest.edit("r1", file.toString(), "if (ready) {\n    start();\n}", "x"));
    FileReply far = enforcer.answer(FileRequest.edit("r2", file.toString(), "zzz", "x"));

    assertEquals(FileResult.CLOSEST, near.result().near(), String.valueOf(near));
    assertEquals(1, near.result().excerpt().from());
    assertEquals(List.of("if (ready) {", "    go();", "}"), near.result().excerpt().lines());
    assertNull(far.result().near(), String.valueOf(far));
    assertNull(far.result().excerpt());
  }

  @Test
  void an_edit_of_an_absent_file_reports_no_file_for_the_server_to_explain() {
    // The server says how to create one; this says only that there is none.
    workspace.set(List.of(repo));
    String named = repo.resolve("gone.txt").toString();

    FileReply reply = enforcer.answer(FileRequest.edit("r1", named, "a", "b"));

    assertEquals(FileResult.noFile(FileRequest.EDIT, named), reply.result());
    assertNull(reply.sentence());
  }

  @Test
  void an_edit_with_no_text_to_replace_is_refused() throws IOException {
    Path file = Files.writeString(repo.resolve("A.java"), "still here");
    workspace.set(List.of(repo));

    FileReply reply = enforcer.answer(FileRequest.edit("r1", file.toString(), null, "x"));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals("still here", Files.readString(file));
  }

  // --- delete --------------------------------------------------------------

  @Test
  void a_delete_removes_the_file() throws IOException {
    Path file = Files.writeString(repo.resolve("A.java"), "class A {}\n");
    workspace.set(List.of(repo));

    FileReply reply = enforcer.answer(FileRequest.delete("r1", file.toString()));

    assertEquals(FileReply.OK, reply.outcome(), reply.sentence());
    assertFalse(Files.exists(file));
  }

  @Test
  void a_delete_of_a_directory_is_refused_and_leaves_it() throws IOException {
    Path dir = Files.createDirectory(repo.resolve("empty"));
    workspace.set(List.of(repo));

    FileReply reply = enforcer.answer(FileRequest.delete("r1", dir.toString()));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(FileResult.DIRECTORY, reply.result().reason(), String.valueOf(reply));
    assertTrue(Files.isDirectory(dir));
  }

  @Test
  void a_delete_of_a_link_is_refused_and_removes_neither_it_nor_its_target() throws IOException {
    Path real = Files.writeString(repo.resolve("real.txt"), "keep");
    Path link = Files.createSymbolicLink(repo.resolve("alias.txt"), real);
    workspace.set(List.of(repo));

    FileReply reply = enforcer.answer(FileRequest.delete("r1", link.toString()));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(FileResult.LINK, reply.result().reason(), String.valueOf(reply));
    assertTrue(Files.exists(real));
    assertTrue(Files.isSymbolicLink(link));
  }

  @Test
  void a_delete_of_an_absent_path_is_refused() {
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(FileRequest.delete("r1", repo.resolve("gone.txt").toString()));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(FileResult.NO_FILE, reply.result().kind(), String.valueOf(reply));
  }

  @Test
  void a_delete_outside_the_workspace_is_refused_and_removes_nothing() throws IOException {
    Path outside = Files.writeString(other.resolve("theirs.txt"), "not yours");
    workspace.set(List.of(repo));

    FileReply reply = enforcer.answer(FileRequest.delete("r1", outside.toString()));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertTrue(Files.exists(outside));
  }

  // --- move ----------------------------------------------------------------

  @Test
  void a_move_renames_the_file_and_creates_the_directories_above_it() throws IOException {
    Path from = Files.writeString(repo.resolve("A.java"), "class A {}\r\n");
    Path to = repo.resolve("deep/new/B.java");
    workspace.set(List.of(repo));

    FileReply reply = enforcer.answer(FileRequest.move("r1", from.toString(), to.toString()));

    assertEquals(FileReply.OK, reply.outcome(), reply.sentence());
    assertFalse(Files.exists(from));
    assertEquals("class A {}\r\n", Files.readString(to));
  }

  @Test
  void a_move_onto_an_existing_file_is_refused_and_both_are_left() throws IOException {
    Path from = Files.writeString(repo.resolve("A.java"), "a");
    Path to = Files.writeString(repo.resolve("B.java"), "b");
    workspace.set(List.of(repo));

    FileReply reply = enforcer.answer(FileRequest.move("r1", from.toString(), to.toString()));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(FileResult.DESTINATION_EXISTS, reply.result().reason(), String.valueOf(reply));
    assertEquals("a", Files.readString(from));
    assertEquals("b", Files.readString(to));
  }

  @Test
  void a_move_onto_a_dangling_link_is_refused_as_an_existing_destination() throws IOException {
    Path from = Files.writeString(repo.resolve("A.java"), "a");
    Path to = Files.createSymbolicLink(repo.resolve("B.java"), repo.resolve("nowhere"));
    workspace.set(List.of(repo));

    FileReply reply = enforcer.answer(FileRequest.move("r1", from.toString(), to.toString()));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertTrue(Files.exists(from));
    assertFalse(Files.exists(repo.resolve("nowhere")));
  }

  @Test
  void a_move_of_a_directory_a_link_or_an_absent_path_is_refused() throws IOException {
    Path dir = Files.createDirectory(repo.resolve("src"));
    Path real = Files.writeString(repo.resolve("real.txt"), "keep");
    Path link = Files.createSymbolicLink(repo.resolve("alias.txt"), real);
    workspace.set(List.of(repo));

    FileReply ofDir =
        enforcer.answer(FileRequest.move("r1", dir.toString(), repo.resolve("lib").toString()));
    FileReply ofLink =
        enforcer.answer(
            FileRequest.move("r2", link.toString(), repo.resolve("moved.txt").toString()));
    FileReply ofNothing =
        enforcer.answer(
            FileRequest.move(
                "r3", repo.resolve("gone.txt").toString(), repo.resolve("x.txt").toString()));

    assertEquals(FileReply.REFUSED, ofDir.outcome());
    assertEquals(FileResult.DIRECTORY, ofDir.result().reason(), String.valueOf(ofDir));
    assertEquals(FileReply.REFUSED, ofLink.outcome());
    assertEquals(FileResult.LINK, ofLink.result().reason(), String.valueOf(ofLink));
    assertEquals(FileReply.REFUSED, ofNothing.outcome());
    assertEquals(FileResult.NO_FILE, ofNothing.result().kind(), String.valueOf(ofNothing));
    assertTrue(Files.isDirectory(dir));
    assertTrue(Files.isSymbolicLink(link));
    assertFalse(Files.exists(repo.resolve("moved.txt")));
  }

  @Test
  void a_move_out_of_the_workspace_is_refused_at_the_destination() throws IOException {
    Path from = Files.writeString(repo.resolve("A.java"), "a");
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(
            FileRequest.move("r1", from.toString(), other.resolve("A.java").toString()));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(FileResult.OUTSIDE, reply.result().reason(), String.valueOf(reply));
    assertEquals(
        other.resolve("A.java").toString(),
        reply.result().path(),
        "the fence names the path it refused, which is the destination");
    assertTrue(Files.exists(from));
    assertFalse(Files.exists(other.resolve("A.java")));
  }

  @Test
  void a_move_into_the_workspace_from_outside_is_refused_at_the_source() throws IOException {
    Path from = Files.writeString(other.resolve("A.java"), "a");
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(FileRequest.move("r1", from.toString(), repo.resolve("A.java").toString()));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(FileResult.OUTSIDE, reply.result().reason(), String.valueOf(reply));
    assertTrue(Files.exists(from));
    assertFalse(Files.exists(repo.resolve("A.java")));
  }

  @Test
  void a_move_to_or_from_a_hidden_path_is_refused_on_both_sides() throws IOException {
    // The fence on both paths: a hidden destination would put a file where
    // no tool can see it, and a hidden source would carry one into view.
    Path visible = Files.writeString(repo.resolve("A.java"), "a");
    Path hidden = Files.writeString(repo.resolve(".env"), "PLOWSHARE_TOKEN=notreal\n");
    workspace.set(List.of(repo));

    FileReply into =
        enforcer.answer(
            FileRequest.move("r1", visible.toString(), repo.resolve(".secret/A.java").toString()));
    FileReply outOf =
        enforcer.answer(
            FileRequest.move("r2", hidden.toString(), repo.resolve("env.txt").toString()));

    assertEquals(FileReply.REFUSED, into.outcome(), into.sentence());
    assertEquals(FileReply.REFUSED, outOf.outcome(), outOf.sentence());
    assertTrue(Files.exists(visible));
    assertFalse(Files.exists(repo.resolve(".secret")));
    assertTrue(Files.exists(hidden));
    assertFalse(Files.exists(repo.resolve("env.txt")));
  }

  // --- reading -------------------------------------------------------------

  @Test
  void a_directory_is_refused_rather_than_handed_to_the_decoder() {
    // Measured on macOS: Files.newInputStream(directory, NOFOLLOW_LINKS)
    // succeeds, so opening first and hoping is not a regular-file check.
    workspace.set(List.of(repo));

    FileReply reply = enforcer.answer(FileRequest.read("r1", repo.toString(), FIRST));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(
        FileResult.refused(FileRequest.READ, FileResult.DIRECTORY, repo.toString()),
        reply.result(),
        "a directory is said to be one, as a change of it says");
  }

  @Test
  void bytes_that_are_not_utf8_are_reported_and_never_substituted() throws IOException {
    // THE ONE READ RULE THAT CHANGES AN ANSWER RATHER THAN A REFUSAL, which
    // is why it matches the server exactly. `new String(bytes, UTF_8)`
    // substitutes U+FFFD silently — measured — so a client written that way
    // sends a page of replacement characters over the wire and the server
    // hands it to a model as the file.
    Files.write(repo.resolve("blob.bin"), new byte[] {(byte) 0xC3, (byte) 0x28});
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(FileRequest.read("r1", repo.resolve("blob.bin").toString(), FIRST));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(
        FileResult.notText(
            FileRequest.READ, repo.resolve("blob.bin").toString(), FileResult.NOT_UTF8),
        reply.result(),
        "and it did not decode it anyway");
    assertNull(reply.span());
  }

  @Test
  void a_file_larger_than_this_client_will_read_is_refused_and_not_truncated() throws IOException {
    workspace.set(List.of(repo));
    Path huge = repo.resolve("huge.log");
    Files.write(huge, new byte[(int) ClientEnforcer.MAX_FILE_BYTES + 1]);

    FileReply reply = enforcer.answer(FileRequest.read("r1", huge.toString(), FIRST));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(null, reply.span(), "refused at, never truncated to");
  }

  @Test
  void a_file_of_four_megabytes_is_ordinary_and_is_read() throws IOException {
    // The accepted side, written as a literal so it does not move with the
    // constant: a fixture derived from the limit it is meant to pin holds
    // nothing. Lower MAX_FILE_BYTES below four megabytes and this fails.
    //
    // ORDINARY LINES, and that word is load-bearing. This fixture was four
    // megabytes with no terminator in it, which is one line and is now a
    // refusal — so it would have gone on failing for a reason that has
    // nothing to do with the constant it is here to pin.
    workspace.set(List.of(repo));
    Path big = repo.resolve("big.log");
    Files.writeString(big, ("x".repeat(63) + "\n").repeat(4 * 1024 * 1024 / 64));

    FileReply reply = enforcer.answer(FileRequest.read("r1", big.toString(), FIRST));

    assertEquals(FileReply.OK, reply.outcome(), reply.sentence());
    assertTrue(
        reply.span().more(),
        "a window of it, since the file is far past MAX_WINDOW_BYTES — and a window is"
            + " what says the whole four megabytes were opened and counted");
    assertEquals(4 * 1024 * 1024 / 64, reply.span().totalLines());
  }

  @Test
  void a_line_no_window_can_carry_is_refused_rather_than_reported_as_an_outage()
      throws IOException {
    // The direction is the whole of it. `answer` turns an unexpected
    // RuntimeException into UNAVAILABLE, which ENDS THE RUN — and Window
    // raises unchecked, because plowshare-protocol has neither side's
    // refusal type. So a translation that was merely forgotten would swap a
    // dead session for a dead run and look fixed from the outside.
    workspace.set(List.of(repo));
    Path bundle = repo.resolve("bundle.min.js");
    Files.writeString(bundle, "x".repeat(Window.MAX_WINDOW_BYTES * 2) + "\n");

    FileReply reply = enforcer.answer(FileRequest.read("r1", bundle.toString(), FIRST));

    assertEquals(FileReply.REFUSED, reply.outcome(), reply.sentence());
    assertEquals(null, reply.span(), "a refusal carries no window");
    assertEquals(
        FileResult.lineTooWide(
            FileRequest.READ,
            bundle.toString(),
            0,
            Window.MAX_WINDOW_BYTES * 2L,
            Window.MAX_WINDOW_BYTES),
        reply.result(),
        "the line, its size and the bound — the server words what still works");
  }

  @Test
  void the_file_a_read_refuses_is_still_searchable() throws IOException {
    // What makes the refusal above a redirection rather than a wall, and it
    // is asserted here rather than trusted: the sentence names file_grep, so
    // the search had better answer on the file the read would not.
    workspace.set(List.of(repo));
    Path bundle = repo.resolve("bundle.min.js");
    Files.writeString(bundle, "var a=1;" + "y".repeat(Window.MAX_WINDOW_BYTES * 2) + "needle\n");

    FileReply reply =
        enforcer.answer(FileRequest.grep("g", bundle.toString(), new Needle("needle", false)));

    assertEquals(FileReply.OK, reply.outcome(), reply.sentence());
    assertEquals(1, reply.found().matches().size());
    Found.Match hit = reply.found().matches().get(0);
    assertEquals(0, hit.offset());
    assertTrue(
        hit.truncated(),
        "cut to what a match may carry, which is exactly why the search survives a file"
            + " the window cannot");
    assertEquals(Needle.MAX_LINE_CHARS, hit.line().length());
  }

  @Test
  void a_missing_file_inside_the_workspace_says_so_rather_than_saying_outside() {
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(FileRequest.read("r1", repo.resolve("nope.java").toString(), FIRST));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(
        FileResult.noFile(FileRequest.READ, repo.resolve("nope.java").toString()), reply.result());
  }

  @Test
  void a_string_that_is_not_a_path_at_all_is_refused_rather_than_crashing() {
    // The one place in the system where this can happen: the server hands a
    // Path to its own seam and never meets it, while here the path arrived
    // as a string off a wire. Measured: Path.of("a\0b") raises
    // InvalidPathException, which is unchecked.
    workspace.set(List.of(repo));

    FileReply reply = enforcer.answer(FileRequest.read("r1", "a\0b", FIRST));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(FileResult.UNNAMEABLE, rule(reply));
    assertFalse(reply.result().detail().isBlank(), "the platform's reason goes with it");
  }

  // --- the adaptor in front of a read --------------------------------------

  /**
   *
   *
   * <h2>What these establish, and what {@code ConversionsTest} establishes</h2>
   *
   * <p>{@code ConversionsTest} owns the buffer, the invalidation key and each converter. These own
   * the one thing only this class can say: that a converted file reaches a caller through the
   * <em>ordinary</em> read path, with the same window, the same span and the same refusals as a
   * text file — which is the whole of the owner's design. The agent's API does not change, so there
   * is nothing here that a text-file test does not also do.
   */
  @Test
  void a_pdf_is_read_as_the_text_it_holds_through_the_ordinary_window() throws IOException {
    Files.write(repo.resolve("report.pdf"), Pdfs.of("Hello from a PDF"));
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(FileRequest.read("r1", repo.resolve("report.pdf").toString(), FIRST));

    assertEquals(FileReply.OK, reply.outcome(), reply.sentence());
    assertEquals(List.of("Hello from a PDF"), reply.span().lines());
  }

  @Test
  void a_pdf_windows_and_counts_like_any_other_file() throws IOException {
    Files.write(
        repo.resolve("book.pdf"),
        Pdfs.of(List.of("page one", "page two", "page three", "page four")));
    workspace.set(List.of(repo));

    Span window = windowOf(repo.resolve("book.pdf").toString(), 1, 2);

    // Lines of the extraction. NOT pages, and nothing in this reply says
    // otherwise -- an offset into converted text is not a source
    // coordinate, and the way that promise is kept is by the reply carrying
    // no source coordinate at all.
    assertEquals(List.of("page two", "page three"), window.lines());
    assertEquals(1, window.offset());
    assertEquals(4, window.totalLines());
    assertTrue(window.more());
    assertEquals(Span.LINES, window.stoppedBy());
  }

  @Test
  void a_stat_of_a_pdf_counts_the_lines_a_read_of_it_would_return() throws IOException {
    Files.write(repo.resolve("book.pdf"), Pdfs.of(List.of("page one", "page two", "page three")));
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(
            new FileRequest(
                "s1",
                FileRequest.STAT,
                repo.resolve("book.pdf").toString(),
                null,
                null,
                null,
                null,
                null,
                null));

    // A stat that counted the PDF's bytes as lines while a read returned the
    // extraction's would be two answers about one file, and the caller stats
    // in order to plan the reads.
    assertEquals(FileReply.OK, reply.outcome(), reply.sentence());
    assertEquals(3, reply.span().totalLines());
    assertEquals(List.of(), reply.span().lines());
  }

  @Test
  void a_pdf_edited_under_the_buffer_is_read_as_it_is_now() throws IOException {
    Path pdf = repo.resolve("report.pdf");
    Files.write(pdf, Pdfs.of("the first draft"));
    workspace.set(List.of(repo));
    assertEquals(
        List.of("the first draft"),
        enforcer.answer(FileRequest.read("r1", pdf.toString(), FIRST)).span().lines());

    // Rewritten with no delay and no touch: on a filesystem whose modified
    // time is second-granular -- HFS+ is, and this suite must not care which
    // one it is running on -- a buffer keyed on mtime serves the first draft
    // here for the rest of the second. Keyed on the bytes, it cannot.
    Files.write(pdf, Pdfs.of("the second draft"));

    assertEquals(
        List.of("the second draft"),
        enforcer.answer(FileRequest.read("r2", pdf.toString(), FIRST)).span().lines(),
        "the buffer is a cache of a conversion, and a cache that outlives its input"
            + " is a wrong answer rather than a stale one");
  }

  @Test
  void a_pdf_that_cannot_be_converted_is_refused_and_says_it_was_the_pdf() throws IOException {
    Files.write(repo.resolve("torn.pdf"), Pdfs.broken());
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(FileRequest.read("r1", repo.resolve("torn.pdf").toString(), FIRST));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(
        FileResult.unreadable(
            FileRequest.READ,
            FileResult.REFUSED,
            FileResult.DAMAGED,
            repo.resolve("torn.pdf").toString(),
            "PDF"),
        reply.result(),
        "this client converts PDFs, so 'not UTF-8 text' would be a true"
            + " fact about the bytes and a false one about why the read failed");
  }

  @Test
  void a_format_this_client_does_not_convert_is_refused_exactly_as_before() throws IOException {
    // A PNG. Nothing converts it, and the sentence a caller gets is the one
    // this class has always given for bytes that are not text -- the adaptor
    // adds a capability and changes no existing answer.
    Files.write(
        repo.resolve("shot.png"),
        new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, (byte) 0xFF, (byte) 0xFE});
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(FileRequest.read("r1", repo.resolve("shot.png").toString(), FIRST));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(FileResult.NOT_UTF8, rule(reply));
  }

  @Test
  void a_pdf_named_as_a_text_file_is_still_converted() throws IOException {
    // By signature and not by extension, which is TextExtraction's rule on
    // the other side of the wire and has to be the same rule here: a name is
    // a claim somebody made and the bytes are what the file is.
    Files.write(repo.resolve("notes.txt"), Pdfs.of("a PDF wearing the name of a text file"));
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(FileRequest.read("r1", repo.resolve("notes.txt").toString(), FIRST));

    assertEquals(FileReply.OK, reply.outcome(), reply.sentence());
    assertEquals(List.of("a PDF wearing the name of a text file"), reply.span().lines());
  }

  @Test
  void a_grep_walk_does_not_convert_what_it_passes() throws IOException {
    Files.write(repo.resolve("report.pdf"), Pdfs.of("the needle is in the PDF"));
    Files.writeString(repo.resolve("notes.md"), "the needle is in the notes\n");
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(
            new FileRequest("g1", FileRequest.GREP, null, null, null, null, null, "needle", null));

    assertEquals(FileReply.OK, reply.outcome(), reply.sentence());
    List<String> where = new ArrayList<>();
    for (Found.Match hit : reply.found().matches()) {
      where.add(hit.path());
    }
    assertEquals(
        List.of(repo.toRealPath().resolve("notes.md").toString()),
        where,
        "a walk converts nothing: the cost of a conversion is per file, and a"
            + " workspace of documents would turn one search into hundreds of"
            + " extractions nobody asked for");
  }

  @Test
  void a_grep_at_a_named_file_converts_it() throws IOException {
    Files.write(repo.resolve("report.pdf"), Pdfs.of("the needle is in the PDF"));
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(
            new FileRequest(
                "g1",
                FileRequest.GREP,
                repo.resolve("report.pdf").toString(),
                null,
                null,
                null,
                null,
                "needle",
                null));

    assertEquals(FileReply.OK, reply.outcome(), reply.sentence());
    assertEquals(
        1,
        reply.found().matches().size(),
        "one file the caller named is a cost the caller chose, and a search that"
            + " refused the file a read of it succeeds on would be two answers"
            + " about one file");
  }

  // --- the window ----------------------------------------------------------

  /**
   * A file of {@code count} numbered lines, each ending in a newline.
   *
   * <p>Numbered rather than identical so that an off-by-one in the offset is visible in the
   * assertion rather than hidden behind lines that all look alike. {@code LocalProviderTest} builds
   * its fixtures the same way, which is what lets the two files' windowing tests be read against
   * each other.
   */
  private Path numbered(String name, int count) throws IOException {
    StringBuilder text = new StringBuilder();
    for (int line = 0; line < count; line++) {
      text.append("line ").append(line).append('\n');
    }
    return Files.writeString(repo.resolve(name), text.toString());
  }

  /**
   * A file of {@code count} lines, each {@code width} characters wide, so that {@link
   * Window#MAX_WINDOW_BYTES} is reached long before the line allowance.
   *
   * <p>ASCII on purpose: the ceiling counts UTF-8 bytes, and a fixture whose characters were not
   * one byte each would make the arithmetic under test harder to read than the assertion about it.
   */
  private Path wide(String name, int count, int width) throws IOException {
    StringBuilder text = new StringBuilder();
    for (int line = 0; line < count; line++) {
      text.append(Character.toString('a' + line % 26).repeat(width)).append('\n');
    }
    return Files.writeString(repo.resolve(name), text.toString());
  }

  private Span windowOf(String path, Integer offset, Integer limit) {
    FileReply reply =
        enforcer.answer(
            new FileRequest("w", FileRequest.READ, path, null, null, offset, limit, null, null));
    assertEquals(FileReply.OK, reply.outcome(), reply.sentence());
    return reply.span();
  }

  @Test
  void a_window_inside_the_file_carries_its_lines_and_nothing_else() throws IOException {
    numbered("notes.md", 10);
    workspace.set(List.of(repo));

    Span window = windowOf(repo.resolve("notes.md").toString(), 3, 4);

    assertEquals(List.of("line 3", "line 4", "line 5", "line 6"), window.lines());
    assertEquals(3, window.offset(), "echoed, so the reply is legible without the request");
    assertEquals(
        10,
        window.totalLines(),
        "the whole file was counted even though four lines of it crossed the wire");
    assertTrue(window.more());
    assertEquals(Span.LINES, window.stoppedBy());
  }

  @Test
  void a_window_reaching_the_last_line_says_the_file_is_finished() throws IOException {
    numbered("notes.md", 10);
    workspace.set(List.of(repo));

    Span window = windowOf(repo.resolve("notes.md").toString(), 6, 50);

    assertEquals(List.of("line 6", "line 7", "line 8", "line 9"), window.lines());
    assertFalse(window.more());
    assertEquals(
        Span.END,
        window.stoppedBy(),
        "which is what stops a model paging off the end of a file it has finished");
  }

  @Test
  void an_offset_past_the_end_is_an_empty_window_and_not_a_refusal() throws IOException {
    // The ordinary way a caller paging forward learns to stop. A refusal
    // would cost it a turn to discover what an empty answer tells it for
    // free, and the total is still the truth about the file.
    numbered("notes.md", 10);
    workspace.set(List.of(repo));

    Span window = windowOf(repo.resolve("notes.md").toString(), 400, 10);

    assertTrue(window.lines().isEmpty());
    assertEquals(10, window.totalLines(), "and the file is still ten lines long");
    assertFalse(window.more());
    assertEquals(Span.END, window.stoppedBy());
  }

  @Test
  void an_empty_file_is_an_empty_window_and_not_a_refusal() throws IOException {
    Files.writeString(repo.resolve("empty.md"), "");
    workspace.set(List.of(repo));

    Span window = windowOf(repo.resolve("empty.md").toString(), 0, 10);

    assertTrue(window.lines().isEmpty());
    assertEquals(
        0,
        window.totalLines(),
        "measured: \"\".lines() is empty, so a file with nothing in it is no lines"
            + " rather than one blank one");
    assertFalse(window.more());
    assertEquals(Span.END, window.stoppedBy());
  }

  /**
   * The case the line count is most easily wrong about.
   *
   * <p>Both spellings of the same three lines are read, because the assertion worth making is that
   * they are <b>indistinguishable</b>. A client that split on {@code \n} itself would report four
   * lines for the terminated file — a trailing empty one that is not in it — and a model told a
   * file has a line it does not have pages once into nothing.
   */
  @Test
  void a_last_line_with_no_newline_is_a_line_and_the_only_one_of_its_kind() throws IOException {
    Files.writeString(repo.resolve("bare.md"), "one\ntwo\nthree");
    Files.writeString(repo.resolve("ended.md"), "one\ntwo\nthree\n");
    workspace.set(List.of(repo));

    byte[] onDisk = Files.readAllBytes(repo.resolve("bare.md"));
    assertEquals(
        (byte) 'e',
        onDisk[onDisk.length - 1],
        "the fixture has to actually lack the newline, or this test asserts nothing");

    Span bare = windowOf(repo.resolve("bare.md").toString(), 0, 100);
    Span ended = windowOf(repo.resolve("ended.md").toString(), 0, 100);

    assertEquals(List.of("one", "two", "three"), bare.lines());
    assertEquals(3, bare.totalLines(), "the last line is a line, newline or no newline");
    assertEquals(bare.lines(), ended.lines(), "and the terminated file is the same file");
    assertEquals(bare.totalLines(), ended.totalLines());
    assertFalse(bare.more());
  }

  /**
   * A terminator is not part of the line it ends, and since the lines are the reply's only carrier,
   * a carriage return that is not in a line is not delivered.
   *
   * <p>This is a real change in what a reader is handed and it is the intended one — {@code
   * LocalProvider} judged it, and its half is the reason this half has no choice: the two
   * disagreeing about line endings would put two different files into one window with nothing to
   * say so. The alternative shows a model a trailing {@code \r} on every line of a file it did not
   * write and charges the byte ceiling for each of them. Nothing reconstructs a file from a window
   * — {@code file_write} is handed its own content — so nothing depends on a window being
   * byte-exact.
   */
  @Test
  void a_crlf_file_comes_back_as_lines_without_the_carriage_returns() throws IOException {
    Path dos =
        Files.write(
            repo.resolve("dos.txt"), "alpha\r\nbeta\r\ngamma\r\n".getBytes(StandardCharsets.UTF_8));
    workspace.set(List.of(repo));

    Span window = windowOf(dos.toString(), 0, 100);

    assertEquals(List.of("alpha", "beta", "gamma"), window.lines());
    assertEquals(3, window.totalLines(), "three lines and not six: a CRLF ends one line, not two");
    assertTrue(
        new String(Files.readAllBytes(dos), StandardCharsets.UTF_8).contains("\r"),
        "and the fixture really is a CRLF file, or this test asserts nothing");
  }

  /**
   * A read frame with neither field set — the shape a server built before windows existed sends,
   * since those keys are simply not in its frames and a lenient mapper binds the missing ones to
   * null.
   *
   * <p>Answered with the first window, which is {@code FileRequest.window()}'s decision and is not
   * re-made here. The fixture is longer than {@link Window#MAX_WINDOW_LINES} on purpose: a shorter
   * one would pass whether this meant "the first window" or "the whole file", and the whole file is
   * the request this slice exists to make impossible.
   */
  @Test
  void a_read_frame_carrying_no_window_at_all_is_answered_with_the_first_window()
      throws IOException {
    int longer = Window.MAX_WINDOW_LINES + 10;
    numbered("notes.md", longer);
    workspace.set(List.of(repo));

    Span window = windowOf(repo.resolve("notes.md").toString(), null, null);

    assertEquals(
        Window.MAX_WINDOW_LINES,
        window.lines().size(),
        "the whole file would have been the frame that killed the session");
    assertEquals("line 0", window.lines().get(0), "and it starts at the top of the file");
    assertEquals(0, window.offset());
    assertEquals(longer, window.totalLines());
    assertTrue(window.more(), "so the caller knows to ask again rather than stopping here");
    assertEquals(Span.LINES, window.stoppedBy());
  }

  /**
   * A limit above the cap is brought down to it and not refused, which is the same reading of the
   * same frame {@code Window.of} gives a server calling it in process. Refusing would make one
   * request succeed locally and fail remotely, which is the drift the shared module exists to
   * prevent.
   */
  @Test
  void a_limit_over_the_cap_is_clamped_rather_than_refused() throws IOException {
    int longer = Window.MAX_WINDOW_LINES + 10;
    numbered("notes.md", longer);
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(
            new FileRequest(
                "w",
                FileRequest.READ,
                repo.resolve("notes.md").toString(),
                null,
                null,
                0,
                50_000,
                null,
                null));

    assertEquals(
        FileReply.OK,
        reply.outcome(),
        "an optimistic limit from a caller that has not read the file is not an"
            + " error — "
            + reply.sentence());
    assertEquals(Window.MAX_WINDOW_LINES, reply.span().lines().size());
    assertTrue(
        reply.span().more(),
        "and the reply says what it got, so nothing about the clamp is silent");
  }

  /**
   * The frames the clamp deliberately does not cover, and the outcome they get.
   *
   * <p>{@code Window}'s constructor throws for both, and the protocol module has neither module's
   * refusal type to throw instead. Left untranslated the unchecked exception would reach {@code
   * answer}'s {@code RuntimeException} clause and come back {@code UNAVAILABLE}, which <b>ends the
   * run</b> — over a frame this client is perfectly well enough to answer. It is the same version
   * gap the unknown-op arm refuses rather than reports as an outage, arriving through a different
   * field of the same frame.
   */
  @Test
  void a_window_this_client_cannot_serve_is_refused_and_does_not_end_the_run() throws IOException {
    numbered("notes.md", 10);
    workspace.set(List.of(repo));

    for (List<Integer> broken : List.of(List.of(-1, 10), List.of(0, 0), List.of(0, -5))) {
      FileReply reply =
          enforcer.answer(
              new FileRequest(
                  "w",
                  FileRequest.READ,
                  repo.resolve("notes.md").toString(),
                  null,
                  null,
                  broken.get(0),
                  broken.get(1),
                  null,
                  null));

      assertEquals(
          FileReply.REFUSED,
          reply.outcome(),
          "a malformed window ended the run instead of being answered — " + broken);
      assertEquals(
          broken.get(0) < 0
              ? FileResult.unservable(FileRequest.READ, "offset", broken.get(0), null)
              : FileResult.unservable(FileRequest.READ, "limit", broken.get(1), null),
          reply.result(),
          broken.toString());
    }
  }

  /**
   * The ceiling is the limit that is actually reached on the files this slice exists for, and it is
   * the one a line count cannot see. Without this case a client that did its own subList arithmetic
   * agrees with {@link Window#cut} on every window a small fixture can express — measured: that
   * mutant survived this file until this test was written.
   *
   * <p>{@link Span#BYTES} and not {@link Span#LINES} is the whole point of the distinction: it
   * tells the caller a wider limit would not have helped, where {@code LINES} invites exactly that
   * wasted request.
   */
  @Test
  void a_window_the_byte_ceiling_stops_says_so_rather_than_naming_the_line_limit()
      throws IOException {
    Path fat = wide("fat.log", 200, 4096);
    workspace.set(List.of(repo));

    Span window = windowOf(fat.toString(), 0, 200);

    assertEquals(
        Span.BYTES,
        window.stoppedBy(),
        "a wider limit would not have helped, and LINES would invite one");
    assertTrue(window.more());
    assertTrue(
        window.lines().size() < 200,
        "the ceiling stopped it short of the lines it was asked for — " + window.lines().size());
    assertEquals(200, window.totalLines(), "and the file it did not carry is still counted whole");
  }

  @Test
  void a_stat_counts_the_lines_and_carries_none_of_them() throws IOException {
    numbered("notes.md", 137);
    workspace.set(List.of(repo));

    FileReply reply = enforcer.answer(FileRequest.stat("r1", repo.resolve("notes.md").toString()));

    assertEquals(FileReply.OK, reply.outcome(), reply.sentence());
    // The cross-check first and the literal second. The other order shadows
    // it: every mutant that moves this count moves the literal too, so the
    // assertion that a stat and a read agree would never be the one to fire.
    assertEquals(
        windowOf(repo.resolve("notes.md").toString(), 0, 10).totalLines(),
        reply.span().totalLines(),
        "the two are counted the same way, or planning a read from a stat plans"
            + " against a file that is not there");
    assertEquals(137, reply.span().totalLines());
    assertTrue(
        reply.span().lines().isEmpty(),
        "a stat moves no text; that is the whole of what it is for");
    assertTrue(reply.span().more(), "and the file it did not carry is still there to be read");
    assertEquals(
        Span.LINES,
        reply.span().stoppedBy(),
        "END exactly when nothing follows is an invariant a caller relies on, and"
            + " something follows a stat of a file with lines in it");
  }

  @Test
  void a_stat_of_an_empty_file_is_the_end_of_it() throws IOException {
    Files.writeString(repo.resolve("empty.md"), "");
    workspace.set(List.of(repo));

    Span counted =
        enforcer.answer(FileRequest.stat("r1", repo.resolve("empty.md").toString())).span();

    assertEquals(0, counted.totalLines());
    assertFalse(counted.more());
    assertEquals(
        Span.END, counted.stoppedBy(), "the other half of the invariant: nothing follows, so END");
  }

  /**
   * A stat is not a cheaper way past the guards. Every refusal a read gives is given here, or a
   * session could measure a file it may not see — and the line count of a file is not nothing,
   * since it is the difference between a one-line marker and a database dump.
   */
  @Test
  void a_stat_refuses_everything_a_read_refuses() throws IOException {
    Files.writeString(other.resolve("secret.txt"), "s");
    Files.createDirectory(repo.resolve("src"));
    Files.write(repo.resolve("logo.png"), new byte[] {(byte) 0xff, (byte) 0xd8, 0x00, 'a'});
    workspace.set(List.of(repo));

    assertEquals(FileResult.OUTSIDE, statRule(other.resolve("secret.txt")));
    assertEquals(FileResult.DIRECTORY, statRule(repo.resolve("src")));
    assertEquals(FileResult.NOT_UTF8, statRule(repo.resolve("logo.png")));
    assertEquals(FileResult.NO_FILE, statRule(repo.resolve("notyet.md")));
  }

  private String statRule(Path path) {
    FileReply reply = enforcer.answer(FileRequest.stat("r1", path.toString()));
    assertEquals(FileReply.REFUSED, reply.outcome(), path + " was not refused");
    assertEquals(FileRequest.STAT, reply.result().op());
    return rule(reply);
  }

  /** The rule a refusal names — its reason, or its kind when it has none — and no words. */
  private static String rule(FileReply reply) {
    assertNull(reply.sentence(), "the server words every refusal but a run's own");
    assertNotNull(reply.result(), "a refusal carries its facts");
    return reply.result().reason() == null ? reply.result().kind() : reply.result().reason();
  }

  /**
   * The agreement that can be made inside this module: the span a request produces is exactly what
   * {@link Window#cut} produces from the same file and the same window, so nothing in {@code
   * ClientEnforcer} is doing the arithmetic itself.
   *
   * <p>That is the property the doubled enforcement rests on. The two halves agree because they
   * both run this code, not because each is careful, and a client that recomputed a range would
   * make a file's contents depend on which machine the job ran on — two plausible windows, neither
   * of which looks like a failure. The same assertion against {@code LocalProvider} cannot be
   * written here, because {@code plowshare-client} does not depend on {@code plowshare-server}; the
   * direction that exists is the server's test source set, which already holds both halves.
   *
   * <p>Several windows and not one, over two files and not one. A class that ignored the offset, or
   * the limit, or stopped a line early, agrees with {@code cut} on some single window by accident —
   * and one whose lines are all short agrees on every window there is, because the byte ceiling
   * never fires and that is the branch a hand-rolled cut gets wrong. The wide file is here because
   * the mutant that did its own {@code subList} survived the narrow one.
   */
  @Test
  void the_span_is_what_Window_cut_makes_of_the_same_file_and_the_same_window() throws IOException {
    Path notes = numbered("notes.md", 500);
    Path fat = wide("fat.log", 200, 4096);
    workspace.set(List.of(repo));

    for (Path file : List.of(notes, fat)) {
      List<String> asLines = Files.readString(file).lines().toList();
      for (Window asked :
          List.of(
              Window.of(0, 1),
              Window.of(0, 500),
              Window.of(7, 13),
              Window.of(199, 5),
              Window.of(500, 5),
              Window.of(0, 200),
              Window.of(0, Window.MAX_WINDOW_LINES))) {
        Span cut = asked.cut(asLines);

        assertEquals(
            cut,
            windowOf(file.toString(), asked.offset(), asked.limit()),
            "the client cut "
                + asked
                + " of "
                + file.getFileName()
                + " differently from the shared arithmetic");
      }
    }
  }

  // --- searching -----------------------------------------------------------

  @Test
  void a_search_with_no_path_walks_the_workspace_and_says_where_each_line_is() throws IOException {
    Files.writeString(repo.resolve("notes.md"), "one\nthe workspace here\nthree\n");
    Files.createDirectory(repo.resolve("src"));
    Files.writeString(repo.resolve("src/A.java"), "class A {}\n// workspace again\n");
    Files.writeString(repo.resolve("quiet.txt"), "nothing of interest\n");
    workspace.set(List.of(repo));

    Found found = found(FileRequest.grep("r1", null, new Needle("workspace", false)));

    assertEquals(2, found.matches().size(), found.matches().toString());
    assertEquals(Found.END, found.stoppedBy());
    for (Found.Match match : found.matches()) {
      assertEquals(1, match.offset(), match.toString());
    }
  }

  @Test
  void no_line_from_outside_the_workspace_is_in_a_search_that_named_no_path() throws IOException {
    // THE LEASH, and an honest note about what this fixture can and cannot
    // isolate. TWO guards keep this line out on this machine: the
    // containment filter FileSearch.eachFile runs on each resolved
    // candidate, and the NOFOLLOW attribute read in `decode`, which refuses
    // a symlink as "not a regular file" before a byte of it is read.
    //
    // MEASURED, with the containment filter mutated out of the shared walk:
    // this test still passed and LocalProviderTest's excluded-file test
    // failed. So the filter's own instrument is over there, where an
    // exclusion covers an ORDINARY FILE inside the root and nothing else
    // stands behind it — the client's leash is the workspace bounds alone,
    // and a walk can only leave those through a link, which the open
    // refuses anyway.
    //
    // Kept, because what it asserts is the outcome that matters and it is
    // the outcome a change to either guard would break: a glob that leaks a
    // path leaks a name, and a search that leaks one leaks the line.
    Path theirs = Files.writeString(other.resolve("theirs.md"), "the secret is here\n");
    Files.createSymbolicLink(repo.resolve("link.md"), theirs);
    Files.writeString(repo.resolve("mine.md"), "the secret is mine\n");
    workspace.set(List.of(repo));

    Found found = found(FileRequest.grep("r1", null, new Needle("secret", false)));

    assertEquals(1, found.matches().size(), found.matches().toString());
    assertTrue(
        found.matches().get(0).path().endsWith("mine.md"), found.matches().get(0).toString());
    assertFalse(
        found.matches().get(0).line().contains("is here"),
        "the line from outside the workspace is not in the answer");
  }

  @Test
  void a_search_of_a_path_outside_the_workspace_is_refused_as_a_read_of_it_would_be()
      throws IOException {
    Files.writeString(other.resolve("theirs.md"), "wanted\n");
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(
            FileRequest.grep(
                "r1", other.resolve("theirs.md").toString(), new Needle("wanted", false)));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(
        FileResult.fenced(
            FileRequest.GREP,
            FileResult.OUTSIDE,
            other.resolve("theirs.md").toString(),
            List.of(repo.toRealPath().toString())),
        reply.result());
  }

  @Test
  void a_grep_with_no_workspace_set_never_reads_as_nothing_matched() {
    // The glob rule at the tool where breaking it is worst: an empty result
    // here tells a model the thing it is looking for is nowhere on the
    // machine, when the truth is that it was granted nowhere to look.
    FileReply reply = enforcer.answer(FileRequest.grep("r1", null, new Needle("wanted", false)));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(
        FileResult.refused(FileRequest.GREP, FileResult.NO_WORKSPACE, null), reply.result());
  }

  @Test
  void a_needle_with_nothing_in_it_is_a_refusal_and_not_every_line_of_the_tree()
      throws IOException {
    // Needle's constructor raises unchecked out of a module with neither
    // side's refusal type. Untranslated it would reach the RuntimeException
    // clause and come back as UNAVAILABLE, ending a run over a frame this
    // client is perfectly well enough to answer.
    Files.writeString(repo.resolve("notes.md"), "one\ntwo\n");
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(
            new FileRequest("r1", FileRequest.GREP, null, null, null, null, null, "   ", null));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(FileResult.unservable(FileRequest.GREP, "needle", null, "   "), reply.result());
  }

  @Test
  void a_frame_that_carries_no_case_flag_at_all_searches_case_sensitively() throws IOException {
    // What a server built before this op sends: the key is simply not there.
    // The two halves must read that one way, or one machine folds case and
    // the other does not for the same search.
    Files.writeString(repo.resolve("notes.md"), "WORKSPACE\n");
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(
            new FileRequest(
                "r1", FileRequest.GREP, null, null, null, null, null, "workspace", null));

    assertEquals(FileReply.OK, reply.outcome(), reply.sentence());
    assertEquals(List.of(), reply.found().matches());
  }

  @Test
  void a_search_that_folds_case_finds_the_line_the_sensitive_one_missed() throws IOException {
    Files.writeString(repo.resolve("notes.md"), "WORKSPACE\n");
    workspace.set(List.of(repo));

    Found found = found(FileRequest.grep("r1", null, new Needle("workspace", true)));

    assertEquals(1, found.matches().size(), found.matches().toString());
  }

  @Test
  void a_file_that_is_not_text_is_skipped_by_a_walk_and_refused_when_it_is_named()
      throws IOException {
    Path binary = repo.resolve("logo.bin");
    Files.write(binary, new byte[] {(byte) 0xff, (byte) 0xfe, 'w', 'a', 'n', 't'});
    Files.writeString(repo.resolve("notes.md"), "want, in text this time\n");
    workspace.set(List.of(repo));

    Found walked = found(FileRequest.grep("r1", null, new Needle("want", false)));
    FileReply named =
        enforcer.answer(FileRequest.grep("r2", binary.toString(), new Needle("want", false)));

    assertEquals(1, walked.matches().size(), walked.matches().toString());
    assertTrue(walked.matches().get(0).path().endsWith("notes.md"));
    assertEquals(FileReply.REFUSED, named.outcome());
    assertEquals(
        FileResult.notText(FileRequest.GREP, binary.toString(), FileResult.NOT_UTF8),
        named.result());
  }

  @Test
  void a_search_that_spends_its_allowance_says_so() throws IOException {
    StringBuilder many = new StringBuilder();
    for (int at = 0; at < Needle.MAX_MATCHES * 3; at++) {
      many.append("wanted ").append(at).append('\n');
    }
    Files.writeString(repo.resolve("many.md"), many.toString());
    workspace.set(List.of(repo));

    Found found = found(FileRequest.grep("r1", null, new Needle("wanted", false)));

    assertEquals(Needle.MAX_MATCHES, found.matches().size());
    assertTrue(found.capped());
    assertEquals(Found.MATCHES, found.stoppedBy());
  }

  /**
   * One search's answer, with the outcome checked so a refusal fails here rather than as a null
   * result three lines later.
   */
  private Found found(FileRequest request) {
    FileReply reply = enforcer.answer(request);
    assertEquals(FileReply.OK, reply.outcome(), reply.sentence());
    return reply.found();
  }

  // --- globbing ------------------------------------------------------------

  @Test
  void a_pattern_finds_files_at_the_top_of_the_tree_as_python_would() throws IOException {
    // Java's `**/` does not match a file at the top and Python's does. The
    // expansion is shared with the server for exactly this reason: two
    // readings would put two different file sets in one listing.
    Files.writeString(repo.resolve("Top.java"), "t");
    Files.createDirectory(repo.resolve("src"));
    Files.writeString(repo.resolve("src/Deep.java"), "d");
    workspace.set(List.of(repo));

    List<String> hits = enforcer.answer(FileRequest.glob("r1", "**/*.java")).paths();

    assertTrue(
        hits.stream().anyMatch(hit -> hit.endsWith("Top.java")),
        "the file at the top of the tree is in the answer — " + hits);
    assertTrue(hits.stream().anyMatch(hit -> hit.endsWith("Deep.java")), hits.toString());
  }

  @Test
  void a_search_with_no_workspace_set_never_reads_as_nothing_matched() {
    // An empty list would mean "searched, and there was nothing" — the
    // confident empty answer that cost one of Excalibur's agents 15 of its
    // 16 turns. This is also how ProviderRouter.absence asks which state a
    // provider is in.
    FileReply reply = enforcer.answer(FileRequest.glob("r1", "**/*.java"));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(
        FileResult.refused(FileRequest.GLOB, FileResult.NO_WORKSPACE, null), reply.result());
  }

  @Test
  void an_absolute_pattern_is_refused_with_the_pattern_that_would_have_worked() {
    // Measured on JDK 21: an absolute glob compiles and matches no relative
    // path, so leaving it alone is a confident empty answer rather than an
    // error.
    workspace.set(List.of(repo));

    FileReply reply = enforcer.answer(FileRequest.glob("r1", "/repo/**/*.java"));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(
        FileResult.pattern(FileResult.ABSOLUTE_PATTERN, "/repo/**/*.java", null),
        reply.result(),
        "the pattern goes with it, and the server words the fix");
  }

  @Test
  void a_pattern_that_will_not_compile_is_a_refusal_and_not_a_crash() {
    // GlobSpellings raises IllegalArgumentException because the protocol
    // module has neither side's refusal type; untranslated it would leave
    // this class as a bug report rather than as something a model can fix.
    workspace.set(List.of(repo));

    FileReply reply = enforcer.answer(FileRequest.glob("r1", "**/[unclosed.java"));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(FileResult.BAD_PATTERN, rule(reply));
    assertEquals("**/[unclosed.java", reply.result().pattern());
  }

  @Test
  void a_link_pointing_out_of_the_workspace_is_not_in_a_listing() throws IOException {
    // A listing tool that skips the containment filter is a read tool with
    // no boundary: the server would route a later read straight to the name
    // it handed back.
    Files.writeString(other.resolve("Secret.java"), "s");
    Files.createSymbolicLink(repo.resolve("Secret.java"), other.resolve("Secret.java"));
    Files.writeString(repo.resolve("Mine.java"), "m");
    workspace.set(List.of(repo));

    List<String> hits = enforcer.answer(FileRequest.glob("r1", "**/*.java")).paths();

    assertEquals(1, hits.size(), hits.toString());
    assertTrue(hits.get(0).endsWith("Mine.java"), hits.toString());
  }

  @Test
  void a_hidden_file_in_the_workspace_is_neither_read_nor_listed_nor_searched() throws IOException {
    // THE SHARPER HALF OF THE FINDING. ClientEnforcer.reachable() builds a
    // FileAccess with NO EXCLUSIONS AT ALL -- FileAccess.of(roots,
    // List.of()) -- on the machine where the CLI keeps its own copy of the
    // operator token. A rule expressed as a fifth entry in
    // ProjectStore.mandatoryExclusions would reach this process not at all,
    // which is why the rule lives in FileAccess.permits, and this is the
    // test that says so: this file constructs no store, imports no server
    // class, and has no exclusion list to inspect.
    //
    // All three tools, because the containment they share is one call and
    // the enumeration half is the one that would go missing first: a glob
    // hands back a name, and a grep hands back the line.
    Files.writeString(repo.resolve(".env"), "PLOWSHARE_TOKEN=notreal\n");
    Files.writeString(repo.resolve("A.java"), "// PLOWSHARE_TOKEN is documented here\n");
    workspace.set(List.of(repo));

    FileReply read =
        enforcer.answer(FileRequest.read("r1", repo.resolve(".env").toString(), FIRST));
    List<String> listed = enforcer.answer(FileRequest.glob("r2", "**/*")).paths();
    Found found =
        enforcer.answer(FileRequest.grep("r3", null, new Needle("PLOWSHARE_TOKEN", false))).found();

    assertEquals(FileReply.REFUSED, read.outcome(), read.sentence());
    assertEquals(
        FileResult.HIDDEN,
        rule(read),
        "hidden, and said to be: the model can see the path is under its root");
    assertTrue(listed.stream().noneMatch(hit -> hit.endsWith(".env")), listed.toString());
    assertTrue(
        listed.stream().anyMatch(hit -> hit.endsWith("A.java")),
        "and the rest of the workspace is still listed — " + listed);
    assertEquals(1, found.matches().size(), found.matches().toString());
    assertTrue(found.matches().get(0).path().endsWith("A.java"), found.matches().toString());
  }

  @Test
  void a_hidden_root_a_human_named_is_reachable_on_this_machine_too() throws IOException {
    // The escape hatch at the client, where the person naming the root is
    // the one sitting at the CLI. `client_root_project_here` takes an
    // absolute directory from a human; a dot in what they typed is consent
    // by construction, and this is the recovery for the .git and .github
    // this rule otherwise costs.
    Path github = Files.createDirectory(repo.resolve(".github"));
    Files.writeString(github.resolve("ci.yml"), "on: push");
    workspace.set(List.of(github));

    FileReply reply =
        enforcer.answer(FileRequest.read("r1", github.resolve("ci.yml").toString(), FIRST));

    assertEquals(FileReply.OK, reply.outcome(), reply.sentence());
    assertEquals(List.of("on: push"), reply.span().lines());
    assertEquals(
        List.of(github.toRealPath().toString()),
        enforcer.answer(FileRequest.roots("r2")).paths(),
        "and it is advertised, or the server routes nothing here");
  }

  @Test
  void a_directory_whose_name_matches_the_pattern_is_not_a_hit() throws IOException {
    // The sweep found this one: nothing here had a directory matching the
    // pattern, so the mutant that dropped the regular-file filter survived.
    // A directory in a listing is a refusal one turn later — the model reads
    // a name, asks for it, and is told it is not a regular file — which is
    // the turn Excalibur's scribe lesson is about.
    Files.createDirectory(repo.resolve("Generated.java"));
    Files.writeString(repo.resolve("Real.java"), "r");
    workspace.set(List.of(repo));

    List<String> hits = enforcer.answer(FileRequest.glob("r1", "**/*.java")).paths();

    assertEquals(1, hits.size(), hits.toString());
    assertTrue(hits.get(0).endsWith("Real.java"), hits.toString());
  }

  @Test
  void two_thousand_matches_are_an_ordinary_answer() throws IOException {
    // THE ACCEPTED SIDE OF MAX_MATCHES, named absolutely so it does not move
    // with the constant — the third time this slice has had to say that a
    // fixture derived from the limit it pins holds nothing. A
    // repository-wide `**/*.java` on somebody's laptop returns thousands,
    // and refusing that would be a limit pretending to be a safeguard.
    Path many = Files.createDirectory(repo.resolve("many"));
    for (int file = 0; file < 2_000; file++) {
      Files.createFile(many.resolve(file + ".java"));
    }
    workspace.set(List.of(repo));

    FileReply reply = enforcer.answer(FileRequest.glob("r1", "**/*.java"));

    assertEquals(FileReply.OK, reply.outcome());
    assertEquals(2_000, reply.paths().size());
  }

  @Test
  void more_matches_than_the_limit_is_refused_rather_than_quietly_truncated() throws IOException {
    // THE REFUSED SIDE, and it had no instrument at all until the review
    // asked for one: replacing the cap with `if (false)` left all thirty
    // tests in this file green, in a file the sweep covered. The server's
    // MAX_MATCHES has both sides pinned and MAX_FILE_BYTES immediately above
    // this one does too — an admission is only worth anything if it is
    // complete, and this was a live survivor outside the five that were
    // declared.
    //
    // The fixture is built from the constant, so it holds only that some
    // limit exists; two_thousand_matches_are_an_ordinary_answer is what pins
    // the number.
    Path many = Files.createDirectory(repo.resolve("many"));
    for (int file = 0; file <= ClientEnforcer.MAX_MATCHES; file++) {
      Files.createFile(many.resolve(file + ".java"));
    }
    workspace.set(List.of(repo));

    FileReply reply = enforcer.answer(FileRequest.glob("r1", "**/*.java"));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(
        FileResult.tooManyMatches(FileRequest.GLOB, ClientEnforcer.MAX_MATCHES), reply.result());
    assertEquals(null, reply.paths(), "refused at, never truncated to");
  }

  // --- commands ------------------------------------------------------------

  /** A command that prints and needs a shell to do it, with PATH to find one. */
  private static final List<String> PRINT_HI = List.of("sh", "-c", "printf hi");

  private void environment(String yaml) throws IOException {
    Path dir = Files.createDirectories(repo.resolve(".plowshare"));
    Files.writeString(dir.resolve("environment.yml"), yaml);
  }

  private FileRequest run(String id, Path cwd, List<String> argv, long timeoutMillis) {
    return FileRequest.run(
        id,
        cwd.toString(),
        argv,
        java.util.Map.of(),
        List.of("PATH"),
        timeoutMillis,
        1024 * 1024,
        true);
  }

  @Test
  void a_command_runs_where_this_machines_own_file_opts_in() throws IOException {
    environment("local:\n  mode: open\n  shells: true\n");
    workspace.set(List.of(repo));

    FileReply reply = enforcer.answer(run("r1", repo, PRINT_HI, 10_000));

    assertEquals(FileReply.OK, reply.outcome(), reply.sentence());
    assertEquals(0, reply.exitCode());
    assertEquals("hi", reply.stdout());
    assertEquals(false, reply.timedOut());
  }

  @Test
  void project_json_controls_only_local_command_consent_and_is_readable_by_the_harness()
      throws IOException {
    workspace.set(List.of(repo));
    Path manifest = repo.resolve("plowshare");
    Files.writeString(
        manifest,
        "{\"version\":1,\"name\":\"house\",\"commands\":{\"local\":{\"mode\":\"open\",\"shells\":true}}}");
    var allowed = enforcer.answer(run("json", repo, PRINT_HI, 10_000));
    assertEquals(FileReply.OK, allowed.outcome(), allowed.sentence());
    assertEquals("hi", allowed.stdout());
    Files.writeString(
        manifest,
        "{\"version\":1,\"name\":\"house\",\"commands\":{\"server\":{\"mode\":\"open\"}}}");
    assertEquals(
        FileReply.REFUSED, enforcer.answer(run("remote", repo, PRINT_HI, 10_000)).outcome());
    Files.createDirectories(repo.resolve(".plowshare"));
    Files.writeString(repo.resolve(".plowshare/project"), "{\"version\":1,\"name\":\"house\"}");
    var metadata =
        enforcer.answer(
            FileRequest.read("manifest", ".plowshare/project", Window.of(0, 10)).forDefinitions());
    assertEquals(FileReply.OK, metadata.outcome());
    var mutation =
        enforcer.answer(
            FileRequest.write("policy", manifest.toString(), "{\"caps\":{\"autoIncrease\":true}}"));
    assertEquals(FileReply.REFUSED, mutation.outcome());
  }

  /**
   * Input is bounded as output is (Task 2's review): a server that sends more than {@link
   * io.aeyer.plowshare.protocol.CommandRunner#MAX_STDIN_BYTES} is refused on this machine too, and
   * the command never starts.
   */
  @Test
  void a_command_whose_stdin_is_past_the_bound_is_refused() throws IOException {
    environment("local:\n  mode: open\n  shells: true\n");
    workspace.set(List.of(repo));
    String past = "x".repeat(io.aeyer.plowshare.protocol.CommandRunner.MAX_STDIN_BYTES + 1);

    FileReply reply =
        enforcer.answer(
            FileRequest.run(
                "r1",
                repo.toString(),
                List.of("sh", "-c", "cat >/dev/null; printf ran"),
                java.util.Map.of(),
                List.of("PATH"),
                10_000,
                1024,
                true,
                past));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertTrue(
        reply.sentence().contains("more than the 65536 a command may be given"), reply.sentence());
    assertEquals(null, reply.exitCode());
  }

  @Test
  void only_the_host_variables_this_machines_own_file_names_pass_through() throws IOException {
    // A server may narrow what a command sees and may not reach for a variable
    // this machine's file did not name: HOME is set on any machine running this.
    environment("local:\n  mode: open\n  shells: true\n  inherit: [PATH]\n");
    workspace.set(List.of(repo));

    FileReply reply =
        enforcer.answer(
            FileRequest.run(
                "r1",
                repo.toString(),
                List.of("sh", "-c", "printf \"${HOME-unset}\""),
                java.util.Map.of(),
                List.of("PATH", "HOME"),
                10_000,
                1024,
                true));

    assertEquals(FileReply.OK, reply.outcome(), reply.sentence());
    assertEquals("unset", reply.stdout());
  }

  @Test
  void a_command_is_refused_where_there_is_no_environment_file() {
    // THE CONSENT IS THIS MACHINE'S. The request says shells: true and a
    // generous timeout, as a server that resolved an open side would send;
    // with no file here, the default is off and nothing starts.
    workspace.set(List.of(repo));

    FileReply reply = enforcer.answer(run("r1", repo, PRINT_HI, 10_000));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertTrue(reply.sentence().contains("its local mode is off"), reply.sentence());
    assertTrue(reply.sentence().contains("default"), reply.sentence());
    assertEquals(null, reply.exitCode());
  }

  @Test
  void a_command_is_refused_where_the_local_mode_is_off() throws IOException {
    // And a server: section that would allow it speaks only for the server.
    environment("local:\n  mode: off\n  shells: true\nserver:\n  mode: open\n");
    workspace.set(List.of(repo));

    FileReply reply = enforcer.answer(run("r1", repo, PRINT_HI, 10_000));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertEquals(
        "this machine's .plowshare/environment.yml does not allow commands to run"
            + " here; its local mode is off",
        reply.sentence());
  }

  @Test
  void a_command_is_refused_where_the_environment_file_cannot_be_parsed() throws IOException {
    environment("local:\n  mode: sometimes\n");
    workspace.set(List.of(repo));

    FileReply reply = enforcer.answer(run("r1", repo, PRINT_HI, 10_000));

    assertEquals(FileReply.REFUSED, reply.outcome());
    assertTrue(reply.sentence().contains("could not be read"), reply.sentence());
    assertTrue(
        reply.sentence().contains("line 2"),
        "and it names the line at fault — " + reply.sentence());
  }

  @Test
  void a_shell_is_refused_unless_this_machine_allows_shells() throws IOException {
    // The request's own shells: true is what the server resolved, and is not
    // taken on trust.
    environment("local:\n  mode: open\n");
    workspace.set(List.of(repo));

    FileReply refused = enforcer.answer(run("r1", repo, PRINT_HI, 10_000));
    FileReply plain = enforcer.answer(run("r2", repo, List.of("printf", "hi"), 10_000));

    assertEquals(FileReply.REFUSED, refused.outcome());
    assertTrue(refused.sentence().contains("is a shell"), refused.sentence());
    assertEquals(FileReply.OK, plain.outcome(), plain.sentence());
    assertEquals("hi", plain.stdout());
  }

  @Test
  void a_shell_runs_where_this_machine_allows_shells() throws IOException {
    environment("local:\n  mode: gated\n  shells: true\n");
    workspace.set(List.of(repo));

    FileReply reply = enforcer.answer(run("r1", repo, List.of("/bin/sh", "-c", "exit 3"), 10_000));

    assertEquals(FileReply.OK, reply.outcome(), reply.sentence());
    assertEquals(3, reply.exitCode());
  }

  @Test
  void a_working_directory_outside_the_workspace_or_hidden_is_refused() throws IOException {
    environment("local:\n  mode: open\n  shells: true\n");
    Path hidden = Files.createDirectory(repo.resolve(".secrets"));
    workspace.set(List.of(repo));

    FileReply outside = enforcer.answer(run("r1", other, PRINT_HI, 10_000));
    FileReply inHidden = enforcer.answer(run("r2", hidden, PRINT_HI, 10_000));
    FileReply aFile =
        enforcer.answer(run("r3", repo.resolve(".plowshare/environment.yml"), PRINT_HI, 10_000));

    assertEquals(FileReply.REFUSED, outside.outcome());
    assertEquals(
        FileResult.OUTSIDE,
        rule(outside),
        "a run's working directory is fenced as every path is, and said as facts");
    assertEquals(FileRequest.RUN, outside.result().op());
    assertEquals(FileReply.REFUSED, inHidden.outcome());
    assertEquals(FileResult.HIDDEN, rule(inHidden));
    assertEquals(FileReply.REFUSED, aFile.outcome());
    assertEquals(null, inHidden.exitCode());
  }

  @Test
  void the_timeout_is_capped_by_this_machines_own_file() throws IOException {
    // The server asks for a minute; this machine allows one second.
    environment("local:\n  mode: open\n  timeout: 1s\n");
    workspace.set(List.of(repo));

    long started = System.nanoTime();
    FileReply reply = enforcer.answer(run("r1", repo, List.of("sleep", "30"), 60_000));
    long millis = (System.nanoTime() - started) / 1_000_000;

    assertEquals(FileReply.OK, reply.outcome(), reply.sentence());
    assertEquals(true, reply.timedOut());
    assertEquals(null, reply.exitCode());
    assertTrue(
        millis < 15_000,
        "killed at the file's second, not the request's minute — " + millis + " ms");
  }

  @Test
  void a_cancel_kills_a_running_command_promptly() throws Exception {
    environment("local:\n  mode: open\n");
    workspace.set(List.of(repo));
    java.util.concurrent.CompletableFuture<FileReply> ran =
        java.util.concurrent.CompletableFuture.supplyAsync(
            () -> enforcer.answer(run("r1", repo, List.of("sleep", "30"), 60_000)));
    // Wait until the command is really under way, so the cancel is not a
    // cancel of nothing that the run then ignores.
    long deadline = System.nanoTime() + 10_000_000_000L;
    while (!sleeping() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }

    long started = System.nanoTime();
    FileReply cancel = enforcer.answer(FileRequest.cancel("c1", "r1"));
    FileReply reply = ran.get(20, java.util.concurrent.TimeUnit.SECONDS);
    long millis = (System.nanoTime() - started) / 1_000_000;

    assertEquals(FileReply.OK, cancel.outcome(), cancel.sentence());
    assertEquals("c1", cancel.id());
    assertEquals(FileReply.OK, reply.outcome(), reply.sentence());
    assertEquals(null, reply.exitCode(), "a cancelled command has no exit code");
    assertEquals(false, reply.timedOut(), "and did not time out");
    assertTrue(millis < 10_000, "killed on the cancel, not the minute — " + millis + " ms");
  }

  private static boolean sleeping() {
    return ProcessHandle.current()
        .descendants()
        .anyMatch(
            child ->
                child.info().command().map(command -> command.endsWith("sleep")).orElse(false));
  }

  @Test
  void a_cancel_for_a_run_that_is_not_going_is_done() {
    FileReply reply = enforcer.answer(FileRequest.cancel("c1", "never-started"));

    assertEquals(FileReply.OK, reply.outcome(), reply.sentence());
    assertEquals("c1", reply.id());
  }

  // --- the harness's mark --------------------------------------------------

  @Test
  void harness_rules_resolve_under_the_workspace_without_widening_the_fence() throws IOException {
    workspace.set(List.of(repo));
    Files.writeString(repo.resolve("AGENTS.md"), "root rules");
    Path rules = Files.createDirectories(repo.resolve(".plowshare/agents/worker"));
    Files.writeString(rules.resolve("AGENT.md"), "worker rules");
    assertEquals(
        List.of("root rules"),
        enforcer
            .answer(FileRequest.read("r1", "AGENTS.md", FIRST).forDefinitions())
            .span()
            .lines());
    assertEquals(
        List.of("worker rules"),
        enforcer
            .answer(
                FileRequest.read("r2", ".plowshare/agents/worker/AGENT.md", FIRST).forDefinitions())
            .span()
            .lines());
    assertEquals(
        FileReply.REFUSED,
        enforcer
            .answer(FileRequest.read("r3", rules.resolve("AGENT.md").toString(), FIRST))
            .outcome());
    assertEquals(
        FileReply.REFUSED,
        enforcer
            .answer(
                FileRequest.write("r4", rules.resolve("AGENT.md").toString(), "changed")
                    .forDefinitions())
            .outcome());
    Path outside = Files.writeString(other.resolve("AGENTS.md"), "private");
    assertEquals(
        FileReply.REFUSED,
        enforcer
            .answer(FileRequest.read("r5", outside.toString(), FIRST).forDefinitions())
            .outcome());
    Files.createSymbolicLink(repo.resolve("AGENT.md"), outside);
    assertEquals(
        FileReply.REFUSED,
        enforcer.answer(FileRequest.read("r6", "AGENT.md", FIRST).forDefinitions()).outcome());
    assertEquals("worker rules", Files.readString(rules.resolve("AGENT.md")));
  }

  @Test
  void the_harness_environment_read_keeps_unrelated_hidden_files_fenced() throws IOException {
    // The server reads a session's .plowshare/environment.yml to resolve the
    // environment a run gets. That one file, under the mark, and nothing
    // else: the definition directories stay refused on this client (TODO
    // §19), and a model's read of the same file is refused as any hidden
    // path is.
    environment("local:\n  mode: open\n");
    Path agents = Files.createDirectories(repo.resolve(".plowshare/agents"));
    Files.writeString(agents.resolve("a.md"), "an agent");
    Files.writeString(repo.resolve(".env"), "PLOWSHARE_TOKEN=notreal\n");
    workspace.set(List.of(repo));
    String file = repo.resolve(".plowshare/environment.yml").toString();

    FileReply marked = enforcer.answer(FileRequest.read("r1", file, FIRST).forDefinitions());
    FileReply stat = enforcer.answer(FileRequest.stat("r2", file).forDefinitions());
    FileReply model = enforcer.answer(FileRequest.read("r3", file, FIRST));
    FileReply env =
        enforcer.answer(
            FileRequest.read("r4", repo.resolve(".env").toString(), FIRST).forDefinitions());
    FileReply agent =
        enforcer.answer(
            FileRequest.read("r5", agents.resolve("a.md").toString(), FIRST).forDefinitions());
    FileReply write =
        enforcer.answer(FileRequest.write("r6", file, "local:\n  mode: off\n").forDefinitions());

    assertEquals(FileReply.OK, marked.outcome(), marked.sentence());
    assertEquals(List.of("local:", "  mode: open"), marked.span().lines());
    assertEquals(FileReply.OK, stat.outcome(), stat.sentence());
    assertEquals(2, stat.span().totalLines());
    assertEquals(FileReply.REFUSED, model.outcome());
    assertEquals(FileReply.REFUSED, env.outcome());
    assertEquals(FileReply.REFUSED, agent.outcome());
    assertEquals(FileReply.REFUSED, write.outcome());
    assertEquals("local:\n  mode: open\n", Files.readString(Path.of(file)));
  }

  @Test
  void the_hooks_mark_reaches_nothing_hidden_on_this_client() throws IOException {
    // Spec 2026-09-30-local-hooks-are-served decision 2: this client keeps refusing, as it does
    // for the definitions (TODO §19), so its sessions have no local tier.
    Path hooks = Files.createDirectories(repo.resolve(".plowshare/hooks"));
    Files.writeString(hooks.resolve("10-guard.ts"), "export default {}\n");
    workspace.set(List.of(repo));

    FileReply read =
        enforcer.answer(
            FileRequest.read("r1", hooks.resolve("10-guard.ts").toString(), FIRST).forHooks());
    FileReply glob = enforcer.answer(FileRequest.glob("r2", ".plowshare/hooks/*").forHooks());

    assertEquals(FileReply.REFUSED, read.outcome());
    assertTrue(glob.paths() == null || glob.paths().isEmpty(), String.valueOf(glob.paths()));
  }

  @Test
  void the_harness_names_the_environment_file_relative_to_the_root_as_the_server_sends_it()
      throws IOException {
    // ChannelDefinitions sends ".plowshare/environment.yml" as it is. Resolved
    // against this process's working directory it would be some other file;
    // against the roots it is this session's. A relative climb out of
    // .plowshare is still the fence's.
    workspace.set(List.of(other, repo));
    FileReply absent =
        enforcer.answer(
            FileRequest.read("r0", ".plowshare/environment.yml", FIRST).forDefinitions());
    environment("local:\n  mode: gated\n");

    FileReply relative =
        enforcer.answer(
            FileRequest.read("r1", ".plowshare/environment.yml", FIRST).forDefinitions());
    FileReply climb =
        enforcer.answer(
            FileRequest.read("r2", ".plowshare/../.plowshare/agents/x.md", FIRST).forDefinitions());

    assertEquals(FileReply.REFUSED, absent.outcome(), "no root holds one yet");
    assertEquals(FileReply.OK, relative.outcome(), relative.sentence());
    assertEquals(
        List.of("local:", "  mode: gated"),
        relative.span().lines(),
        "the second root holds it, and the first does not");
    assertEquals(FileReply.REFUSED, climb.outcome());
  }

  // --- the disk that went away ---------------------------------------------

  @Test
  void a_workspace_that_was_set_and_has_been_deleted_is_an_outage_and_not_a_refusal()
      throws IOException {
    // The line LocalProvider draws, drawn here for the same reason: a
    // file_glob over a deleted tree is otherwise a confident (no matches),
    // and nothing the model does next brings the directory back.
    workspace.set(List.of(repo));
    Files.delete(repo);

    FileReply reply = enforcer.answer(FileRequest.glob("r1", "**/*.java"));

    assertEquals(FileReply.UNAVAILABLE, reply.outcome());
    assertNull(reply.sentence());
    assertEquals(
        FileResult.unavailable(FileRequest.GLOB, FileResult.ROOT_GONE, repo.toString(), null),
        reply.result());
  }

  @Test
  void a_workspace_replaced_by_a_file_is_a_different_sentence_from_one_that_is_gone()
      throws IOException {
    // Measured: Files.isDirectory is false for both, and Files.exists tells
    // them apart. Reporting a file that is sitting right there as absent
    // sends a human looking for something that is not lost.
    workspace.set(List.of(repo));
    Files.delete(repo);
    Files.writeString(repo, "a file where a directory was");

    FileReply reply = enforcer.answer(FileRequest.roots("r1"));

    assertEquals(FileReply.UNAVAILABLE, reply.outcome());
    assertEquals(FileResult.ROOT_NOT_DIRECTORY, reply.result().reason());
  }

  // --- the shape of an answer ----------------------------------------------

  @Test
  void every_answer_carries_the_id_it_was_asked_with() {
    // Correlation is the id and nothing else, because several requests are
    // outstanding on one socket at once. A client that answered without it —
    // or with the wrong one — would hand one job another job's file.
    workspace.set(List.of(repo));
    List<FileReply> answers = new ArrayList<>();
    for (String id : List.of("a", "b", "c")) {
      answers.add(enforcer.answer(FileRequest.read(id, repo.resolve(id).toString(), FIRST)));
    }

    assertEquals(List.of("a", "b", "c"), answers.stream().map(FileReply::id).toList());
  }

  @Test
  void an_operation_this_client_does_not_know_is_refused_and_the_client_is_not_blamed() {
    // The two halves ship separately, so a later server can ask for
    // something this build has never heard of. Refused rather than reported
    // as an outage: this client is perfectly well, and the fix is to match
    // the two builds.
    FileReply reply =
        enforcer.answer(
            new FileRequest("r1", "chmod", "/repo/A.java", null, null, null, null, null, null));

    assertEquals(FileReply.REFUSED, reply.outcome());
    // The op it does not know, and no list of the ones it does: a list this
    // side kept was a sentence that went stale when stat arrived, and the
    // fix — match the two builds — is the server's to say.
    assertEquals(FileResult.refused("chmod", FileResult.UNKNOWN_OP, null), reply.result());
    assertNull(reply.sentence());
  }

  @Test
  void nothing_a_request_can_contain_makes_this_class_throw() {
    // The thing on the other end is a job blocked on an answer: an exception
    // escaping here sends nothing, and the request sits until its deadline
    // and is reported as a session that went quiet — a true sentence about a
    // client that is sitting right there, and the wrong thing to read.
    workspace.set(List.of(repo));
    List<FileRequest> nasty =
        List.of(
            new FileRequest("r1", null, null, null, null, null, null, null, null),
            new FileRequest("r2", FileRequest.READ, null, null, null, null, null, null, null),
            new FileRequest("r3", FileRequest.GLOB, null, null, null, null, null, null, null),
            new FileRequest("r4", FileRequest.WRITE, null, null, null, null, null, null, null),
            new FileRequest("r5", FileRequest.ROOTS, null, null, null, null, null, null, null),
            new FileRequest("r6", FileRequest.STAT, null, null, null, null, null, null, null),
            // The window fields are the newest way a frame can be wrong, and
            // Window's constructor throws for both of these — a negative
            // offset and a limit that cannot return a line are the two the
            // clamp deliberately does not cover.
            new FileRequest(
                "r7",
                FileRequest.READ,
                repo.resolve("A.java").toString(),
                null,
                null,
                -1,
                10,
                null,
                null),
            new FileRequest(
                "r8",
                FileRequest.READ,
                repo.resolve("A.java").toString(),
                null,
                null,
                0,
                0,
                null,
                null),
            // The newest way again: a search with no literal on it, which
            // Needle's constructor throws for.
            new FileRequest("r9", FileRequest.GREP, null, null, null, null, null, null, null),
            new FileRequest("r10", FileRequest.EDIT, null, null, null, null, null, null, null),
            new FileRequest("r11", FileRequest.DELETE, null, null, null, null, null, null, null),
            new FileRequest("r12", FileRequest.MOVE, null, null, null, null, null, null, null),
            FileRequest.move("r13", repo.resolve("A.java").toString(), null),
            FileRequest.edit("r14", repo.resolve("A.java").toString(), null, null));

    for (FileRequest request : nasty) {
      FileReply reply = enforcer.answer(request);
      assertNotEquals(null, reply, request.toString());
      assertEquals(request.id(), reply.id(), request.toString());
    }
  }

  @Test
  void an_enforcer_built_without_a_workspace_says_so_at_construction() {
    assertThrowsNullPointer(() -> new ClientEnforcer(null));
  }

  private static void assertThrowsNullPointer(Runnable body) {
    try {
      body.run();
    } catch (NullPointerException expected) {
      return;
    }
    throw new AssertionError(
        "expected a NullPointerException at construction, so the"
            + " wiring bug is reported at the frame that made it rather than inside the"
            + " first file request");
  }
}
