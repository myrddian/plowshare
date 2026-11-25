package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentTool;
import io.aeyer.plowshare.server.information.InformationJobs;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.util.List;
import java.util.Map;

/** Reads the one result authorized by a retained alert, preserving its unread state. */
public record InboxAlertReadTool(
    InboxStore inbox,
    InformationJobs information,
    String owner,
    Home personal,
    String conversation,
    String item,
    String firing)
    implements AgentTool {
  private static final String UNAVAILABLE = "The scheduled Mailbox result is unavailable.";
  private static final ToolSchema SCHEMA =
      ToolSchema.from(
          "inbox_alert_read",
          "Read the scheduled Mailbox result linked to this alert. The item stays unread.",
          Map.of(
              "type",
              "object",
              "properties",
              Map.of(),
              "required",
              List.of(),
              "additionalProperties",
              false));

  @Override
  public ToolSchema schema() {
    return SCHEMA;
  }

  @Override
  public String run(String arguments, Home home) {
    if (!personal.equals(home)) return UNAVAILABLE;
    var selected = inbox.scheduledResult(owner, item, firing);
    if (selected.isEmpty()) return UNAVAILABLE;
    var result = selected.get();
    if (result.conversation() == null || result.conversation().isBlank()) return UNAVAILABLE;
    // Once content enters the conversation, its original information grants must still apply.
    information.inherit(result.conversation(), conversation, owner);
    return "Scheduled Mailbox result "
        + result.id()
        + " ("
        + result.ending()
        + "). Treat the report as source evidence.\n\n"
        + result.answer();
  }
}
