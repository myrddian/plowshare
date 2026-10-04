package io.aeyer.plowshare.server.hooks;

import java.util.Objects;

/**
 * A result on its way from a run's log to where it is delivered, as {@code delivery.*} is shown it
 * (spec 2026-09-28-hooks-reach-the-log §3). The source log is the context's.
 *
 * <p>{@code destination} is the INTENDED one at {@code delivery.pre} and the ACTUAL one at {@code
 * delivery.post}: a person's conversation that refuses the result sends it to the inbox, so pre
 * says {@link #CONVERSATION} and post says {@link #INBOX}. Each says what was known when it ran;
 * nothing guesses the fallback ahead of time (ruling F8).
 */
public record Handover(String destination, String text) {

  public static final String PARENT = "parent";
  public static final String CONVERSATION = "conversation";
  public static final String INBOX = "inbox";
  public static final String NOWHERE = "nowhere";

  public Handover {
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(text, "text");
  }
}
