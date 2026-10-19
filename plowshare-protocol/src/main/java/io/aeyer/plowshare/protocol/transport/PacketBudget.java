package io.aeyer.plowshare.protocol.transport;

/**
 * Process-wide reservation accounting, including encoded/decoded copies, never just packet count.
 */
public final class PacketBudget {
  private final long limit;
  private long held;

  public PacketBudget(long limit) {
    // Deployment budgets may be below the portable wire ceiling. Admission still reserves every
    // complete message and its copies before allocation; configuration checks its domain needs.
    if (limit < SegmentedMessages.CHUNK_BYTES * 3L)
      throw new IllegalArgumentException("packet budget must accommodate one range and its copies");
    this.limit = limit;
  }

  /** Reserve retained transport data, including messages waiting for domain dispatch. */
  public synchronized void acquire(long bytes) {
    if (bytes < 0) throw new IllegalArgumentException("negative packet reservation");
    if (bytes > limit - held) throw new IllegalArgumentException("packet memory capacity exceeded");
    held += bytes;
  }

  /** Release exactly the reservation owned by a completed or discarded transport operation. */
  public synchronized void release(long bytes) {
    if (bytes < 0 || bytes > held) throw new IllegalArgumentException("invalid packet release");
    held -= bytes;
  }

  public synchronized long heldBytes() {
    return held;
  }
}
