package io.aeyer.plowshare.sdk;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

/** Bounded HTTP bootstrap/upload exception; public callbacks never occupy OkHttp's worker. */
final class HttpAsync {
  private static final Semaphore CAPACITY = new Semaphore(64);

  private HttpAsync() {}

  static <T> CompletionStage<T> call(Call call, AsyncSupport.Decoder<Response, T> decoder) {
    if (!CAPACITY.tryAcquire())
      return CompletableFuture.failedFuture(
          new Plowshare.TransportException(
              Plowshare.Delivery.NOT_SUBMITTED, "HTTP SDK capacity exceeded before submission"));
    var answer = new CompletableFuture<T>();
    answer.whenComplete(
        (value, failure) -> {
          if (answer.isCancelled()) call.cancel();
        });
    try {
      call.enqueue(
          new Callback() {
            @Override
            public void onFailure(Call ignored, IOException failure) {
              finish(answer, null, failure);
            }

            @Override
            public void onResponse(Call ignored, Response response) {
              T value;
              try (response) {
                value = decoder.decode(response);
              } catch (IOException | RuntimeException failure) {
                finish(answer, null, failure);
                return;
              }
              finish(answer, value, null);
            }
          });
    } catch (RuntimeException refused) {
      CAPACITY.release();
      answer.completeExceptionally(
          new Plowshare.TransportException(
              Plowshare.Delivery.NOT_SUBMITTED, "HTTP request was refused before submission"));
    }
    return answer;
  }

  private static <T> void finish(CompletableFuture<T> answer, T value, Throwable failure) {
    Thread.ofVirtual()
        .name("plowshare-sdk-http-completion")
        .start(
            () -> {
              try {
                if (failure == null) answer.complete(value);
                else answer.completeExceptionally(failure);
              } finally {
                CAPACITY.release();
              }
            });
  }
}
