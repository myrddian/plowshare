package io.aeyer.plowshare.server.llm.openai;

import java.io.EOFException;
import java.io.IOException;
import java.net.ProtocolException;
import java.net.SocketException;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

/**
 * Tracks the HTTP boundary for one streaming attempt. Only connection failures before the request
 * body starts are safe to recover: lack of a response alone does not establish non-delivery.
 */
final class StreamConnectionAttempt {
  private volatile boolean bodyStarted;
  private volatile boolean responseStarted;
  private volatile String phase = "connection";

  Response exchange(Interceptor.Chain chain) throws IOException {
    phase = "request_headers";
    Response response = chain.proceed(chain.request());
    responseStarted();
    return response;
  }

  void bodyStarted() {
    bodyStarted = true;
    phase = "request_body";
  }

  void responseStarted() {
    responseStarted = true;
    phase = "response_headers";
  }

  /**
   * The SSE adapter disables OkHttp event listeners. Track delivery at the actual body write and
   * network exchange instead, and prevent OkHttp from silently replaying a body it began writing.
   */
  Request request(Request request) {
    RequestBody delegate = request.body();
    if (delegate == null) throw new IllegalArgumentException("streaming request requires a body");
    RequestBody body =
        new RequestBody() {
          @Override
          public MediaType contentType() {
            return delegate.contentType();
          }

          @Override
          public long contentLength() throws IOException {
            return delegate.contentLength();
          }

          @Override
          public boolean isOneShot() {
            return true;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            bodyStarted();
            delegate.writeTo(sink);
          }
        };
    return request.newBuilder().method(request.method(), body).build();
  }

  boolean recoverable(Throwable failure) {
    Throwable cause = failure.getCause();
    return !bodyStarted
        && !responseStarted
        && (cause instanceof SocketException
            || cause instanceof EOFException
            || cause instanceof ProtocolException);
  }

  String phase() {
    return phase;
  }
}
