package io.aeyer.plowshare.protocol.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class SegmentedMessagesTest {
  private static final class Wire implements PacketWire {
    final BlockingQueue<MessageSegment> sent = new LinkedBlockingQueue<>();
    final List<SegmentCredit> credits = new ArrayList<>();

    public void segment(MessageSegment part) {
      sent.add(part);
    }

    public void credit(SegmentCredit credit) {
      credits.add(credit);
    }
  }

  private static List<MessageSegment> parts(byte[] bytes) throws Exception {
    String id = UUID.randomUUID().toString();
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    int count = (bytes.length + SegmentedMessages.CHUNK_BYTES - 1) / SegmentedMessages.CHUNK_BYTES;
    var parts = new ArrayList<MessageSegment>();
    for (int n = 1; n <= count; n++) {
      int start = (n - 1) * SegmentedMessages.CHUNK_BYTES;
      parts.add(
          new MessageSegment(
              "transport.segment",
              1,
              id,
              n,
              count,
              start,
              bytes.length,
              hash,
              Base64.getEncoder()
                  .encodeToString(
                      Arrays.copyOfRange(
                          bytes,
                          start,
                          Math.min(bytes.length, start + SegmentedMessages.CHUNK_BYTES)))));
    }
    return parts;
  }

  @Test
  void completed_ids_are_connection_scoped_and_never_evicted_on_capacity_exhaustion()
      throws Exception {
    var budget = new PacketBudget(256L * 1024 * 1024);
    var first = parts(new byte[] {65}).getFirst();
    try (var left = new SegmentedMessages(new Wire(), budget, () -> {});
        var right = new SegmentedMessages(new Wire(), budget, () -> {})) {
      assertEquals("A", left.receive(first).orElseThrow());
      assertEquals(
          "A",
          right.receive(first).orElseThrow(),
          "another connection owns another assembly namespace");
      for (int n = 1; n < SegmentedMessages.MAX_TOMBSTONES; n++)
        left.receive(parts(new byte[] {65}).getFirst());
      assertThrows(
          IllegalArgumentException.class, () -> left.receive(parts(new byte[] {65}).getFirst()));
      assertThrows(IllegalArgumentException.class, () -> left.receive(first));
    }
    assertEquals(0, budget.heldBytes());
  }

  @Test
  void encoded_message_boundary_is_independent_of_packet_allowance() throws Exception {
    // Exercise the full preflight ceiling without allocating several 320 MiB test strings.
    assertEquals(
        SegmentedMessages.MAX_MESSAGE_BYTES,
        SegmentedMessages.utf8Length(asciiView(SegmentedMessages.MAX_MESSAGE_BYTES)));
    assertThrows(
        IllegalArgumentException.class,
        () -> SegmentedMessages.utf8Length(asciiView(SegmentedMessages.MAX_MESSAGE_BYTES + 1)));
    assertTrue(
        SegmentedMessages.MAX_MESSAGE_BYTES
            > 6L * io.aeyer.plowshare.protocol.RelayPort.MAX_TEXT_BYTES + 1024 * 1024);
  }

  private static CharSequence asciiView(int length) {
    return new CharSequence() {
      public int length() {
        return length;
      }

      public char charAt(int index) {
        return 'x';
      }

      public CharSequence subSequence(int start, int end) {
        throw new UnsupportedOperationException("test view has no slices");
      }
    };
  }

  @Test
  void assembly_preserves_split_unicode_and_dispatches_only_once_after_verified_completion()
      throws Exception {
    String text = "x".repeat(SegmentedMessages.CHUNK_BYTES - 1) + "😀" + "y".repeat(100);
    var parts = parts(text.getBytes(StandardCharsets.UTF_8));
    var budget = new PacketBudget(256L * 1024 * 1024);
    var wire = new Wire();
    try (var transport = new SegmentedMessages(wire, budget, () -> fail("unexpected expiry"))) {
      assertTrue(transport.receive(parts.get(1)).isEmpty());
      assertTrue(transport.receive(parts.get(1)).isEmpty());
      assertEquals(text, transport.receive(parts.getFirst()).orElseThrow());
      assertEquals(3, wire.credits.size());
      assertThrows(IllegalArgumentException.class, () -> transport.receive(parts.getFirst()));
      assertEquals(256 * 3, budget.heldBytes(), "completed IDs retain bounded memory");
    }
    assertEquals(0, budget.heldBytes());
  }

  @Test
  void conflicting_duplicate_bad_hash_and_invalid_utf8_never_emit_a_complete_message()
      throws Exception {
    for (int mode = 0; mode < 3; mode++) {
      var budget = new PacketBudget(256L * 1024 * 1024);
      var wire = new Wire();
      try (var transport = new SegmentedMessages(wire, budget, () -> {})) {
        if (mode == 0) {
          var first = parts(new byte[70000]).getFirst();
          transport.receive(first);
          byte[] changed = Base64.getDecoder().decode(first.data());
          changed[0] = 1;
          var duplicate =
              new MessageSegment(
                  first.kind(),
                  first.version(),
                  first.transferId(),
                  1,
                  first.segmentCount(),
                  0,
                  first.totalBytes(),
                  first.sha256(),
                  Base64.getEncoder().encodeToString(changed));
          assertThrows(IllegalArgumentException.class, () -> transport.receive(duplicate));
        } else {
          var first = parts(mode == 1 ? new byte[] {65} : new byte[] {(byte) 0xff}).getFirst();
          var invalid =
              mode == 1
                  ? new MessageSegment(
                      first.kind(), 1, first.transferId(), 1, 1, 0, 1, "0".repeat(64), first.data())
                  : first;
          assertThrows(IllegalArgumentException.class, () -> transport.receive(invalid));
          assertTrue(wire.credits.isEmpty(), "failed final verification issues no credit");
        }
      }
      assertEquals(0, budget.heldBytes());
    }
  }

  @Test
  void incomplete_transfers_expire_and_release_process_capacity() throws Exception {
    var now = new AtomicLong();
    var expired = new AtomicInteger();
    var budget = new PacketBudget(256L * 1024 * 1024);
    try (var transport =
        new SegmentedMessages(new Wire(), budget, expired::incrementAndGet, now::get)) {
      transport.receive(parts(new byte[70000]).getFirst());
      assertEquals(210000, budget.heldBytes());
      now.set(SegmentedMessages.IDLE_TIMEOUT.toNanos());
      transport.expire();
      assertEquals(1, expired.get());
      assertEquals(0, budget.heldBytes());
      assertThrows(IOException.class, () -> transport.receive(parts(new byte[] {65}).getFirst()));
    }
  }

  @Test
  @SuppressWarnings(
      "try") // Explicit close is the behavior under test; final cleanup stays guarded.
  void credits_gate_every_packet_and_close_unblocks_a_sender_without_replay() throws Exception {
    var wire = new Wire();
    var budget = new PacketBudget(256L * 1024 * 1024);
    try (var transport = new SegmentedMessages(wire, budget, () -> {});
        var executor = Executors.newSingleThreadExecutor()) {
      var sent =
          executor.submit(
              () -> {
                transport.send("x".repeat(70000));
                return null;
              });
      var first = wire.sent.poll(5, TimeUnit.SECONDS);
      assertNotNull(first);
      assertNull(wire.sent.poll(50, TimeUnit.MILLISECONDS), "second packet needs credit");
      transport.credit(new SegmentCredit("transport.credit", 1, first.transferId(), 1));
      var second = wire.sent.poll(5, TimeUnit.SECONDS);
      assertNotNull(second);
      transport.close();
      assertInstanceOf(
          IOException.class,
          assertThrows(ExecutionException.class, () -> sent.get(5, TimeUnit.SECONDS)).getCause());
      assertTrue(wire.sent.isEmpty());
    }
    assertEquals(0, budget.heldBytes());
  }

  @Test
  @SuppressWarnings("try") // Explicit close demonstrates reclamation before the next connection.
  void capacity_and_metadata_are_refused_before_large_allocation() throws Exception {
    int reserved = 32 * 1024 * 1024;
    var budget = new PacketBudget(reserved * 3L);
    var data = Base64.getEncoder().encodeToString(new byte[SegmentedMessages.CHUNK_BYTES]);
    try (var first = new SegmentedMessages(new Wire(), budget, () -> {});
        var second = new SegmentedMessages(new Wire(), budget, () -> {})) {
      var part =
          new MessageSegment(
              "transport.segment",
              1,
              UUID.randomUUID().toString(),
              1,
              512,
              0,
              reserved,
              "0".repeat(64),
              data);
      first.receive(part);
      assertThrows(IllegalArgumentException.class, () -> second.receive(part));
      first.close();
      assertTrue(second.receive(part).isEmpty());
    }
    assertEquals(0, budget.heldBytes());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MessageSegment(
                "transport.segment",
                1,
                UUID.randomUUID().toString(),
                1,
                1,
                1,
                1,
                "0".repeat(64),
                "QQ=="));
    assertThrows(IllegalArgumentException.class, () -> SegmentedMessages.utf8Length("\ud800"));
  }
}
