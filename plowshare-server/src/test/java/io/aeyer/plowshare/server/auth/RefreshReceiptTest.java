package io.aeyer.plowshare.server.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshReceiptTest {
  @Test
  void derivation_is_separated_and_binds_parent_intent_and_server_nonce() {
    String parent = Tokens.mint();
    var intent = new RefreshIntent(UUID.randomUUID());
    String nonce = Tokens.mint();
    var receipt = new RefreshReceipt(intent, nonce, Instant.EPOCH);
    var pair = receipt.pair(parent);
    assertTrue(pair.access().matches("[0-9a-f]{48}"));
    assertTrue(pair.refresh().matches("[0-9a-f]{48}"));
    assertFalse(pair.access().equals(pair.refresh()));
    assertTrue(pair.access().equals(receipt.pair(parent).access()));
    assertFalse(pair.access().equals(receipt.pair(Tokens.mint()).access()));
    assertFalse(
        pair.access()
            .equals(
                new RefreshReceipt(new RefreshIntent(UUID.randomUUID()), nonce, Instant.EPOCH)
                    .pair(parent)
                    .access()));
    assertFalse(
        pair.access()
            .equals(
                new RefreshReceipt(intent, Tokens.mint(), Instant.EPOCH).pair(parent).access()));
    assertFalse(receipt.toString().contains(nonce));
    assertFalse(
        new RefreshRotation(pair, Duration.ofMinutes(15), Duration.ofDays(7))
            .toString()
            .contains(pair.access()));
    assertEquals(
        Instant.EPOCH.plusSeconds(2), receipt.expiresAt(Duration.ofSeconds(2), Duration.ofDays(7)));
    assertEquals(
        Instant.EPOCH.plusSeconds(1),
        receipt.expiresAt(Duration.ofMinutes(15), Duration.ofSeconds(1)));
  }
}
