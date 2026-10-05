package io.aeyer.plowshare.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A followed conversation grew through this ordinal; carries no private content. */
public record ConversationGrowth(String conversation, int through) implements ServerPush {
  public ConversationGrowth {
    if (conversation == null
        || conversation.isBlank()
        || conversation.length() > 1024
        || conversation
            .codePoints()
            .anyMatch(code -> Character.isISOControl(code) || code == 0x2028 || code == 0x2029)
        || through < 0) throw new IllegalArgumentException("invalid conversation position");
  }

  @JsonProperty
  public String kind() {
    return "conversation.appended";
  }
}
