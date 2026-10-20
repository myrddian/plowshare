package io.aeyer.plowshare.sdk;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Checked DTO conversion belongs to the typed facade, after the shared exchange settles. */
final class AsyncSupport {
  private AsyncSupport() {}

  @FunctionalInterface
  interface Decoder<T, R> {
    R decode(T value) throws IOException;
  }

  /** Blocking compatibility owns its cancellation signal, including the interrupt cleanup. */
  static <T> T await(
      RequestOptions defaults,
      java.util.function.Function<RequestOptions, CompletionStage<T>> start)
      throws IOException {
    var cancellation = new RequestCancellation();
    try {
      return Plowshare.await(start.apply(new RequestOptions(defaults.timeout(), cancellation)));
    } finally {
      if (Thread.currentThread().isInterrupted()) cancellation.cancel();
    }
  }

  static <T, R> CompletionStage<R> map(CompletionStage<T> stage, Decoder<T, R> decoder) {
    return stage.thenApply(
        value -> {
          try {
            return decoder.decode(value);
          } catch (IOException failure) {
            throw new CompletionException(failure);
          }
        });
  }
}
