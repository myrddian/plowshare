package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.server.faults.CallerFault;

/**
 * The model-call total an {@code agent.run} names for an existing conversation, or nothing (ADR
 * 0069).
 *
 * <h2>What is decided here and what is not</h2>
 *
 * <p>Only what the body alone can show is wrong: the field arriving where there is no existing
 * conversation for it to raise, and a number that is not a budget at all. Both are refused before
 * the caller, the agent or the conversation is read, so a body that is wrong in this way is told so
 * whatever else it names. What depends on the conversation — a total below what it has already
 * spent, or a number for an allowance with no ceiling — is {@code Turn.grant}'s, the one rule
 * {@code conversation.resume} applies as well, and is not restated here.
 *
 * <p><b>Not beside {@code newConversation}, and not on a standalone run.</b> Opening a conversation
 * already takes its own {@code maxModelCalls} through {@code conversation.open}, and a standalone
 * run spends its agent's own allowance; a raise in either place would be a second, unaudited way of
 * choosing a first budget rather than a person raising an existing one.
 */
public final class RequestedAllowanceRaise {

  private RequestedAllowanceRaise() {}

  /**
   * The total, validated, or {@code null} for a body that names none.
   *
   * @param maxModelCalls the field as bound, possibly null
   * @param conversation the existing conversation the run names, or null
   * @param newConversation whether the run asks for a fresh conversation, possibly null
   * @return {@code maxModelCalls}, unchanged, once it is known to be admissible here
   * @throws CallerFault if the field names no existing conversation or is not positive
   */
  public static Integer in(Integer maxModelCalls, String conversation, Boolean newConversation) {
    if (maxModelCalls == null) {
      return null;
    }
    if (Boolean.TRUE.equals(newConversation) || conversation == null) {
      throw new CallerFault(
          "'maxModelCalls' on agent.run raises an existing conversation's allowance, so it"
              + " needs a 'conversation' and cannot be combined with 'newConversation'. A new"
              + " conversation takes its budget when it is opened, and a standalone run spends"
              + " its agent's own. Nothing was started.");
    }
    if (maxModelCalls < 1) {
      throw new CallerFault(
          "'maxModelCalls' is the conversation's new total and needs at least one model"
              + " call; got "
              + maxModelCalls
              + ". Nothing was started.");
    }
    return maxModelCalls;
  }
}
