package io.aeyer.plowshare.server.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Reconstruction metadata, never a plaintext token cache. The parent secret keys HMAC-SHA256; a
 * fresh server-only nonce and domain-separated labels bind both outputs to this rotation. Neither a
 * database digest/nonce leak nor a parent-plus-public-intent leak can derive the pair alone. Exact
 * captured requests remain indistinguishable from duplicate network delivery during the fixed
 * window: see the refresh-intent decision. Callers hold the chain lock and validate the current
 * successor and account authority before returning a reconstruction.
 */
record RefreshReceipt(RefreshIntent intent, String nonce, Instant issuedAt) {
  RefreshReceipt {
    java.util.Objects.requireNonNull(intent, "intent");
    java.util.Objects.requireNonNull(issuedAt, "issuedAt");
    if (nonce == null || !nonce.matches("[0-9a-f]{48}"))
      throw new IllegalArgumentException("invalid refresh receipt nonce");
  }

  static final Duration WINDOW = Duration.ofSeconds(30);

  Instant expiresAt(Duration accessLifetime, Duration refreshLifetime) {
    Duration limit = WINDOW.compareTo(accessLifetime) < 0 ? WINDOW : accessLifetime;
    if (refreshLifetime.compareTo(limit) < 0) limit = refreshLifetime;
    return issuedAt.plus(limit);
  }

  TokenStore.Pair pair(String parent) {
    return new TokenStore.Pair(derive(parent, "access"), derive(parent, "refresh"));
  }

  private String derive(String parent, String kind) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(parent.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] output =
          mac.doFinal(
              ("plowshare-refresh-v1:" + kind + ":" + intent.value() + ":" + nonce)
                  .getBytes(StandardCharsets.UTF_8));
      // Preserve the platform's 192-bit, lowercase hexadecimal bearer format.
      return HexFormat.of().formatHex(Arrays.copyOf(output, 24));
    } catch (GeneralSecurityException unavailable) {
      throw new IllegalStateException("required refresh cryptography unavailable", unavailable);
    }
  }

  @Override
  public String toString() {
    return "RefreshReceipt[redacted]";
  }
}
