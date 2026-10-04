package io.aeyer.plowshare.server.hooks;

import java.util.List;
import java.util.Objects;

/**
 * What a notifying stage decided: each notice goes to the log owner's inbox (spec
 * 2026-09-28-hooks-reach-the-log decision 8), and the record of every decision.
 */
public record Notified(List<Notice> notices, List<HookRecord> records) {

  public static final Notified NOTHING = new Notified(List.of(), List.of());

  /** One {@code { notify }}, and which hook asked. */
  public record Notice(String hook, String text) {
    public Notice {
      Objects.requireNonNull(hook, "hook");
      Objects.requireNonNull(text, "text");
    }
  }

  public Notified {
    notices = List.copyOf(notices);
    records = List.copyOf(records);
  }
}
