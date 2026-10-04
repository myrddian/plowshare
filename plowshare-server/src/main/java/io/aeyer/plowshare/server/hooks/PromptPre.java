package io.aeyer.plowshare.server.hooks;

import java.util.List;

/** What {@code prompt.pre} produced: the additions, and the record of every decision. */
public record PromptPre(List<Addition> additions, List<HookRecord> records) {

  public static final PromptPre NOTHING = new PromptPre(List.of(), List.of());

  public PromptPre {
    additions = List.copyOf(additions);
    records = List.copyOf(records);
  }
}
