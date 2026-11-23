package io.aeyer.plowshare.server.approvals;

import java.util.Objects;

/** Durable evidence about the effect of a retained call, independent of its result body. */
public record ToolEffectReceipt(Kind kind, String providerRequestId) {
  public enum Kind {
    PREFLIGHT_REFUSED,
    FILE_SENT,
    FILE_REPLY_TIMEOUT,
    FILE_EFFECT_CONFIRMED,
    FILE_EFFECT_VERIFIED,
    FILE_EFFECT_UNCHANGED,
    FILE_REPLY_UNVERIFIED,
    FILE_DISPATCH_UNCERTAIN,
    FILE_LOCAL_CHANGED,
    FILE_LOCAL_UNCHANGED,
    FILE_LOCAL_UNVERIFIED
  }

  public enum Dispatch {
    NOT_DISPATCHED,
    SENT,
    MAY_HAVE_DISPATCHED,
    DISPATCHED
  }

  public enum Effect {
    NO_EFFECT,
    UNKNOWN,
    CONFIRMED
  }

  public ToolEffectReceipt {
    Objects.requireNonNull(kind);
    if (!remote(kind)) {
      if (providerRequestId != null) throw new IllegalArgumentException("No file request was sent");
    } else if (providerRequestId == null || !providerRequestId.matches("[a-zA-Z0-9_-]{1,128}")) {
      throw new IllegalArgumentException("Invalid file request identity");
    }
  }

  public Dispatch dispatch() {
    return switch (kind) {
      case PREFLIGHT_REFUSED -> Dispatch.NOT_DISPATCHED;
      case FILE_DISPATCH_UNCERTAIN -> Dispatch.MAY_HAVE_DISPATCHED;
      case FILE_LOCAL_CHANGED, FILE_LOCAL_UNCHANGED, FILE_LOCAL_UNVERIFIED -> Dispatch.DISPATCHED;
      default -> Dispatch.SENT;
    };
  }

  public Effect effect() {
    return switch (kind) {
      case PREFLIGHT_REFUSED, FILE_EFFECT_UNCHANGED, FILE_LOCAL_UNCHANGED -> Effect.NO_EFFECT;
      case FILE_SENT,
              FILE_REPLY_TIMEOUT,
              FILE_REPLY_UNVERIFIED,
              FILE_DISPATCH_UNCERTAIN,
              FILE_LOCAL_UNVERIFIED ->
          Effect.UNKNOWN;
      case FILE_EFFECT_CONFIRMED, FILE_EFFECT_VERIFIED, FILE_LOCAL_CHANGED -> Effect.CONFIRMED;
    };
  }

  private static boolean remote(Kind kind) {
    return switch (kind) {
      case FILE_SENT,
              FILE_REPLY_TIMEOUT,
              FILE_EFFECT_CONFIRMED,
              FILE_EFFECT_VERIFIED,
              FILE_EFFECT_UNCHANGED,
              FILE_REPLY_UNVERIFIED ->
          true;
      default -> false;
    };
  }
}
