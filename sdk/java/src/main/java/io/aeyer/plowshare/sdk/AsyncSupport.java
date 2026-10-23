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

  /**
   * Blocking compatibility owns its cancellation signal, including the interrupt cleanup.
   *
   * <p>The signal is handed to {@link Plowshare#await(CompletionStage, RequestCancellation)}, which
   * cancels through it on an interrupt and then reports the delivery the exchange itself settled
   * on. Cancelling here in a {@code finally} instead would fire after the outcome had already been
   * decided, so a caller interrupted while parked for capacity would be told the server might have
   * done work for a request that was never written.
   *
   * <p>Only the cancellation is substituted. The caller's own {@link RequestOptions#capacity()}
   * travels with the call, so the blocking surface can express the non-blocking form too.
   */
  static <T> T await(
      RequestOptions defaults,
      java.util.function.Function<RequestOptions, CompletionStage<T>> start)
      throws IOException {
    var cancellation = new RequestCancellation();
    return Plowshare.await(
        start.apply(new RequestOptions(defaults.timeout(), cancellation, defaults.capacity())),
        cancellation);
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
