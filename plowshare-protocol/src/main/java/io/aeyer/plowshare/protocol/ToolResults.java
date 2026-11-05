package io.aeyer.plowshare.protocol;

import java.util.UUID;

/** Owner-scoped retained invocation read; no execution or binary disclosure is performed. */
public final class ToolResults {
  private ToolResults() {}

  public record Read(String project, UUID invocation) {
    public Read {
      RelayPort.identity(project);
      java.util.Objects.requireNonNull(invocation);
    }
  }

  public record Receipt(
      UUID id,
      String state,
      String text,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          ToolContent.Body content) {
    public Receipt {
      java.util.Objects.requireNonNull(id);
      if (!java.util.Set.of("COMPLETED", "REJECTED", "UNKNOWN").contains(state)
          || text == null
          || text.length() > 16384
          || text.indexOf('\0') >= 0
          || content == null && text.isBlank()
          || content != null
              && (!content.text().equals(text)
                  || !"COMPLETED".equals(state) && !content.images().isEmpty()))
        throw new IllegalArgumentException("Invalid retained tool result");
    }
  }
}
