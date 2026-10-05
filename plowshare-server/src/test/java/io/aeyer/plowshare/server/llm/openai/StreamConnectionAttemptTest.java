package io.aeyer.plowshare.server.llm.openai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.llm.dispatch.LlmTransportException;
import java.io.EOFException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;

class StreamConnectionAttemptTest {
  @Test
  void only_known_connection_failures_before_delivery_are_recoverable() {
    var attempt = new StreamConnectionAttempt();
    for (var cause :
        new Exception[] {
          new ConnectException(), new SocketException(), new EOFException(), new ProtocolException()
        }) {
      assertTrue(attempt.recoverable(new LlmTransportException("safe", cause)));
    }
    for (var cause :
        new Exception[] {
          new SocketTimeoutException(), new InterruptedIOException(), new IllegalStateException()
        }) {
      assertFalse(attempt.recoverable(new LlmTransportException("safe", cause)));
    }
    assertFalse(attempt.recoverable(new LlmTransportException("HTTP 503")));
  }

  @Test
  void starting_the_body_or_response_makes_delivery_uncertain() {
    var failure = new LlmTransportException("safe", new EOFException());
    var body = new StreamConnectionAttempt();
    body.bodyStarted();
    assertFalse(body.recoverable(failure));
    var response = new StreamConnectionAttempt();
    response.responseStarted();
    assertFalse(response.recoverable(failure));
  }
}
