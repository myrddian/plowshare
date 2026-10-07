package io.aeyer.plowshare.server.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.FileResult;
import io.aeyer.plowshare.protocol.Found;
import io.aeyer.plowshare.protocol.Needle;
import io.aeyer.plowshare.protocol.Replacement;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.protocol.Window;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The second implementation of the seam: a provider whose disk is on another machine.
 *
 * <h2>A fake channel, and why no socket appears in this file</h2>
 *
 * <p>Everything here is about what this class does with an answer — how a refusal becomes an
 * exception the model reads, what happens before a byte goes on the wire, and which requests are
 * sent at all. The socket itself, its two bounds and its correlation are {@code FileChannelTest}'s
 * subject and are measured there against a real port on loopback. Splitting them that way is what
 * {@code LocalProviderTest} and {@code ProviderRouterTest} already do from the two sides of {@code
 * LocalProvider}.
 *
 * <h2>What is asserted about requests and not only about answers</h2>
 *
 * <p>Three rules here are invisible in the return value and are only observable as <em>what was
 * sent</em>: an agent with no grant asks nobody, a read-only agent's write never leaves the server,
 * and roots are re-asked rather than remembered. {@link Channel} records every request so those can
 * be measured rather than argued.
 */
class RemoteProviderTest {

  private static final List<Grant> READ = List.of(new Grant(Scope.WORKSPACE, Mode.READ));
  private static final List<Grant> WRITE = List.of(new Grant(Scope.WORKSPACE, Mode.WRITE));
  private static final String SESSION = "session-7";

  /**
   * The window every read here asks for, since none of these tests is about which one — {@code
   * the_window_is_carried_in_the_frame} is.
   */
  private static final Window FIRST = Window.of(0, Window.MAX_WINDOW_LINES);

  @Test
  void the_provider_names_itself_so_two_machines_can_be_told_apart() {
    // FileProvider.name is for the ambiguity refusal, which has to say
    // BETWEEN WHAT. A provider that answered "provider" twice would produce
    // a refusal naming nothing an operator could go and change.
    assertEquals("remote", provider(new Channel(request -> FileReply.done(request.id()))).name());
    assertNotEquals("local", provider(new Channel(request -> FileReply.done(request.id()))).name());
  }

  @Test
  void the_roots_are_asked_again_every_time_and_never_remembered() {
    // FileProvider.roots() says a remote's roots may change between two
    // calls because its human moves their workspace, and that NO CALLER MAY
    // CACHE IT. The instrument is the second answer differing from the
    // first: a provider holding a snapshot passes an assertion on one call.
    List<List<String>> answers =
        new ArrayList<>(List.of(List.of("/laptop/before"), List.of("/laptop/after")));
    Channel channel = new Channel(request -> FileReply.listed(request.id(), answers.remove(0)));
    RemoteProvider provider = provider(channel);

    assertEquals(List.of(Path.of("/laptop/before")), provider.roots());
    assertEquals(
        List.of(Path.of("/laptop/after")),
        provider.roots(),
        "the second call reports where the workspace is now, not where it was");
    assertEquals(2, channel.sent.size(), "and it asked twice to find that out");
  }

  @Test
  void an_empty_root_list_is_an_ordinary_answer_and_not_a_refusal() {
    // The one method on this interface for which empty is a real answer:
    // "what can I see?" is exactly the question file_roots asks.
    assertEquals(
        List.of(),
        provider(new Channel(request -> FileReply.listed(request.id(), List.of()))).roots());
  }

  @Test
  void the_window_the_client_cut_is_the_window_this_provider_returns() {
    // Handed back untouched, and the fixture is chosen so that a provider
    // which recomputed anything would be caught: the span says it starts at
    // line 40 and that more follows, which is a fact this process has no way
    // to derive — it never sees the lines the window left out. FileProvider
    // says both implementations cut with Window.cut and neither invents a
    // range, and on this side inventing one is not merely forbidden but
    // impossible to do correctly.
    Span cut = new Span(List.of("class A {}", "// and more"), 40, 900, true, Span.LINES);
    RemoteProvider provider =
        provider(
            new Channel(
                request -> {
                  assertEquals(FileRequest.READ, request.op());
                  assertEquals("/laptop/repo/A.java", request.path());
                  return FileReply.answered(request.id(), cut);
                }));

    assertEquals(cut, provider.read(Path.of("/laptop/repo/A.java"), FIRST));
  }

  @Test
  void the_window_is_carried_in_the_frame_so_the_far_side_never_assembles_more() {
    // The whole of this slice in one assertion: the bound travels WITH the
    // request, so the client cuts before it serialises and no frame larger
    // than one window is ever built. A provider that sent the window in no
    // field at all would still pass every other test in this file, because
    // the fake channel answers whatever it is asked.
    Channel channel =
        new Channel(
            request ->
                FileReply.answered(
                    request.id(), new Span(List.of("x"), 120, 900, true, Span.LINES)));

    provider(channel).read(Path.of("/laptop/repo/A.java"), Window.of(120, 30));

    assertEquals(120, channel.sent.get(0).offset());
    assertEquals(30, channel.sent.get(0).limit());
    assertEquals(
        Window.of(120, 30),
        channel.sent.get(0).window(),
        "and the frame reads back as the window it was built from");
  }

  @Test
  void a_stat_carries_no_window_because_there_is_nothing_to_bound() {
    // The answer is a Span with no lines in it, so there is nothing for a
    // window to cut. A stat that sent one would be asking the client to page
    // a reply that has no pages.
    Span counted = new Span(List.of(), 0, 900, true, Span.LINES);
    Channel channel =
        new Channel(
            request -> {
              assertEquals(FileRequest.STAT, request.op());
              return FileReply.answered(request.id(), counted);
            });

    assertEquals(counted, provider(channel).stat(Path.of("/laptop/repo/A.java")));
    assertNull(channel.sent.get(0).offset());
    assertNull(channel.sent.get(0).limit());
  }

  @Test
  void a_read_answered_without_a_window_is_not_read_as_an_empty_file() {
    // Only reachable from the far side, and it is what a client built before
    // windows existed answers a read with: ok, and no span on the frame.
    // Read as an empty span it would hand a model a file that appears to
    // have nothing in it, which is the confident empty answer with a
    // version skew behind it.
    RemoteProvider provider =
        provider(
            new Channel(
                request -> new FileReply(request.id(), FileReply.OK, null, null, null, null)));

    WorkspaceUnavailableException broken =
        assertThrows(
            WorkspaceUnavailableException.class,
            () -> provider.read(Path.of("/laptop/repo/A.java"), FIRST));
    assertTrue(broken.getMessage().contains(FileRequest.READ), broken.getMessage());

    WorkspaceUnavailableException noStat =
        assertThrows(
            WorkspaceUnavailableException.class,
            () -> provider.stat(Path.of("/laptop/repo/A.java")));
    assertTrue(noStat.getMessage().contains(FileRequest.STAT), noStat.getMessage());
    assertFalse(
        noStat.getMessage().contains(FileRequest.READ),
        "and each names its own — " + noStat.getMessage());
  }

  @Test
  void the_path_goes_over_the_wire_as_it_was_typed_and_not_canonicalised() {
    // FileAccess.canonical is not idempotent past its hop budget, so a path
    // resolved here and again on the far side walks two different distances
    // along one link chain — and this server cannot resolve a path on
    // somebody else's disk at all, so anything it did would be about its own
    // tree. ProviderRouter makes the same argument for handing the provider
    // the original rather than its own canonical form.
    Channel channel =
        new Channel(
            request ->
                FileReply.answered(request.id(), new Span(List.of("x"), 0, 1, false, Span.END)));
    provider(channel).read(Path.of("/laptop/repo/../repo/A.java"), FIRST);

    assertEquals(
        "/laptop/repo/../repo/A.java",
        channel.sent.get(0).path(),
        "the client resolves it against the disk it is actually about");
  }

  @Test
  void a_refusal_the_client_wrote_is_the_sentence_the_model_reads() {
    // Nothing here re-words it. The client is the only party that knows
    // which of its states holds, and FileTools passes a refusal through for
    // the same reason.
    RemoteProvider provider =
        provider(
            new Channel(
                request ->
                    FileReply.refused(request.id(), "the workspace moved to /laptop/other")));

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> provider.read(Path.of("/laptop/repo/A.java"), FIRST));
    assertEquals("the workspace moved to /laptop/other", refused.getMessage());
  }

  @Test
  void a_session_that_could_not_be_asked_ends_the_run_carrying_its_own_reason() {
    // Passed through untouched rather than re-wrapped: the reason travels
    // with the ending, and a sentence invented here would leave an operator
    // reading about a provider when the thing that broke is a session.
    RemoteProvider provider =
        provider(
            new Channel(
                request -> {
                  throw new WorkspaceUnavailableException(
                      "the session closed while this was waiting");
                }));

    WorkspaceUnavailableException gone =
        assertThrows(
            WorkspaceUnavailableException.class,
            () -> provider.read(Path.of("/laptop/repo/A.java"), FIRST));
    assertEquals("the session closed while this was waiting", gone.getMessage());
  }

  @Test
  void a_read_only_agents_write_never_leaves_the_server() {
    // THE ORDER IS DIFFERENT FROM LocalProvider'S, AND IT HAS TO BE.
    // LocalProvider checks containment first, deliberately, so that a bad
    // path is reported as a bad path rather than as a scopes problem. Over a
    // wire the containment check is on the other machine, so asking it first
    // would perform the write. The instrument is that nothing was sent.
    Channel channel = new Channel(request -> FileReply.done(request.id()));
    RemoteProvider provider = new RemoteProvider(channel, SESSION, READ);

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> provider.write(Path.of("/laptop/repo/A.java"), "x"));

    assertTrue(refused.getMessage().contains("read-only"), refused.getMessage());
    assertTrue(
        channel.sent.isEmpty(),
        "a write that reached the client would have happened — this is the whole"
            + " of what the check before the send is for");
  }

  @Test
  void an_agent_that_may_write_has_its_write_carried_out() {
    Channel channel = new Channel(request -> FileReply.done(request.id()));
    new RemoteProvider(channel, SESSION, WRITE).write(Path.of("/laptop/repo/A.java"), "hi\n");

    assertEquals(FileRequest.WRITE, channel.sent.get(0).op());
    assertEquals(
        "hi\n", channel.sent.get(0).content(), "verbatim: a trailing newline is part of a file");
  }

  @Test
  void a_read_only_agents_edit_delete_move_and_create_never_leave_the_server() {
    Channel channel = new Channel(request -> FileReply.done(request.id()));
    RemoteProvider provider = new RemoteProvider(channel, SESSION, READ);
    Path file = Path.of("/laptop/repo/A.java");

    for (Runnable change :
        List.<Runnable>of(
            () -> provider.create(file, "x"),
            () -> provider.edit(file, "a", "b"),
            () -> provider.delete(file),
            () -> provider.move(file, Path.of("/laptop/repo/B.java")))) {
      WorkspaceRefusedException refused =
          assertThrows(WorkspaceRefusedException.class, change::run);
      assertTrue(refused.getMessage().contains("read-only"), refused.getMessage());
    }
    assertTrue(channel.sent.isEmpty(), "each of these is a change, for the write's reason");
  }

  @Test
  void an_edit_a_delete_a_move_and_a_create_go_out_as_their_own_frames() {
    Channel channel = new Channel(request -> FileReply.done(request.id()));
    RemoteProvider provider = new RemoteProvider(channel, SESSION, WRITE);
    Path file = Path.of("/laptop/repo/A.java");

    provider.create(file, "new\n");
    provider.edit(file, "old\r\n", "");
    provider.delete(file);
    provider.move(file, Path.of("/laptop/repo/B.java"));

    FileRequest create = channel.sent.get(0);
    assertEquals(FileRequest.WRITE, create.op());
    assertTrue(create.creating());
    FileRequest edit = channel.sent.get(1);
    assertEquals(FileRequest.EDIT, edit.op());
    assertEquals("old\r\n", edit.replacing());
    assertEquals("", edit.content());
    assertEquals(FileRequest.DELETE, channel.sent.get(2).op());
    FileRequest move = channel.sent.get(3);
    assertEquals(FileRequest.MOVE, move.op());
    assertEquals("/laptop/repo/A.java", move.path());
    assertEquals("/laptop/repo/B.java", move.to());
  }

  @Test
  void a_change_hands_back_the_facts_the_client_sent_and_says_nothing_itself() {
    Path file = Path.of("/laptop/repo/A.java");
    FileResult facts = Replacement.edit("b\n", "b", "B").result(file.toString());
    RemoteProvider provider =
        new RemoteProvider(
            new Channel(request -> FileReply.changed(request.id(), facts)), SESSION, WRITE);

    Changed edited = provider.edit(file, "b", "B");

    assertEquals(facts, edited.facts());
    assertNull(edited.oldSentence());
    assertEquals(0, provider.oldClientReplies());
  }

  /**
   * A client built before facts answers a change in its own words, and a person mid-upgrade keeps
   * working: the words pass through as they always did, and the session is recorded as an old
   * client.
   */
  @Test
  void an_old_client_s_words_are_passed_through_and_it_is_recorded_as_old() {
    String view =
        "The new text is on line 0 now, shown with the 3 lines either side:\n"
            + "[The whole file, line 0 of 1, counting from 0 as offset does.]\nB";
    Path file = Path.of("/laptop/repo/A.java");
    RemoteProvider viewing =
        new RemoteProvider(
            new Channel(
                request -> new FileReply(request.id(), FileReply.OK, view, null, null, null)),
            SESSION,
            WRITE);
    RemoteProvider older =
        new RemoteProvider(new Channel(request -> FileReply.done(request.id())), SESSION, WRITE);
    RemoteProvider refusing =
        new RemoteProvider(
            new Channel(
                request ->
                    FileReply.refused(
                        request.id(), "there is no file at " + file + " on this machine")),
            SESSION,
            WRITE);

    Changed shown = viewing.edit(file, "b", "B");
    Changed nothing = older.edit(file, "b", "B");
    Changed written = older.write(file, "x\n");
    WorkspaceRefusedException refused =
        assertThrows(WorkspaceRefusedException.class, () -> refusing.delete(file));

    assertNull(shown.facts());
    assertEquals(view, shown.oldSentence(), "an old client's view, as it sent it");
    assertFalse(
        shown.showedWholeFile(),
        "and never read for a header: only facts say an edit showed the whole file");
    assertNull(
        nothing.oldSentence(),
        "a client built before the view answers done, and nothing is made up");
    assertNull(written.facts());
    assertEquals("there is no file at " + file + " on this machine", refused.getMessage());
    assertFalse(refused instanceof FileRefusedException);
    assertEquals(1, viewing.oldClientReplies());
    assertEquals(2, older.oldClientReplies());
    assertEquals(1, refusing.oldClientReplies());
  }

  @Test
  void a_refusal_sent_as_facts_is_worded_here_as_a_file_on_the_client_s_machine() {
    Path file = Path.of("/laptop/repo/A.java");
    FileResult facts = FileResult.noFile(FileRequest.DELETE, file.toString());
    RemoteProvider provider =
        new RemoteProvider(
            new Channel(request -> FileReply.changed(request.id(), facts)), SESSION, WRITE);

    FileRefusedException refused =
        assertThrows(FileRefusedException.class, () -> provider.delete(file));

    assertEquals(facts, refused.facts());
    assertEquals(
        "there is no file at " + file + " on this machine, so nothing was deleted",
        refused.getMessage());
    assertEquals(0, provider.oldClientReplies());
  }

  @Test
  void facts_of_a_refusal_on_an_ok_reply_are_still_a_refusal() {
    Path file = Path.of("/laptop/repo/A.java");
    FileResult facts = FileResult.manyMatches(file.toString(), 2);
    RemoteProvider provider =
        new RemoteProvider(
            new Channel(
                request ->
                    new FileReply(
                        request.id(),
                        FileReply.OK,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        facts)),
            SESSION,
            WRITE);

    FileRefusedException refused =
        assertThrows(FileRefusedException.class, () -> provider.edit(file, "a", "b"));

    assertTrue(
        refused.getMessage().startsWith("the text to replace occurs 2 times"),
        refused.getMessage());
  }

  /**
   * A read's refusal from an old client passes through as a change's does, and is recorded; a
   * current client's arrives as facts and is worded here.
   */
  @Test
  void a_read_refused_in_words_is_an_old_client_and_in_facts_is_worded_here() {
    Path file = Path.of("/laptop/repo/A.java");
    RemoteProvider old =
        provider(
            new Channel(
                request ->
                    FileReply.refused(
                        request.id(), "there is no file at " + file + " on this machine")));
    RemoteProvider current =
        provider(
            new Channel(
                request ->
                    FileReply.refused(
                        request.id(), FileResult.noFile(FileRequest.READ, file.toString()))));

    WorkspaceRefusedException fromOld =
        assertThrows(WorkspaceRefusedException.class, () -> old.read(file, FIRST));
    FileRefusedException fromCurrent =
        assertThrows(FileRefusedException.class, () -> current.read(file, FIRST));

    assertEquals("there is no file at " + file + " on this machine", fromOld.getMessage());
    assertEquals(1, old.oldClientReplies());
    assertEquals(
        fromOld.getMessage(),
        fromCurrent.getMessage(),
        "the words a model reads did not change, only who wrote them");
    assertEquals(0, current.oldClientReplies());
  }

  /** An outage in facts is worded here, and ends the run as one in words does. */
  @Test
  void an_outage_sent_as_facts_is_worded_here_and_still_ends_the_run() {
    RemoteProvider provider =
        provider(
            new Channel(
                request ->
                    FileReply.unavailable(
                        request.id(),
                        FileResult.unavailable(
                            FileRequest.GLOB, FileResult.ROOT_GONE, "/laptop/repo", null))));

    WorkspaceUnavailableException gone =
        assertThrows(WorkspaceUnavailableException.class, () -> provider.glob("**/*.java"));

    assertEquals(
        "the workspace /laptop/repo this session was set to is no longer there", gone.getMessage());
    assertEquals(0, provider.oldClientReplies());
  }

  /**
   * A picture the client named comes back as its id and no lines; the line a reader is handed is
   * worded here and then cut, counted and searched as a file's would be.
   */
  @Test
  void a_picture_named_on_the_client_is_worded_cut_counted_and_searched_here() {
    Path logo = Path.of("/laptop/repo/logo.png");
    RemoteProvider provider =
        provider(
            new Channel(
                request ->
                    FileReply.named(
                        request.id(),
                        FileResult.named(request.op(), logo.toString(), "png", "img_7"))));
    String line =
        FileWords.named(FileResult.named(FileRequest.READ, logo.toString(), "png", "img_7"), true);

    Span read = provider.read(logo, FIRST);
    Span stat = provider.stat(logo);
    Found found = provider.grep(new Needle("img_7", false), logo);

    assertEquals(List.of(line), read.lines());
    assertEquals(1, read.totalLines());
    assertEquals(1, stat.totalLines(), "what a read of it would carry");
    assertEquals(List.of(), stat.lines());
    assertEquals(1, found.matches().size(), "the id is searchable, as it always was");
    assertTrue(line.contains("It was uploaded to the server"), line);
  }

  /** A change reported as made, but as another kind of change, is not a change made. */
  @Test
  void a_change_made_as_another_kind_is_not_reported_as_done() {
    Path file = Path.of("/laptop/repo/A.java");
    RemoteProvider provider =
        new RemoteProvider(
            new Channel(
                request ->
                    FileReply.changed(request.id(), FileResult.written(file.toString(), 1, 1))),
            SESSION,
            WRITE);

    WorkspaceUnavailableException broken =
        assertThrows(WorkspaceUnavailableException.class, () -> provider.delete(file));

    assertTrue(
        broken.getMessage().contains("a change of another kind (written)"), broken.getMessage());
  }

  @Test
  void a_plain_write_is_not_create_only() {
    Channel channel = new Channel(request -> FileReply.done(request.id()));
    new RemoteProvider(channel, SESSION, WRITE).write(Path.of("/laptop/repo/A.java"), "x");

    assertFalse(channel.sent.get(0).creating());
  }

  @Test
  void a_glob_hands_back_what_the_client_matched() {
    RemoteProvider provider =
        provider(
            new Channel(
                request -> {
                  assertEquals("**/*.java", request.pattern());
                  return FileReply.listed(
                      request.id(), List.of("/laptop/repo/A.java", "/laptop/B.java"));
                }));

    assertEquals(
        List.of(Path.of("/laptop/repo/A.java"), Path.of("/laptop/B.java")),
        provider.glob("**/*.java"));
  }

  @Test
  void every_request_carries_an_id_no_other_outstanding_request_has() {
    // Correlation is the id and nothing else, because several jobs are
    // blocked on one socket and two answers can cross. A provider that sent
    // a constant id would hand one job another job's file.
    Channel channel = new Channel(request -> FileReply.listed(request.id(), List.of()));
    RemoteProvider provider = provider(channel);
    for (int i = 0; i < 50; i++) {
      provider.roots();
    }

    assertEquals(
        50,
        new HashSet<>(channel.sent.stream().map(FileRequest::id).toList()).size(),
        "fifty requests, fifty ids");
  }

  @Test
  void an_answer_to_a_different_question_is_never_read_as_this_ones() {
    // The channel correlates and this checks it anyway, because the failure
    // this catches is the one the whole slice is about: reading the wrong
    // file and never knowing. It is the second, independent check applied to
    // the seam that would otherwise be the only place it happens.
    RemoteProvider provider =
        provider(
            new Channel(
                request ->
                    FileReply.answered(
                        "some-other-request",
                        new Span(List.of("the wrong file"), 0, 1, false, Span.END))));

    WorkspaceUnavailableException confused =
        assertThrows(
            WorkspaceUnavailableException.class,
            () -> provider.read(Path.of("/laptop/repo/A.java"), FIRST));
    assertTrue(confused.getMessage().contains("answered"), confused.getMessage());
    assertFalse(
        confused.getMessage().contains("the wrong file"),
        "and the answer itself is not repeated into a message, since the one thing"
            + " known about it is that it belongs to somebody else");
  }

  @Test
  void a_missing_list_says_which_question_it_was_missing_from() {
    // The assertion that was not there: the type was checked and the
    // sentence was not, so `brokenAnswer("this", ...)` shipped and read
    // "when asked to this". An operator holding a run's ending needs to know
    // whether a client failed to list its roots or failed to run a search —
    // those are different halves of a client.
    RemoteProvider provider =
        provider(
            new Channel(
                request -> new FileReply(request.id(), FileReply.OK, null, null, null, null)));

    WorkspaceUnavailableException noRoots =
        assertThrows(WorkspaceUnavailableException.class, () -> provider.roots());
    WorkspaceUnavailableException noHits =
        assertThrows(WorkspaceUnavailableException.class, () -> provider.glob("**/*.java"));

    assertTrue(noRoots.getMessage().contains(FileRequest.ROOTS), noRoots.getMessage());
    assertTrue(noHits.getMessage().contains(FileRequest.GLOB), noHits.getMessage());
    assertFalse(
        noRoots.getMessage().contains(FileRequest.GLOB),
        "and each names only its own — " + noRoots.getMessage());
  }

  @Test
  void an_ok_answer_with_nothing_in_it_is_not_read_as_an_empty_search() {
    // FileProvider reserves an empty result for "searched, and there was
    // nothing". A client that answers ok and sends no list has told us
    // nothing, and rendering that as (no matches) is the confident empty
    // answer this project exists to avoid — reachable only from the far side
    // of a wire, which is why LocalProvider has no such branch.
    RemoteProvider provider =
        provider(
            new Channel(
                request -> new FileReply(request.id(), FileReply.OK, null, null, null, null)));

    assertThrows(WorkspaceUnavailableException.class, () -> provider.glob("**/*.java"));
    assertThrows(WorkspaceUnavailableException.class, () -> provider.roots());
    assertThrows(
        WorkspaceUnavailableException.class,
        () -> provider.read(Path.of("/laptop/repo/A.java"), FIRST));
  }

  @Test
  void a_refusal_with_no_sentence_in_it_still_says_something() {
    // Also only reachable from the far side: `ok` false and `refusal` null.
    // AgentTool forbids a blank tool result, and a WorkspaceRefusedException
    // with a null message would become exactly that.
    RemoteProvider provider =
        provider(
            new Channel(
                request -> new FileReply(request.id(), FileReply.REFUSED, null, null, null, null)));

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> provider.read(Path.of("/laptop/repo/A.java"), FIRST));
    assertFalse(
        refused.getMessage() == null || refused.getMessage().isBlank(),
        "a blank tool result reads to a model as a tool that does not work");
  }

  @Test
  void a_provider_built_without_a_channel_or_a_session_says_so_at_construction() {
    // At the frame that made the wiring mistake rather than inside a job's
    // first file call, where the report is an UNAVAILABLE outcome naming
    // nothing.
    Channel channel = new Channel(request -> FileReply.done(request.id()));
    assertThrows(NullPointerException.class, () -> new RemoteProvider(null, SESSION, READ));
    assertThrows(NullPointerException.class, () -> new RemoteProvider(channel, null, READ));
    assertThrows(NullPointerException.class, () -> new RemoteProvider(channel, SESSION, null));
  }

  @Test
  void a_workspace_that_vanished_under_the_client_ends_the_run_rather_than_refusing() {
    // The client draws LocalProvider's line in the same place. A workspace
    // nobody ever set is somebody's to set and is a refusal; one deleted
    // under a running job makes every later answer meaningless, and a
    // file_glob over it would otherwise be a confident (no matches).
    RemoteProvider provider =
        provider(
            new Channel(
                request ->
                    FileReply.unavailable(
                        request.id(), "the workspace /laptop/repo is no longer" + " there")));

    WorkspaceUnavailableException gone =
        assertThrows(WorkspaceUnavailableException.class, () -> provider.glob("**/*.java"));
    assertEquals("the workspace /laptop/repo is no longer there", gone.getMessage());
  }

  @Test
  void an_outcome_this_build_has_never_heard_of_is_an_outage_and_not_a_success() {
    // The two halves ship separately, so a later client can send a word this
    // one does not know. THE DIRECTION IS THE WHOLE TEST: read as success it
    // is a wrong answer handed to a model, and read as an outage it is a run
    // that stops and says so.
    RemoteProvider provider =
        provider(
            new Channel(
                request ->
                    new FileReply(
                        request.id(),
                        "partially-ok",
                        "half of it worked",
                        List.of("/laptop/repo/A.java"),
                        new Span(List.of("some text"), 0, 1, false, Span.END),
                        null)));

    assertThrows(
        WorkspaceUnavailableException.class,
        () -> provider.read(Path.of("/laptop/repo/A.java"), FIRST));
    assertThrows(WorkspaceUnavailableException.class, () -> provider.glob("**/*.java"));
    assertThrows(WorkspaceUnavailableException.class, () -> provider.roots());
  }

  @Test
  void an_outage_with_no_sentence_in_it_still_names_the_session() {
    // An operator reading a job's ending needs to know which session it was
    // about; "could not answer" with nothing attached names nobody.
    RemoteProvider provider =
        provider(
            new Channel(
                request ->
                    new FileReply(request.id(), FileReply.UNAVAILABLE, null, null, null, null)));

    WorkspaceUnavailableException gone =
        assertThrows(
            WorkspaceUnavailableException.class,
            () -> provider.read(Path.of("/laptop/repo/A.java"), FIRST));
    assertTrue(gone.getMessage().contains(SESSION), gone.getMessage());
  }

  @Test
  void an_agent_with_no_grant_asks_nobody_whatever_it_is_asked_for() {
    // Enumerated rather than argued, because a sweep that probed `glob`
    // alone would have said the generalisation held — the fault this slice
    // has recorded twice, most recently a commit message claiming a
    // null-guard pair was proven for every file tool when one had been
    // measured. This test was that sweep for one operation short of the
    // interface: `stat` arrived without being added here, and the mutant
    // that deleted its guard survived. A stat is not a cheaper way past
    // them, which FileProvider.stat says in as many words — an agent that
    // could count the lines of a file it may not open would learn the
    // difference between a one-line marker and a database dump.
    Channel channel = new Channel(request -> FileReply.listed(request.id(), List.of()));
    RemoteProvider provider = new RemoteProvider(channel, SESSION, List.of());

    assertEquals(List.of(), provider.roots());
    assertThrows(
        WorkspaceRefusedException.class, () -> provider.read(Path.of("/laptop/A.java"), FIRST));
    assertThrows(WorkspaceRefusedException.class, () -> provider.stat(Path.of("/laptop/A.java")));
    assertThrows(WorkspaceRefusedException.class, () -> provider.glob("**/*.java"));
    assertThrows(
        WorkspaceRefusedException.class, () -> provider.write(Path.of("/laptop/A.java"), "x"));

    assertTrue(
        channel.sent.isEmpty(),
        "not one of them put a question on somebody's laptop to find out that this"
            + " agent's own definition granted it nothing");

    // The sentence, folded in from a near-duplicate this replaced: an earlier
    // test asserted the message and the empty roots for `glob` alone, and
    // everything it held is here.
    WorkspaceRefusedException refused =
        assertThrows(WorkspaceRefusedException.class, () -> provider.glob("**/*.java"));
    assertEquals(Grant.noneDeclared(), refused.getMessage());
    assertTrue(
        refused.getMessage().contains("scopes:"),
        "and it names the key an operator has to type — " + refused.getMessage());
  }

  @Test
  void an_agent_with_no_grant_is_refused_for_its_definition_and_not_for_being_read_only() {
    // Renamed: it was called ..._before_the_write_and_not_only_before_the_send
    // and asserted no ordering at all, which is a name claiming more than the
    // body can see. The ORDER is
    // a_read_only_agents_write_never_leaves_the_server's, which measures that
    // nothing was sent. THIS one is about WHICH sentence an agent holding no
    // grant gets: its definition, rather than the read-only one, which would
    // be a message about a `scopes:` line it does not have.
    Channel channel = new Channel(request -> FileReply.done(request.id()));

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () ->
                new RemoteProvider(channel, SESSION, List.of())
                    .write(Path.of("/laptop/A.java"), "x"));

    assertEquals(Grant.noneDeclared(), refused.getMessage());
    assertFalse(refused.getMessage().contains("read-only"), refused.getMessage());
  }

  @Test
  void a_list_holding_something_that_is_not_a_path_fails_in_one_of_the_two_allowed_ways() {
    // A FOURTH FAR-SIDE SHAPE, and the one that arrives as raw strings across
    // a process boundary. Measured: Path.of("a\0b") raises
    // InvalidPathException and a null element raises NullPointerException,
    // and NEITHER is one of the two kinds FileProvider says this seam fails
    // in — so both would reach JobRuntime as an unclassified RuntimeException
    // and be reported as "the tool failed; you may try something else" about
    // a client that will send the same list every time.
    //
    // NodeFiles has carried the mirror-image guard on the other end of
    // this same wire since it was written; this end had the list and not the
    // guard.
    // The record directly, not FileReply.listed: that factory copies with
    // List.copyOf, which rejects a null element itself — so the fixture could
    // not even be built through the door a well-behaved client uses. A frame
    // off a wire is bound by Jackson and meets no such copy.
    RemoteProvider nul =
        provider(
            new Channel(
                request ->
                    new FileReply(
                        request.id(), FileReply.OK, null, Arrays.asList("a\0b"), null, null)));
    RemoteProvider missing =
        provider(
            new Channel(
                request ->
                    new FileReply(
                        request.id(),
                        FileReply.OK,
                        null,
                        Arrays.asList((String) null),
                        null,
                        null)));

    assertThrows(WorkspaceUnavailableException.class, () -> nul.glob("**/*.java"));
    assertThrows(WorkspaceUnavailableException.class, () -> nul.roots());
    assertThrows(WorkspaceUnavailableException.class, () -> missing.glob("**/*.java"));
    assertThrows(WorkspaceUnavailableException.class, () -> missing.roots());
  }

  @Test
  void a_path_this_server_cannot_name_is_quoted_back_so_somebody_can_fix_the_client() {
    RemoteProvider provider =
        provider(
            new Channel(
                request ->
                    new FileReply(
                        request.id(),
                        FileReply.OK,
                        null,
                        Arrays.asList("/laptop/a\0b"),
                        null,
                        null)));

    WorkspaceUnavailableException broken =
        assertThrows(WorkspaceUnavailableException.class, () -> provider.glob("**/*.java"));
    assertTrue(broken.getMessage().contains(FileRequest.GLOB), broken.getMessage());
    assertTrue(
        broken.getMessage().contains("not a path this server can even name"), broken.getMessage());
  }

  @Test
  void a_search_goes_out_as_a_frame_and_comes_back_untouched() {
    // Nothing is matched, filtered or trimmed on this side. The needle
    // travels, the client matches with the same Needle the server would have
    // used, and a second pass here would be a second answer to a question
    // already answered on the far side.
    Found matched =
        Found.of(
            List.of(
                new Found.Match("/laptop/repo/A.java", 41, "class A implements B {", false),
                new Found.Match("/laptop/repo/B.java", 7, "interface B {", false)),
            true);
    Channel channel = new Channel(request -> FileReply.found(request.id(), matched));

    Found back = provider(channel).grep(new Needle("B", false), null);

    assertSame(matched, back, "the far side's answer is the answer");
    assertEquals(FileRequest.GREP, channel.sent.get(0).op());
    assertEquals("B", channel.sent.get(0).needle());
    assertEquals(false, channel.sent.get(0).ignoreCase());
    assertNull(
        channel.sent.get(0).path(),
        "a search with no path travels as one: the roots it would be expanded"
            + " against are the client's, at the instant the client looks");
    assertNull(channel.sent.get(0).pattern(), "and the glob field is not where a needle goes");
  }

  @Test
  void a_search_under_a_named_path_carries_the_path_as_the_caller_wrote_it() {
    Channel channel =
        new Channel(request -> FileReply.found(request.id(), Found.of(List.of(), false)));

    provider(channel).grep(new Needle("wanted", true), Path.of("/laptop/repo/src"));

    assertEquals("/laptop/repo/src", channel.sent.get(0).path());
    assertEquals(true, channel.sent.get(0).ignoreCase());
  }

  @Test
  void an_ok_answer_with_no_matches_field_is_not_read_as_a_search_that_found_nothing() {
    // What a client built before this op answers with, and the reading that
    // must not happen: a model told confidently that the thing it is looking
    // for is nowhere in its workspace stops looking for it.
    RemoteProvider provider =
        provider(
            new Channel(
                request -> new FileReply(request.id(), FileReply.OK, null, null, null, null)));

    WorkspaceUnavailableException broken =
        assertThrows(
            WorkspaceUnavailableException.class,
            () -> provider.grep(new Needle("wanted", false), null));

    assertTrue(broken.getMessage().contains(FileRequest.GREP), broken.getMessage());
  }

  @Test
  void a_search_by_an_agent_with_no_grant_asks_nobody() {
    // Before the wire, like every other operation: an agent whose definition
    // declared no grant reaches no file, and the sentence sends a reader to
    // that definition rather than to a workspace.
    Channel channel =
        new Channel(request -> FileReply.found(request.id(), Found.of(List.of(), false)));
    RemoteProvider provider = new RemoteProvider(channel, SESSION, List.of());

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> provider.grep(new Needle("wanted", false), null));

    assertEquals(Grant.noneDeclared(), refused.getMessage());
    assertEquals(List.of(), channel.sent, "and nothing went on the wire");
  }

  private static RemoteProvider provider(Channel channel) {
    return new RemoteProvider(channel, SESSION, WRITE);
  }

  /** A channel that records what it was asked and answers however the test says. */
  // --- run ------------------------------------------------------------------------

  private static final io.aeyer.plowshare.protocol.EnvironmentFile.Side OPEN =
      io.aeyer.plowshare.protocol.EnvironmentFile.Side.DEFAULT.with(
          io.aeyer.plowshare.protocol.EnvironmentFile.parse(
                  "local:\n  mode: open\n  inherit: [PATH]\n  env:\n    CI: \"1\"\n")
              .local());

  /** A channel that records the deadline a request was sent with. */
  private static final class Timed implements SessionChannel {
    final List<FileRequest> sent = java.util.Collections.synchronizedList(new ArrayList<>());
    final List<java.time.Duration> deadlines =
        java.util.Collections.synchronizedList(new ArrayList<>());
    private final Function<FileRequest, FileReply> answer;

    Timed(Function<FileRequest, FileReply> answer) {
      this.answer = answer;
    }

    @Override
    public FileReply ask(String session, FileRequest request) {
      sent.add(request);
      return answer.apply(request);
    }

    @Override
    public FileReply ask(String session, FileRequest request, java.time.Duration deadline) {
      deadlines.add(deadline);
      return ask(session, request);
    }
  }

  @Test
  void a_run_goes_out_with_its_environment_and_waits_as_long_as_the_command_may_take() {
    Timed channel =
        new Timed(
            request ->
                FileReply.ran(
                    request.id(),
                    new io.aeyer.plowshare.protocol.CommandRunner.Outcome(
                        2, false, false, "out", 0, "err", 0, 9)));
    RemoteProvider provider = new RemoteProvider(channel, SESSION, WRITE);

    io.aeyer.plowshare.protocol.CommandRunner.Outcome outcome =
        provider.run(
            Path.of("/laptop/repo"),
            List.of("./gradlew", "test"),
            OPEN,
            java.time.Duration.ofMinutes(4),
            () -> false);

    FileRequest run = channel.sent.get(0);
    assertEquals(FileRequest.RUN, run.op());
    assertEquals("/laptop/repo", run.path());
    assertEquals(List.of("./gradlew", "test"), run.argv());
    assertEquals(java.util.Map.of("CI", "1"), run.env());
    assertEquals(List.of("PATH"), run.inherit());
    assertEquals(240_000L, run.timeoutMillis());
    assertEquals(
        java.time.Duration.ofMinutes(4).plus(RemoteProvider.RUN_MARGIN),
        channel.deadlines.get(0),
        "a four-minute build is not a client that went quiet");
    assertEquals(2, outcome.exitCode());
    assertEquals("err", outcome.stderr());
  }

  @Test
  void an_isolated_run_uses_a_distinct_operation_and_never_retries_an_old_client() {
    Timed channel = new Timed(request -> FileReply.refused(request.id(), "unknown file operation"));
    var provider = new RemoteProvider(channel, SESSION, WRITE);
    var isolated =
        OPEN.with(
            io.aeyer.plowshare.protocol.EnvironmentFile.parse("local:\n  isolation: bubblewrap\n")
                .local());
    assertThrows(
        WorkspaceRefusedException.class,
        () ->
            provider.run(
                Path.of("/laptop/repo"),
                List.of("test-program"),
                isolated,
                java.time.Duration.ofSeconds(1),
                () -> false));
    assertEquals(1, channel.sent.size());
    assertEquals(FileRequest.RUN_ISOLATED, channel.sent.getFirst().op());
  }

  @Test
  void a_run_carries_its_stdin_to_the_client_and_none_when_it_has_none() {
    Timed channel =
        new Timed(
            request ->
                FileReply.ran(
                    request.id(),
                    new io.aeyer.plowshare.protocol.CommandRunner.Outcome(
                        0, false, false, "", 0, "", 0, 1)));
    RemoteProvider provider = new RemoteProvider(channel, SESSION, WRITE);

    provider.run(
        Path.of("/laptop/repo"),
        List.of("python", "-m", "rpg.main"),
        OPEN,
        java.time.Duration.ofSeconds(30),
        "3\n",
        () -> false);
    provider.run(
        Path.of("/laptop/repo"),
        List.of("true"),
        OPEN,
        java.time.Duration.ofSeconds(30),
        () -> false);

    assertEquals("3\n", channel.sent.get(0).stdin());
    assertNull(channel.sent.get(1).stdin());
  }

  /** Input is bounded as output is (Task 2's review): past the bound nothing is sent. */
  @Test
  void a_run_whose_stdin_is_past_the_bound_is_refused_before_anything_is_sent() {
    Timed channel =
        new Timed(
            request ->
                FileReply.ran(
                    request.id(),
                    new io.aeyer.plowshare.protocol.CommandRunner.Outcome(
                        0, false, false, "", 0, "", 0, 1)));
    RemoteProvider provider = new RemoteProvider(channel, SESSION, WRITE);
    String past = "x".repeat(io.aeyer.plowshare.protocol.CommandRunner.MAX_STDIN_BYTES + 1);

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () ->
                provider.run(
                    Path.of("/laptop/repo"),
                    List.of("cat"),
                    OPEN,
                    java.time.Duration.ofSeconds(30),
                    past,
                    () -> false));

    assertTrue(
        refused.getMessage().contains("more than the 65536 a command may be given"),
        refused.getMessage());
    assertEquals(List.of(), channel.sent);
  }

  @Test
  void a_client_that_answers_a_run_without_an_outcome_is_not_a_command_that_printed_nothing() {
    Timed channel = new Timed(request -> FileReply.done(request.id()));
    RemoteProvider provider = new RemoteProvider(channel, SESSION, WRITE);

    assertThrows(
        WorkspaceUnavailableException.class,
        () ->
            provider.run(
                Path.of("/laptop/repo"),
                List.of("true"),
                OPEN,
                java.time.Duration.ofSeconds(5),
                () -> false));
  }

  @Test
  void a_read_only_agents_run_never_leaves_the_server() {
    Timed channel = new Timed(request -> FileReply.done(request.id()));
    RemoteProvider provider = new RemoteProvider(channel, SESSION, READ);

    assertThrows(
        WorkspaceRefusedException.class,
        () ->
            provider.run(
                Path.of("/laptop/repo"),
                List.of("true"),
                OPEN,
                java.time.Duration.ofSeconds(5),
                () -> false));
    assertTrue(channel.sent.isEmpty());
  }

  @Test
  void a_cancelled_job_sends_a_cancel_for_its_run_and_reads_the_run_it_killed() throws Exception {
    java.util.concurrent.CountDownLatch killed = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicReference<String> cancelledId =
        new java.util.concurrent.atomic.AtomicReference<>();
    Timed channel =
        new Timed(
            request -> {
              if (FileRequest.CANCEL.equals(request.op())) {
                cancelledId.set(request.path());
                killed.countDown();
                return FileReply.done(request.id());
              }
              try {
                killed.await();
              } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
              }
              return FileReply.ran(
                  request.id(),
                  new io.aeyer.plowshare.protocol.CommandRunner.Outcome(
                      null, false, true, "", 0, "", 0, 300));
            });
    RemoteProvider provider = new RemoteProvider(channel, SESSION, WRITE);

    io.aeyer.plowshare.protocol.CommandRunner.Outcome outcome =
        provider.run(
            Path.of("/laptop/repo"),
            List.of("sleep", "30"),
            OPEN,
            java.time.Duration.ofMinutes(1),
            () -> true);

    FileRequest run =
        channel.sent.stream().filter(r -> FileRequest.RUN.equals(r.op())).findFirst().orElseThrow();
    assertEquals(run.id(), cancelledId.get());
    assertTrue(outcome.cancelled());
  }

  private static final class Channel implements SessionChannel {

    private final List<FileRequest> sent = new ArrayList<>();
    private final Function<FileRequest, FileReply> answer;

    Channel(Function<FileRequest, FileReply> answer) {
      this.answer = answer;
    }

    @Override
    public FileReply ask(String session, FileRequest request) {
      assertEquals(SESSION, session, "a provider asks the session that submitted its job");
      sent.add(request);
      return answer.apply(request);
    }
  }
}
