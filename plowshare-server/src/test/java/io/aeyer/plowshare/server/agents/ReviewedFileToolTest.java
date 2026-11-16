package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.FileAccess;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.approvals.ApprovalReviews;
import io.aeyer.plowshare.server.approvals.OperationApprovalReview;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.approvals.RunApprovalStore;
import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.files.LocalProvider;
import io.aeyer.plowshare.server.files.Mode;
import io.aeyer.plowshare.server.files.ProviderRouter;
import io.aeyer.plowshare.server.files.Scope;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.ToolPre;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    var router = new ProviderRouter(at -> List.of(provider));
    return new ReviewedFileTool(
        FileTools.of(name, router, new FileTools.Reads()),
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
    String frozen = tool.retainedArguments(args, home);
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

  @Test
  void pathsOutsideProviderFencesRemainRefusedWithoutModelOrHumanAccess() {
    var tool = tool(FileTools.READ_NAME);
    String args = "{\"path\":\"" + root.getParent().resolve("outside") + "\"}";
    assertTrue(gate(tool, args).isDenied());
    assertTrue(tool.waitingApproval().isEmpty());
    verifyNoInteractions(review);
  }
}
