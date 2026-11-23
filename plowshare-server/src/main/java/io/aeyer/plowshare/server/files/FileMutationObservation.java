package io.aeyer.plowshare.server.files;

/** Evidence from the original file request. Each observed file tool performs one mutation. */
public record FileMutationObservation(String requestId, Kind kind) {
  public enum Kind {
    ATTEMPTED,
    SENT,
    CONFIRMED,
    VERIFIED,
    UNCHANGED,
    REPLY_UNVERIFIED,
    LOCAL_CHANGED,
    LOCAL_UNCHANGED,
    LOCAL_UNVERIFIED
  }
}
