package io.aeyer.plowshare.server.hooks;

import java.util.Objects;

/** Text a {@code prompt.pre} hook adds after the utterance, and whether it stays. */
public record Addition(String hook, String text, Mode mode) {
  public Addition {
    Objects.requireNonNull(hook, "hook");
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(mode, "mode");
  }
}
