package io.aeyer.plowshare.server.hooks;

import java.util.List;
import java.util.Objects;

/** The reply as it will be stored and shown, and the record of every decision. */
public record PromptPost(String reply, List<HookRecord> records) {

  public PromptPost {
    Objects.requireNonNull(reply, "reply");
    records = List.copyOf(records);
  }

  public static PromptPost untouched(String reply) {
    return new PromptPost(reply, List.of());
  }
}
