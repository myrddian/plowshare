package io.aeyer.plowshare.sdk;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.aeyer.plowshare.protocol.Home;

/**
 * Atomic get-or-open in the authenticated account's external namespace. Null options are omitted;
 * creation choices never change an existing alias's limits or lifecycle. Locators are preserved
 * exactly and never normalized, interpreted as internal IDs or treated as grants.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConversationOpenRequestEx(
    String project,
    String source,
    String externalConversation,
    Integer maxModelCalls,
    Integer maxTurns,
    Boolean noTurnCap,
    Boolean noBudget) {
  public ConversationOpenRequestEx {
    locator(project, source, externalConversation);
    limits(maxModelCalls, maxTurns, noTurnCap);
    if (Boolean.TRUE.equals(noBudget) && maxModelCalls != null)
      throw new IllegalArgumentException("Choose a budget total or noBudget");
  }

  public ConversationOpenRequestEx(String project, String source, String externalConversation) {
    this(project, source, externalConversation, null, null, null, null);
  }

  static void locator(String project, String source, String externalConversation) {
    Home.of(project);
    if (edgeWhitespace(project))
      throw new IllegalArgumentException("project must not have edge whitespace");
    if (source == null
        || source.length() > 128
        || !source.matches("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*"))
      throw new IllegalArgumentException("Invalid external conversation source");
    if (externalConversation == null || !externalConversation.matches("[\\x21-\\x7e]{1,256}"))
      throw new IllegalArgumentException("Invalid external conversation identifier");
  }

  static void limits(Integer calls, Integer turns, Boolean noTurnCap) {
    if (calls != null && calls < 1
        || turns != null && (turns < 1 || Boolean.TRUE.equals(noTurnCap)))
      throw new IllegalArgumentException("Invalid or conflicting conversation limits");
  }

  static void nonblank(String value, String field) {
    if (value == null
        || value.length() > 50 * 1024 * 1024
        || value.codePoints().allMatch(ConversationOpenRequestEx::whitespace))
      throw new IllegalArgumentException("A bounded nonblank " + field + " is required");
  }

  /** ECMAScript trim is the canonical SDK boundary; Java strip has a different Unicode set. */
  static boolean whitespace(int code) {
    return code >= 0x09 && code <= 0x0d
        || code == 0x20
        || code == 0xa0
        || code == 0x1680
        || code >= 0x2000 && code <= 0x200a
        || code == 0x2028
        || code == 0x2029
        || code == 0x202f
        || code == 0x205f
        || code == 0x3000
        || code == 0xfeff;
  }

  private static boolean edgeWhitespace(String value) {
    return !value.isEmpty()
        && (whitespace(value.codePointAt(0)) || whitespace(value.codePointBefore(value.length())));
  }
}
