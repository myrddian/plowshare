package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.server.faults.CallerFault;

/** A messaging request failed validation, authorization or its retained lifecycle contract. */
public final class MessageRefused extends CallerFault {
  public MessageRefused(String message) {
    super(message);
  }
}
