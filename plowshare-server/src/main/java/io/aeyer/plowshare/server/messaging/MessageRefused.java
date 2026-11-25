package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.server.faults.CallerFault;

/** A messaging request failed validation, authorization or its retained lifecycle contract. */
public final class MessageRefused extends CallerFault {
  public enum Code {
    INVALID_ARGUMENT,
    SOURCE_UNAVAILABLE,
    SEND_NOT_GRANTED,
    TARGET_UNAVAILABLE,
    ROUTE_UNKNOWN,
    ROUTE_DENIED,
    INSTANCE_ENDED,
    QUEUE_FULL,
    INSTANCE_LIMIT,
    MESSAGING_UNAVAILABLE,
    MESSAGE_REFUSED
  }

  private final Code code;

  public MessageRefused(String message) {
    this(Code.MESSAGE_REFUSED, message);
  }

  public MessageRefused(Code code, String message) {
    super(message);
    this.code = code;
  }

  public Code code() {
    return code;
  }
}
