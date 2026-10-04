package io.aeyer.plowshare.server.hooks;

import java.util.List;
import java.util.Objects;

/** The tool result as the model will see it, and the record of every decision. */
public record ToolPost(String result, List<HookRecord> records) {

  public ToolPost {
    Objects.requireNonNull(result, "result");
    records = List.copyOf(records);
  }

  public static ToolPost untouched(String result) {
    return new ToolPost(result, List.of());
  }
}
