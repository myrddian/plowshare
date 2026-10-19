package io.aeyer.plowshare.server.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * Caller-thread reuse of one immutable repository projection. Owners retain current authorization,
 * read auditing and disclosure locks. The bounded loader must avoid hydrating oversized data; the
 * uncached loader preserves the original read semantics when sharing is unsafe or unavailable.
 */
public interface ProjectionCache<T> {
  boolean enabled();

  long maxWeight();

  T read(
      SnapshotCache.Key key,
      boolean cacheable,
      Supplier<T> boundedLoader,
      Supplier<T> uncachedLoader,
      ToLongFunction<T> weight);

  default T read(
      SnapshotCache.Key key, boolean cacheable, Supplier<T> loader, ToLongFunction<T> weight) {
    return read(key, cacheable, loader, loader, weight);
  }

  /** A bounded SQL projection refused before hydrating content; retry only the ordinary read. */
  final class Bypass extends RuntimeException {
    public Bypass() {
      super("Projection cannot be shared", null, false, false);
    }
  }

  /** Existing standalone repositories keep direct reads without cache/preflight infrastructure. */
  static <T> ProjectionCache<T> direct() {
    return new ProjectionCache<>() {
      public boolean enabled() {
        return false;
      }

      public long maxWeight() {
        return 0;
      }

      public T read(
          SnapshotCache.Key key,
          boolean cacheable,
          Supplier<T> boundedLoader,
          Supplier<T> uncachedLoader,
          ToLongFunction<T> weight) {
        return uncachedLoader.get();
      }
    };
  }

  /** Length-delimited exact selection fingerprint; never shorten or omit a visibility input. */
  static String fingerprint(String... parts) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (String part : parts) {
        byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 unavailable", unavailable);
    }
  }
}
