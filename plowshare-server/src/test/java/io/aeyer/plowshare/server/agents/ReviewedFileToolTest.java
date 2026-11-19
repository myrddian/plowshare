package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.FileAccess;
import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.approvals.ApprovalReviews;
import io.aeyer.plowshare.server.approvals.OperationApprovalReview;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.approvals.RunApprovalStore;
import io.aeyer.plowshare.server.approvals.SecurityReviewer;
import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.files.LocalProvider;
import io.aeyer.plowshare.server.files.Mode;
import io.aeyer.plowshare.server.files.ProviderRouter;
import io.aeyer.plowshare.server.files.RemoteProvider;
import io.aeyer.plowshare.server.files.Scope;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.files.SessionGoneException;
import io.aeyer.plowshare.server.files.WorkspaceCodeMap;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.ToolPre;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReviewedFileToolTest {
  @TempDir Path root;
  private final Home home = Home.of("project");
  private final OperationApprovalReview review = mock(OperationApprovalReview.class);
  private final RunApprovalStore store = mock(RunApprovalStore.class);
  private final HookContext context =
      new HookContext("coder", false, Set.of("file_edit"), "project", null, HookContext.SERVER);
  private final AtomicReference<RunApproval> question = new AtomicReference<>();

  private ReviewedFileTool tool(String name) {
    var provider =
        LocalProvider.over(
            FileAccess.of(List.of(root), List.of()),
            List.of(new Grant(Scope.WORKSPACE, Mode.WRITE)));
    return tool(name, new ProviderRouter(at -> List.of(provider)));
  }

  private ReviewedFileTool tool(String name, ProviderRouter router) {
    return tool(name, router, new FileTools.Reads());
  }

  private ReviewedFileTool tool(String name, ProviderRouter router, FileTools.Reads reads) {
    return new ReviewedFileTool(
        FileTools.of(name, router, reads),
        router,
        review,
        store,
        asked -> {
          var row =
              new RunApproval(
                  "approval",
                  1,
                  "conversation",
                  "conversation",
                  null,
                  "coder",
                  asked.side(),
                  List.of(),
                  root.toString(),
                  asked.reason(),
                  RunApproval.ASKED,
                  null,
                  null,
                  null,
                  null,
                  null,
                  Instant.now(),
                  null,
                  null,
                  null,
                  asked.file());
          question.set(row);
          return row;
        });
  }

  @Test
  void a_replacement_connection_cannot_use_the_original_review() throws Exception {
    String workspace = root.toRealPath().toString();
    SessionChannel transport = mock(SessionChannel.class);
    SessionChannel original = mock(SessionChannel.class);
    SessionChannel replacement = mock(SessionChannel.class);
    when(transport.bound("client")).thenReturn(original, replacement);
    for (SessionChannel channel : List.of(original, replacement)) {
      when(channel.ask(eq("client"), argThat(request -> FileRequest.ROOTS.equals(request.op()))))
          .thenAnswer(
              call ->
                  FileReply.listed(
                      call.getArgument(1, FileRequest.class).id(), List.of(workspace)));
    }
    var remote =
        new RemoteProvider(transport, "client", List.of(new Grant(Scope.WORKSPACE, Mode.WRITE)));
    var tool = tool(FileTools.DELETE_NAME, new ProviderRouter(at -> List.of(remote)));
    when(review.review(any(), any()))
        .thenReturn(
            new ApprovalReviews.Decision(
                ApprovalReviews.Action.ALLOW, "Matches the request.", 1, List.of(), null, null));
    String args = "{\"path\":\"" + workspace + "/draft.md\"}";
    assertFalse(gate(tool, args).isDenied());
    doThrow(new SessionGoneException("Connection replaced"))
        .when(original)
        .requireCurrent("client");
    assertThrows(SessionGoneException.class, () -> tool.run(args, home));
    verify(replacement, never())
        .ask(eq("client"), argThat(request -> FileRequest.DELETE.equals(request.op())));
  }

  @Test
  void read_uses_the_provider_that_passed_the_fresh_approval_fence() throws Exception {
    Path target = root.resolve("preferences.md");
    Files.writeString(target, "Keep summaries short");
    var provider =
        LocalProvider.over(
            FileAccess.of(List.of(root), List.of()),
            List.of(new Grant(Scope.WORKSPACE, Mode.READ)));
    var routes = new AtomicInteger();
    var router =
        new ProviderRouter(at -> routes.incrementAndGet() <= 2 ? List.of(provider) : List.of());
    var tool = tool(FileTools.READ_NAME, router);
    when(review.review(any(), any())).thenReturn(decision(ApprovalReviews.Action.ALLOW));
    String args = "{\"path\":\"" + target + "\"}";
    assertFalse(gate(tool, args).isDenied());
    assertTrue(tool.run(args, home).contains("Keep summaries short"));
    verify(review).requireCurrent(any());
    assertEquals(2, routes.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {FileTools.EDIT_NAME, FileTools.DELETE_NAME, FileTools.MOVE_NAME})
  void mutations_use_the_provider_that_passed_the_fresh_approval_fence(String name)
      throws Exception {
    Path source = Files.writeString(root.resolve("source.md"), "original");
    Path destination = root.resolve("destination.md");
    var provider =
        LocalProvider.over(
            FileAccess.of(List.of(root), List.of()),
            List.of(new Grant(Scope.WORKSPACE, Mode.WRITE)));
    var routes = new AtomicInteger();
    int expectedRoutes = FileTools.MOVE_NAME.equals(name) ? 4 : 2;
    var router =
        new ProviderRouter(
            at -> routes.incrementAndGet() <= expectedRoutes ? List.of(provider) : List.of());
    var codeMap = mock(WorkspaceCodeMap.class);
    var tool = tool(name, router, new FileTools.Reads(codeMap));
    when(review.review(any(), any())).thenReturn(decision(ApprovalReviews.Action.ALLOW));
    String args =
        switch (name) {
          case FileTools.EDIT_NAME ->
              "{\"path\":\"" + source + "\",\"old\":\"original\",\"new\":\"new contents\"}";
          case FileTools.DELETE_NAME -> "{\"path\":\"" + source + "\"}";
          default -> "{\"path\":\"" + source + "\",\"to\":\"" + destination + "\"}";
        };

    assertFalse(gate(tool, args).isDenied());
    tool.run(args, home);

    switch (name) {
      case FileTools.EDIT_NAME -> assertEquals("new contents", Files.readString(source));
      case FileTools.DELETE_NAME -> assertFalse(Files.exists(source));
      default -> {
        assertFalse(Files.exists(source));
        assertEquals("original", Files.readString(destination));
      }
    }
    verify(review).requireCurrent(any());
    verify(codeMap).beforeMutation(home);
    verify(codeMap).afterMutation(eq(home), any());
    assertEquals(expectedRoutes, routes.get());
  }

  private ApprovalReviews.Decision decision(ApprovalReviews.Action action) {
    return new ApprovalReviews.Decision(
        action, "The task requested inspection only.", 1, List.of(), null, null);
  }

  private ToolPre gate(ReviewedFileTool tool, String arguments) {
    return tool.gate(arguments, home, context, (at, args) -> new ToolPre(args, null, List.of()));
  }

  @Test
  void securityRejectionDoesNotChangeAFileOrCreateAQuestion() throws Exception {
    Path target = root.resolve("config");
    Files.writeString(target, "old");
    var tool = tool(FileTools.EDIT_NAME);
    when(review.review(any(), any())).thenReturn(decision(ApprovalReviews.Action.REJECT));
    String args = "{\"path\":\"" + target + "\",\"old\":\"old\",\"new\":\"changed\"}";
    assertTrue(gate(tool, args).isDenied());
    assertTrue(tool.securityRejected());
    assertTrue(tool.waitingApproval().isEmpty());
    assertEquals("old", Files.readString(target));
    assertTrue(tool.run(args, home).contains("No review"));
    assertEquals("old", Files.readString(target));
  }

  @Test
  void clearReviewExecutesOnlyTheExactArgumentsOnce() throws Exception {
    Path target = root.resolve("created");
    var tool = tool(FileTools.EDIT_NAME);
    when(review.review(any(), any())).thenReturn(decision(ApprovalReviews.Action.ALLOW));
    String args = "{\"path\":\"" + target + "\",\"content\":\"new contents\"}";
    assertFalse(gate(tool, args).isDenied());
    assertTrue(tool.run(args, home).startsWith("Wrote"));
    verify(review).requireCurrent(any());
    assertEquals("new contents", Files.readString(target));
    assertTrue(tool.run(args, home).contains("No review"));
  }

  @Test
  void humanQuestionRetainsItsTypedTargetsAndRefusesHookChanges() throws Exception {
    Path target = root.resolve("config");
    Files.writeString(target, "old");
    var tool = tool(FileTools.DELETE_NAME);
    when(review.review(any(), any())).thenReturn(decision(ApprovalReviews.Action.ASK_HUMAN));
    String args = "{\"path\":\"" + target + "\"}";
    assertTrue(gate(tool, args).isDenied());
    assertEquals(List.of(target.toString()), question.get().file().paths());
    String frozen =
        tool.retainedArguments(args, home).replace(",\"securityEditVerified\":false", "");
    ToolPre changed =
        tool.gateRetained(
            frozen,
            "server",
            home,
            context,
            (at, original) -> new ToolPre(original + " ", null, List.of()));
    assertTrue(changed.isDenied());
    assertTrue(Files.exists(target));
    when(store.consumeFile(question.get().id(), question.get().file())).thenReturn(true);
    ToolPre checked =
        tool.gateRetained(
            frozen,
            "server",
            home,
            context,
            (at, original) -> new ToolPre(original, null, List.of()));
    assertFalse(checked.isDenied());
    assertTrue(tool.run(checked.arguments(), home).startsWith("Deleted"));
    verify(review).humanOverride(any(), eq("approval"));
    assertFalse(Files.exists(target));
  }

  @ParameterizedTest
  @ValueSource(strings = {"edit", "delete", "move-from", "move-to"})
  void protected_mutations_cannot_park_when_security_did_not_clear(String action) {
    Path policy = root.resolve("security.md");
    Path ordinary = root.resolve("ordinary.md");
    String name =
        switch (action) {
          case "edit" -> FileTools.EDIT_NAME;
          case "delete" -> FileTools.DELETE_NAME;
          default -> FileTools.MOVE_NAME;
        };
    String arguments =
        switch (action) {
          case "edit" -> "{\"path\":\"" + policy + "\",\"content\":\"new\"}";
          case "delete" -> "{\"path\":\"" + policy + "\"}";
          case "move-from" -> "{\"path\":\"" + policy + "\",\"to\":\"" + ordinary + "\"}";
          default -> "{\"path\":\"" + ordinary + "\",\"to\":\"" + policy + "\"}";
        };
    var tool = tool(name);
    when(review.review(any(), any())).thenReturn(decision(ApprovalReviews.Action.ASK_HUMAN));

    assertTrue(gate(tool, arguments).isDenied());
    assertTrue(tool.securityRejected());
    assertTrue(tool.waitingApproval().isEmpty());
    verify(review).review(argThat(OperationApprovalReview.Request::securityFileMutation), any());
    verifyNoInteractions(store);
  }

  @Test
  void protected_retained_approval_requires_the_original_server_clearance() throws Exception {
    Path target = root.resolve("security.md");
    Files.writeString(target, "old");
    var tool = tool(FileTools.DELETE_NAME);
    when(review.review(any(), any()))
        .thenReturn(
            new ApprovalReviews.Decision(
                ApprovalReviews.Action.ASK_HUMAN,
                "Consent is also required.",
                1,
                List.of(),
                new SecurityReviewer.Verdict(
                    SecurityReviewer.Outcome.CLEAR, "The user asked to delete the policy."),
                null));
    String args = "{\"path\":\"" + target + "\"}";
    assertTrue(gate(tool, args).isDenied());
    assertTrue(tool.waitingApproval().isPresent());
    String frozen = tool.retainedArguments(args, home);
    BiFunction<HookContext, String, ToolPre> chain =
        (at, original) -> new ToolPre(original, null, List.of());
    for (String invalid :
        List.of(
            frozen.replace(",\"securityEditVerified\":true", ""),
            frozen.replace("\"securityEditVerified\":true", "\"securityEditVerified\":\"true\""))) {
      assertTrue(tool.gateRetained(invalid, "server", home, context, chain).isDenied());
    }
    verifyNoInteractions(store);
    when(store.consumeFile(question.get().id(), question.get().file())).thenReturn(true);
    assertFalse(tool.gateRetained(frozen, "server", home, context, chain).isDenied());
    verify(review).humanOverride(any(), eq("approval"), eq(true));
  }

  @Test
  void a_retargeted_alias_requires_fresh_security_clearance_for_a_direct_edit() throws Exception {
    Path ordinary = Files.createDirectory(root.resolve("ordinary"));
    Path security = Files.createDirectory(root.resolve("security"));
    Path alias = Files.createSymbolicLink(root.resolve("alias"), ordinary);
    Path protectedFile = Files.writeString(security.resolve("fragment.md"), "unchanged");
    var tool = tool(FileTools.EDIT_NAME);
    when(review.review(any(), any())).thenReturn(decision(ApprovalReviews.Action.ALLOW));
    String args = "{\"path\":\"" + alias.resolve("fragment.md") + "\",\"content\":\"new\"}";
    assertFalse(gate(tool, args).isDenied());
    Files.delete(alias);
    Files.createSymbolicLink(alias, security);

    assertTrue(tool.run(args, home).contains("target changed"));
    assertEquals("unchanged", Files.readString(protectedFile));
  }

  @Test
  void a_personal_read_alias_has_no_standing_intent() throws Exception {
    Path workspace = root.toRealPath();
    var provider = mock(FileProvider.class);
    when(provider.roots()).thenReturn(List.of(workspace));
    when(provider.identity()).thenReturn("personal");
    when(provider.forReviewedRead()).thenReturn(provider);
    when(provider.reviewPath(any()))
        .thenAnswer(call -> call.getArgument(0, Path.class).normalize());
    when(provider.personalWorkspace())
        .thenReturn(Optional.of(new FileProvider.PersonalWorkspace("owner", workspace)));
    var tool = tool(FileTools.READ_NAME, new ProviderRouter(at -> List.of(provider)));
    when(review.review(any(), any())).thenReturn(decision(ApprovalReviews.Action.ALLOW));
    assertFalse(gate(tool, "{\"path\":\"" + workspace + "//preferences.md\"}").isDenied());
    assertFalse(gate(tool, "{\"path\":\"" + workspace + "/preferences.md\"}").isDenied());
    verify(review).review(argThat(request -> request.personalWorkspace().isEmpty()), any());
    verify(review).review(argThat(request -> request.personalWorkspace().isPresent()), any());
  }

  @Test
  void pathsOutsideProviderFencesRemainRefusedWithoutModelOrHumanAccess() {
    var tool = tool(FileTools.READ_NAME);
    String args = "{\"path\":\"" + root.getParent().resolve("outside") + "\"}";
    assertTrue(gate(tool, args).isDenied());
    assertTrue(tool.waitingApproval().isEmpty());
    verifyNoInteractions(review);
  }
}
