package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentTool;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reads the speaker's Inbox; command approvals retain their human read receipt and alert. */
public final class InboxTool implements AgentTool {

  public static final String NAME = "inbox_read";

  private static final ToolSchema SCHEMA =
      ToolSchema.from(
          NAME,
          "Read the unread items in the user-inbox of the person you are talking to: what"
              + " scheduled and event-started runs left for them. Reading marks ordinary items read;"
              + " command approvals remain unread until the person reads or answers them.",
          Map.of("type", "object", "properties", Map.of(), "required", List.of()));

  private final Inbox inbox;
  private final String handle;

  public InboxTool(Inbox inbox, String handle) {
    this.inbox = Objects.requireNonNull(inbox, "inbox");
    this.handle = Objects.requireNonNull(handle, "handle");
  }

  @Override
  public ToolSchema schema() {
    return SCHEMA;
  }

  /** How many unread items one answer shows; one more is asked for, to know whether more remain. */
  static final int SHOWN = 20;

  @Override
  public String run(String argumentsJson, Home home) {
    List<InboxItem> asked = inbox.unreadForAgent(handle, 0, SHOWN + 1);
    if (asked.isEmpty()) {
      return "The user-inbox has no unread ordinary items. Command approvals stay in the person's Inbox.";
    }
    boolean more = asked.size() > SHOWN;
    List<InboxItem> unread = more ? asked.subList(0, SHOWN) : asked;
    StringBuilder out = new StringBuilder();
    for (InboxItem item : unread) {
      boolean isRun = InboxStore.KIND_RUN.equals(item.kind());
      out.append("- ")
          .append(item.arrivedAt())
          .append(" · ")
          .append(isRun ? item.ending() : item.kind().replace('.', ' '));
      if (item.conversation() != null) {
        out.append(" · conversation ").append(item.conversation());
      }
      out.append('\n').append("  ").append(item.answer().replace("\n", "\n  ")).append('\n');
    }
    // The repository excludes human command approvals before pagination. Consuming ordinary
    // deliveries cannot silence an approval alert or let pending requests starve older results.
    inbox.read(handle, unread.stream().map(InboxItem::id).toList());
    // The state it left: what it just marked read, and whether anything is still unread.
    out.append(
        unread.size() == 1
            ? "This one is marked read now"
            : "These " + unread.size() + " are marked read now");
    return out.append(more ? "; more are still unread — call inbox_read again for them." : ".")
        .toString();
  }
}
