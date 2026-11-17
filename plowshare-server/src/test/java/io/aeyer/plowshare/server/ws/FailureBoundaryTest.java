package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Envelope;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.ApiExceptionHandler;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.files.SessionGoneException;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import io.aeyer.plowshare.server.reads.ReadUnavailableException;
import io.aeyer.plowshare.server.telemetry.FailureReporter;
import io.aeyer.plowshare.server.telemetry.FailureReporter.Source;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class FailureBoundaryTest {
  private final FailureReporter failures = mock(FailureReporter.class);
  private final Envelope request =
      new Envelope("read", "schedule.list", Envelope.CURRENT_VERSION, Map.of());

  @Test
  void synchronousSessionLossIsObservedOnceWithoutChangingTheReply() {
    var gone = new SessionGoneException("private session");
    var router =
        router(
            (body, asking) -> {
              throw gone;
            },
            ReadAdmission.direct());
    assertEquals(Code.ARCHIVE_UNAVAILABLE, router.route(request, FrameParity.ASKING).code());
    verify(failures).report(Source.WEBSOCKET, gone);
    verifyNoMoreInteractions(failures);
  }

  @Test
  void asynchronousReadFailureIsObservedAfterUnwrapping() {
    var busy = new ReadUnavailableException();
    var router =
        router(
            new FrameHandler() {
              public Outcome handle(Map<String, Object> body, Asking asking) {
                throw new AssertionError("Async dispatch must use handleAsync");
              }

              public CompletionStage<Outcome> handleAsync(Map<String, Object> body, Asking asking) {
                return CompletableFuture.failedFuture(busy);
              }
            },
            ReadAdmission.direct());
    var reply = router.routeAsync(request, FrameParity.ASKING).toCompletableFuture().join();
    assertEquals(Code.ARCHIVE_UNAVAILABLE, reply.code());
    verify(failures).report(Source.WEBSOCKET, busy);
    verifyNoMoreInteractions(failures);
  }

  @Test
  void readAdmissionPressureRefusesBeforeTheHandlerWithoutCountingAServerFailure() {
    var busy = new ReadUnavailableException();
    var router =
        router(
            (body, asking) -> {
              throw new AssertionError("Must not run");
            },
            (principal, work) -> {
              throw busy;
            });
    assertEquals(Code.CAPACITY_EXHAUSTED, router.route(request, FrameParity.ASKING).code());
    verifyNoInteractions(failures);
  }

  @Test
  void callerRefusalsDoNotCountAsServerErrors() {
    var router =
        router(
            (body, asking) -> {
              throw new CallerFault("invalid");
            },
            ReadAdmission.direct());
    assertEquals(Code.BAD_REQUEST, router.route(request, FrameParity.ASKING).code());
    new ApiExceptionHandler(failures).callerFault(new CallerFault("invalid"));
    verifyNoInteractions(failures);
  }

  @Test
  void httpReadFailureKeepsItsAvailabilityStatusAndReportsItsOwnSource() {
    var busy = new ReadUnavailableException();
    assertEquals(503, new ApiExceptionHandler(failures).unexpected(busy).getStatusCode().value());
    verify(failures).report(Source.HTTP, busy);
    verifyNoMoreInteractions(failures);
  }

  private FrameRouter router(FrameHandler handler, ReadAdmission admission) {
    return new FrameRouter(Map.of(request.type(), handler), null, admission, failures);
  }
}
