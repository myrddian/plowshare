package io.aeyer.plowshare.server.information;

/** One lifecycle-owned, bounded timer; callbacks must run asynchronously outside schedule(). */
public interface InformationInvalidationClock extends AutoCloseable {
  void schedule(Runnable action);

  @Override
  void close();
}
