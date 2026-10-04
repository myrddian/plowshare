package io.aeyer.plowshare.server.delivery;

import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.archive.ArchiveException;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.hooks.Handover;
import java.util.Objects;

/**
 * The shared last mile from durable work to a person: an idle human conversation first, then the
 * owning account's inbox. The caller owns persistence, retries and the words being delivered.
 */
public final class PersonDelivery {

  public enum Result {
    DELIVERED,
    BUSY,
    NOWHERE
  }

  /** What one delivery did, and where: a {@link Handover} destination. */
  public record Sent(Result result, String destination) {}

  public interface Voice {
    boolean isSpeaking(String conversation);

    void speak(String conversation, String agent, String text, Speaker speaker);
  }

  @FunctionalInterface
  public interface Inbox {
    /**
     * @param about the question the notice asks (V68), or null for news
     */
    void notify(String handle, String kind, String text, String about);

    default void notifyFromLog(
        String handle, String kind, String text, String about, String source) {
      notify(handle, kind, text, about);
    }
  }

  private final ConversationStore conversations;
  private final Voice voice;
  private final Inbox inbox;

  public PersonDelivery(ConversationStore conversations, Voice voice, Inbox inbox) {
    this.conversations = Objects.requireNonNull(conversations, "conversations");
    this.voice = Objects.requireNonNull(voice, "voice");
    this.inbox = Objects.requireNonNull(inbox, "inbox");
  }

  /**
   * Deliver once. A busy conversation is left to the caller's when-free drain.
   *
   * @param speaker who is delivering — {@code Delivery} names its run, {@code ApprovalDelivery}
   *     names its approval — so the log never reads a harness delivery back as the person's own
   */
  public Result deliver(
      String conversation, String agent, String handle, String kind, String text, Speaker speaker) {
    return deliver(conversation, agent, handle, kind, text, speaker, null);
  }

  /**
   * {@link #deliver(String, String, String, String, String, Speaker)}, for a question: should it
   * land in the inbox, its notice names {@code about}, and leaves the inbox once that question is
   * settled (V68). A conversation spoken into has nothing to settle.
   */
  public Result deliver(
      String conversation,
      String agent,
      String handle,
      String kind,
      String text,
      Speaker speaker,
      String about) {
    return send(conversation, agent, handle, kind, text, speaker, about).result();
  }

  /**
   * {@link #deliver}, saying where it went, for a {@code delivery.post} hook to be told. Its order
   * — the conversation, else the inbox, else nowhere — is repeated by {@link #routeFor} and must
   * change with it: routeFor is the intended destination, this the actual one, and a speak refused
   * here makes the two differ on purpose (ruling F8).
   */
  public Sent send(
      String conversation, String agent, String handle, String kind, String text, Speaker speaker) {
    return send(conversation, agent, handle, kind, text, speaker, null);
  }

  /**
   * {@link #send(String, String, String, String, String, Speaker)}, for a question {@code about}
   * names — null for news.
   */
  public Sent send(
      String conversation,
      String agent,
      String handle,
      String kind,
      String text,
      Speaker speaker,
      String about) {
    return sendFromLog(conversation, agent, handle, kind, text, speaker, about, null);
  }

  public Sent sendFromLog(
      String conversation,
      String agent,
      String handle,
      String kind,
      String text,
      Speaker speaker,
      String about,
      String source) {
    if (information != null && source != null) information.requireLog(source, handle);
    if (conversation != null && isTurn(conversation)) {
      if (voice.isSpeaking(conversation)) {
        return new Sent(Result.BUSY, Handover.CONVERSATION);
      }
      try {
        voice.speak(conversation, agent, text, speaker);
        return new Sent(Result.DELIVERED, Handover.CONVERSATION);
      } catch (Turn.Refused | ArchiveException refused) {
        // The durable caller decides whether and how to log this fallback.
      }
    }
    if (handle != null) {
      if (source == null) inbox.notify(handle, kind, text, about);
      else inbox.notifyFromLog(handle, kind, text, about, source);
      return new Sent(Result.DELIVERED, Handover.INBOX);
    }
    return new Sent(Result.NOWHERE, Handover.NOWHERE);
  }

  /**
   * Where {@link #send} will try first, for a {@code delivery.pre} hook told before it sends. A
   * refused speak then falls back to the inbox, so pre can name the intended destination and post
   * the actual one — kept, not reconciled by a guess (ruling F8, spec
   * 2026-09-28-hooks-reach-the-log §3).
   */
  public String routeFor(String conversation, String handle) {
    if (conversation != null && isTurn(conversation)) {
      return Handover.CONVERSATION;
    }
    return handle != null ? Handover.INBOX : Handover.NOWHERE;
  }

  private io.aeyer.plowshare.server.information.InformationJobs information;

  public void useInformationInputs(
      io.aeyer.plowshare.server.information.InformationJobs information) {
    this.information = information;
  }

  private boolean isTurn(String conversation) {
    return conversations
        .find(conversation)
        .map(record -> record.origin() == Origin.TURN)
        .orElse(false);
  }
}
