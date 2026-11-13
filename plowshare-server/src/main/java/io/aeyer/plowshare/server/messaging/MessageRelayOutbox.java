package io.aeyer.plowshare.server.messaging;

/** Bounded owning snapshot capture and generation-fenced publication; never runs a model. */
public interface MessageRelayOutbox {
  boolean captureNext();

  boolean publishNext();
}
