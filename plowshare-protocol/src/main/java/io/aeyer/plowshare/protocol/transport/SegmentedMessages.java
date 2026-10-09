package io.aeyer.plowshare.protocol.transport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Connection-scoped assembly and stop-and-wait packet sending. A credit releases the next packet,
 * never a mutation retry. Disconnect, expiration or malformed packets terminate this instance.
 * Complete messages cross this boundary only after strict UTF-8 and SHA-256 verification.
 */
public final class SegmentedMessages implements AutoCloseable {
  public static final String SUBPROTOCOL = "plowshare-segments-v1";
  public static final int CHUNK_BYTES = 64 * 1024;
  public static final int MAX_PACKET_BYTES = 128 * 1024;

  /** Allows a maximally escaped 50 MiB Relay TEXT value plus the ordinary message envelope. */
  public static final int MAX_MESSAGE_BYTES = 320 * 1024 * 1024;

  public static final int MAX_CONNECTION_BYTES = 2 * MAX_MESSAGE_BYTES;
  public static final int MAX_TRANSFERS = 4;
  public static final int MAX_TOMBSTONES = 4096;
  public static final Duration IDLE_TIMEOUT = Duration.ofSeconds(15);
  public static final Duration TRANSFER_TIMEOUT = Duration.ofSeconds(60);
  public static final PacketBudget PROCESS_BUDGET = new PacketBudget(2L * 1024 * 1024 * 1024);
  private static final ScheduledExecutorService CLOCK =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon().name("packet-expiry").factory());

  private final java.util.function.LongSupplier clock;
  private final PacketWire wire;
  private final PacketBudget budget;
  private final Runnable expired;
  private final Object sender = new Object();
  private final Map<String, Assembly> receiving = new HashMap<>();
  private final Set<String> completed = new HashSet<>();
  private final ScheduledFuture<?> timer;
  private long held;
  private boolean closed;
  private String sending;
  private int expected;
  private int credited;

  private static final int TOMBSTONE_BYTES = 256;

  private static final class Assembly {
    final MessageSegment first;
    final byte[] bytes;
    final BitSet ranges = new BitSet();
    final long started;
    long touched;

    Assembly(MessageSegment first, long now) {
      this.first = first;
      started = now;
      touched = now;
      bytes = new byte[first.totalBytes()];
    }
  }

  public SegmentedMessages(PacketWire wire, PacketBudget budget, Runnable expired) {
    this(wire, budget, expired, System::nanoTime);
  }

  SegmentedMessages(
      PacketWire wire,
      PacketBudget budget,
      Runnable expired,
      java.util.function.LongSupplier clock) {
    this.clock = java.util.Objects.requireNonNull(clock);
    this.wire = java.util.Objects.requireNonNull(wire);
    this.budget = java.util.Objects.requireNonNull(budget);
    this.expired = java.util.Objects.requireNonNull(expired);
    timer = CLOCK.scheduleWithFixedDelay(this::expire, 1, 1, TimeUnit.SECONDS);
  }

  /** Refuses oversize messages before a byte is sent; at most one data packet is in flight. */
  public void send(String message) throws IOException {
    int length = utf8Length(message);
    synchronized (this) {
      checkOpen();
      reserve(length);
    }
    try {
      synchronized (sender) {
        synchronized (this) {
          checkOpen();
        }
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        String id = UUID.randomUUID().toString();
        long started = clock.getAsLong();
        try {
          String hash = hash(bytes);
          int count = (bytes.length + CHUNK_BYTES - 1) / CHUNK_BYTES;
          synchronized (this) {
            sending = id;
            credited = 0;
          }
          for (int number = 1; number <= count; number++) {
            int offset = (number - 1) * CHUNK_BYTES;
            String data =
                Base64.getEncoder()
                    .encodeToString(
                        Arrays.copyOfRange(
                            bytes, offset, Math.min(bytes.length, offset + CHUNK_BYTES)));
            synchronized (this) {
              checkOpen();
              expected = number;
            }
            wire.segment(
                new MessageSegment(
                    "transport.segment", 1, id, number, count, offset, bytes.length, hash, data));
            long idle = clock.getAsLong();
            synchronized (this) {
              while (credited < number) {
                checkOpen();
                if (clock.getAsLong() - idle >= IDLE_TIMEOUT.toNanos()
                    || clock.getAsLong() - started >= TRANSFER_TIMEOUT.toNanos())
                  throw new IOException("packet credit deadline expired");
                try {
                  wait(100);
                } catch (InterruptedException stopping) {
                  Thread.currentThread().interrupt();
                  throw new IOException("packet send interrupted", stopping);
                }
              }
            }
          }
        } catch (IOException | RuntimeException failure) {
          close();
          expired.run();
          throw failure;
        } finally {
          synchronized (this) {
            sending = null;
          }
        }
      }
    } finally {
      synchronized (this) {
        release(length);
      }
    }
  }

  public static int utf8Length(String message) {
    return utf8Length((CharSequence) message);
  }

  // Count before allocating encoded bytes; accepting a character view keeps this preflight
  // independent of the caller's text-storage representation.
  static int utf8Length(CharSequence message) {
    int bytes = 0;
    for (int i = 0; i < message.length(); i++) {
      char c = message.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (++i >= message.length() || !Character.isLowSurrogate(message.charAt(i)))
          throw new IllegalArgumentException("invalid Unicode");
        bytes += 4;
      } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("invalid Unicode");
      else bytes += c < 128 ? 1 : c < 2048 ? 2 : 3;
      if (bytes > MAX_MESSAGE_BYTES)
        throw new IllegalArgumentException("encoded message exceeds transport allowance");
    }
    if (bytes == 0) throw new IllegalArgumentException("empty message");
    return bytes;
  }

  /** Accepts an authenticated connection's packet. No complete value means no domain dispatch. */
  public Optional<String> receive(MessageSegment packet) throws IOException {
    String result = null;
    synchronized (this) {
      checkOpen();
      if (completed.contains(packet.transferId()))
        throw new IllegalArgumentException("completed transfer identity reused");
      Assembly assembly = receiving.get(packet.transferId());
      if (assembly == null) {
        if (receiving.size() >= MAX_TRANSFERS
            || completed.size() + receiving.size() >= MAX_TOMBSTONES)
          throw new IllegalArgumentException("transfer capacity exceeded");
        reserve(packet.totalBytes());
        try {
          assembly = new Assembly(packet, clock.getAsLong());
        } catch (RuntimeException | Error failure) {
          release(packet.totalBytes());
          throw failure;
        }
        receiving.put(packet.transferId(), assembly);
      }
      MessageSegment first = assembly.first;
      if (packet.totalBytes() != first.totalBytes()
          || packet.segmentCount() != first.segmentCount()
          || !packet.sha256().equals(first.sha256()))
        throw new IllegalArgumentException("conflicting transfer metadata");
      byte[] range = Base64.getDecoder().decode(packet.data());
      if (assembly.ranges.get(packet.segmentNumber())) {
        for (int i = 0; i < range.length; i++)
          if (assembly.bytes[packet.byteOffset() + i] != range[i])
            throw new IllegalArgumentException("conflicting duplicate range");
      } else {
        System.arraycopy(range, 0, assembly.bytes, packet.byteOffset(), range.length);
        assembly.ranges.set(packet.segmentNumber());
      }
      assembly.touched = clock.getAsLong();
      if (assembly.ranges.cardinality() == packet.segmentCount()) {
        if (!hash(assembly.bytes).equals(first.sha256()))
          throw new IllegalArgumentException("transfer hash mismatch");
        try {
          result =
              StandardCharsets.UTF_8
                  .newDecoder()
                  .onMalformedInput(CodingErrorAction.REPORT)
                  .onUnmappableCharacter(CodingErrorAction.REPORT)
                  .decode(ByteBuffer.wrap(assembly.bytes))
                  .toString();
        } catch (CharacterCodingException invalid) {
          throw new IllegalArgumentException("invalid message UTF-8", invalid);
        }
        receiving.remove(packet.transferId());
        release(packet.totalBytes());
        reserve(TOMBSTONE_BYTES);
        completed.add(packet.transferId());
      }
    }
    wire.credit(
        new SegmentCredit("transport.credit", 1, packet.transferId(), packet.segmentNumber()));
    return Optional.ofNullable(result);
  }

  public synchronized void credit(SegmentCredit credit) throws IOException {
    checkOpen();
    if (!credit.transferId().equals(sending)
        || credit.segmentNumber() > expected
        || credit.segmentNumber() < credited)
      throw new IllegalArgumentException("unexpected packet credit");
    credited = credit.segmentNumber();
    notifyAll();
  }

  private void reserve(int bytes) {
    if (bytes > MAX_CONNECTION_BYTES - held)
      throw new IllegalArgumentException("connection packet capacity exceeded");
    budget.acquire(bytes * 3L); // Includes byte buffers and UTF-8/JSON conversion copies.
    held += bytes;
  }

  private void release(int bytes) {
    held -= bytes;
    budget.release(bytes * 3L);
  }

  private void checkOpen() throws IOException {
    if (closed) throw new IOException("packet connection closed");
  }

  private static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 unavailable", unavailable);
    }
  }

  void expire() {
    boolean stale;
    synchronized (this) {
      long now = clock.getAsLong();
      stale =
          !closed
              && receiving.values().stream()
                  .anyMatch(
                      a ->
                          now - a.started >= TRANSFER_TIMEOUT.toNanos()
                              || now - a.touched >= IDLE_TIMEOUT.toNanos());
    }
    if (stale) {
      close();
      expired.run();
    }
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    timer.cancel(false);
    receiving.values().forEach(a -> release(a.first.totalBytes()));
    receiving.clear();
    release(completed.size() * TOMBSTONE_BYTES);
    completed.clear();
    notifyAll();
  }
}
