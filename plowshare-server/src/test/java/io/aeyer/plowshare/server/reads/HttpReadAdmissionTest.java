package io.aeyer.plowshare.server.reads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.aeyer.plowshare.server.api.ApiExceptionHandler;
import io.aeyer.plowshare.server.auth.AuthFilter;
import io.aeyer.plowshare.server.telemetry.FailureReporter;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;

class HttpReadAdmissionTest {
  @RestController
  static class Endpoint {
    final AtomicInteger calls = new AtomicInteger();
    boolean fail;

    @GetMapping("/v1/fixture")
    public String read() {
      calls.incrementAndGet();
      if (fail) throw new ReadUnavailableException();
      return "ok";
    }
  }

  @Test
  void httpAndSocketWorkUseTheSamePrincipalBudgetAndCompletionReleasesIt() throws Exception {
    var admission = new BoundedReadAdmission(new ReadCapacityProperties(2, 1, 2, 1, 8, 2));
    var http = new HttpReadAdmission(admission);
    var request = new MockHttpServletRequest("GET", "/v1/fixture");
    request.setAttribute(AuthFilter.HANDLE_ATTRIBUTE, "alice");
    var response = new MockHttpServletResponse();
    var handler = new HandlerMethod(new Endpoint(), Endpoint.class.getMethod("read"));
    assertTrue(http.preHandle(request, response, handler));
    assertThrows(
        ReadUnavailableException.class,
        () -> admission.admit("alice", ReadAdmission.Work.INTERACTIVE));
    assertTrue(
        http.preHandle(request, response, handler), "Async redispatch must not reserve twice");
    http.afterCompletion(request, response, handler, null);
    var next = admission.admit("alice", ReadAdmission.Work.INTERACTIVE);
    next.close();
    next.close();
    assertEquals(0, admission.active(ReadAdmission.Work.INTERACTIVE));
  }

  @Test
  void aNormalHttpBurstWaitsBeforeTheHandlerWithoutLeakingAThreadDeadline() throws Exception {
    var admission = new BoundedReadAdmission(new ReadCapacityProperties(1, 1, 2, 1, 8, 2));
    var http = new HttpReadAdmission(admission);
    var request = new MockHttpServletRequest("GET", "/v1/fixture");
    request.setAttribute(AuthFilter.HANDLE_ATTRIBUTE, "alice");
    var response = new MockHttpServletResponse();
    var handler = new HandlerMethod(new Endpoint(), Endpoint.class.getMethod("read"));
    var held = admission.admit("alice", ReadAdmission.Work.INTERACTIVE);
    try (held;
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      var waiting =
          callers.submit(
              () -> {
                assertTrue(http.preHandle(request, response, handler));
                long remaining =
                    ReadDeadline.deadlineNanos(Duration.ofHours(1)) - System.nanoTime();
                assertTrue(remaining > Duration.ofMinutes(30).toNanos());
                return true;
              });
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (admission.waitingInteractive() != 1 && System.nanoTime() - deadline < 0)
        Thread.sleep(1);
      assertEquals(1, admission.waitingInteractive());
      held.close();
      assertTrue(waiting.get(5, TimeUnit.SECONDS));
      assertEquals(1, admission.active(ReadAdmission.Work.INTERACTIVE));
      http.afterCompletion(request, response, handler, null);
      assertEquals(0, admission.active(ReadAdmission.Work.INTERACTIVE));
    }
  }

  @Test
  void preHandlerCapacityHasItsOwnHttpFlowControlType() throws Exception {
    var admission =
        new BoundedReadAdmission(
            new ReadCapacityProperties(1, 1, 2, 1, 8, 2),
            new ReadSchedulingProperties(64, 4, 2000, 2000, 8, 4, 1));
    var http = new HttpReadAdmission(admission);
    var request = new MockHttpServletRequest("GET", "/v1/fixture");
    request.setAttribute(AuthFilter.HANDLE_ATTRIBUTE, "alice");
    var handler = new HandlerMethod(new Endpoint(), Endpoint.class.getMethod("read"));
    var held = admission.admit("alice", ReadAdmission.Work.INTERACTIVE);
    try (held) {
      assertThrows(
          IncomingReadCapacityException.class,
          () -> http.preHandle(request, new MockHttpServletResponse(), handler));
    }
    assertEquals(0, admission.active(ReadAdmission.Work.INTERACTIVE));
  }

  @Test
  void httpCapacityIs429WithoutStartingTheHandlerOrReportingArchiveFailure() throws Exception {
    var admission =
        new BoundedReadAdmission(
            new ReadCapacityProperties(1, 1, 2, 1, 8, 2),
            new ReadSchedulingProperties(64, 4, 2000, 2000, 8, 4, 1));
    var endpoint = new Endpoint();
    var failures = mock(FailureReporter.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(endpoint)
            .addInterceptors(new HttpReadAdmission(admission))
            .setControllerAdvice(new ApiExceptionHandler(failures))
            .build();
    var held = admission.admit("alice", ReadAdmission.Work.INTERACTIVE);
    try (held) {
      mvc.perform(get("/v1/fixture").requestAttr(AuthFilter.HANDLE_ATTRIBUTE, "alice"))
          .andExpect(status().isTooManyRequests())
          .andExpect(jsonPath("$.error").value("capacity_exhausted"));
      assertEquals(0, endpoint.calls.get());
      verifyNoInteractions(failures);
    }
    mvc.perform(get("/v1/fixture").requestAttr(AuthFilter.HANDLE_ATTRIBUTE, "alice"))
        .andExpect(status().isOk())
        .andExpect(content().string("ok"));
    assertEquals(1, endpoint.calls.get());
    endpoint.fail = true;
    mvc.perform(get("/v1/fixture").requestAttr(AuthFilter.HANDLE_ATTRIBUTE, "alice"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("archive_unavailable"));
    assertEquals(2, endpoint.calls.get(), "Mid-handler capacity is not an unstarted request");
  }
}
