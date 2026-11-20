package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.server.approvals.ReviewWork;
import io.aeyer.plowshare.server.approvals.SecurityPolicyFiles;
import io.aeyer.plowshare.server.approvals.WorkspaceSecurityGuidance.Guidance;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.files.WorkspaceUnavailableException;
import io.aeyer.plowshare.server.hooks.Deadline;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

class ProjectSecurityGuidanceTest {
  @Test
  void security_consults_the_selected_remote_checkout_and_its_named_fragments() {
    var configurations = mock(ProjectConfigurations.class);
    when(configurations.securityGuidance(eq("project"), any())).thenReturn("Registered policy");
    List<Duration> waits = new ArrayList<>();
    SessionChannel channel =
        timedChannel(
            (request, wait) -> {
              waits.add(wait);
              Span span =
                  switch (request.path()) {
                    case "security.md" ->
                        switch (request.window().offset()) {
                          case 0 -> new Span(List.of("---", "version: 1"), 0, 5, true, Span.LINES);
                          case 2 ->
                              new Span(
                                  List.of("includes: [edits.md]", "---", "Checkout policy"),
                                  2,
                                  5,
                                  false,
                                  Span.END);
                          default -> throw new AssertionError("Unexpected page requested");
                        };
                    case "security/edits.md" ->
                        new Span(List.of("Extra guidance"), 0, 1, false, Span.END);
                    default -> throw new AssertionError("Unexpected resource requested");
                  };
              return FileReply.answered(request.id(), span);
            });
    var source = new ProjectSecurityGuidance(configurations, channel);
    var budget = Duration.ofSeconds(2);
    var work = new ReviewWork(Deadline.after(budget), () -> false);
    assertEquals(
        new Guidance("Registered policy", "Checkout policy\n\nExtra guidance"),
        source.read("project", "remote:serving-session", work));
    assertEquals(new Guidance("Registered policy", ""), source.read("project", "local", work));
    assertEquals(3, waits.size());
    assertTrue(waits.get(0).compareTo(budget) < 0);
    assertTrue(waits.get(1).compareTo(waits.get(0)) < 0);
    assertTrue(waits.get(2).compareTo(waits.get(1)) < 0);
  }

  @Test
  void cancelling_a_review_during_a_checkout_read_discards_its_reply_and_stops_paging() {
    var cancelled = new AtomicBoolean();
    var requests = new AtomicInteger();
    var channel =
        timedChannel(
            (request, wait) -> {
              requests.incrementAndGet();
              cancelled.set(true);
              return FileReply.answered(
                  request.id(), new Span(List.of("partial"), 0, 2, true, Span.LINES));
            });
    var source = new ProjectSecurityGuidance(mock(ProjectConfigurations.class), channel);
    var work = new ReviewWork(Deadline.after(Duration.ofSeconds(2)), cancelled::get);
    assertThrows(
        WorkspaceUnavailableException.class,
        () -> source.read("project", "remote:serving-session", work));
    assertEquals(1, requests.get());
  }

  @Test
  void checkout_guidance_refuses_oversize_or_truncated_windows() {
    for (Span invalid :
        List.of(
            new Span(
                List.of("x".repeat(SecurityPolicyFiles.MAX_BYTES + 1)), 0, 2, true, Span.BYTES),
            new Span(List.of("partial"), 0, 2, false, Span.END),
            new Span(List.of("wrong offset"), 1, 2, false, Span.END))) {
      var requests = new AtomicInteger();
      var channel =
          timedChannel(
              (request, wait) -> {
                requests.incrementAndGet();
                return FileReply.answered(request.id(), invalid);
              });
      var source = new ProjectSecurityGuidance(mock(ProjectConfigurations.class), channel);
      var work = new ReviewWork(Deadline.after(Duration.ofSeconds(2)), () -> false);
      assertThrows(
          IllegalStateException.class,
          () -> source.read("project", "remote:serving-session", work));
      assertEquals(1, requests.get());
    }
  }

  private static SessionChannel timedChannel(BiFunction<FileRequest, Duration, FileReply> answer) {
    return new SessionChannel() {
      @Override
      public FileReply ask(String session, FileRequest request) {
        throw new AssertionError("Security reads must use the timed file transport");
      }

      @Override
      public FileReply ask(String session, FileRequest request, Duration wait) {
        assertEquals("serving-session", session);
        assertEquals(FileRequest.READ, request.op());
        return answer.apply(request, wait);
      }
    };
  }
}
