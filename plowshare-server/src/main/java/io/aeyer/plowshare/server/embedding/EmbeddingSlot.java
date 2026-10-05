package io.aeyer.plowshare.server.embedding;

/** Permanent capability-selected slots. Content and query text never select a slot. */
public enum EmbeddingSlot {
  CODE("code"),
  PROSE("prose");
  private final String stored;

  EmbeddingSlot(String stored) {
    this.stored = stored;
  }

  public String stored() {
    return stored;
  }
}
