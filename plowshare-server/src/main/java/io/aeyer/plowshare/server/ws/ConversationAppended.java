package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.LogGrowth;
import io.aeyer.plowshare.server.agents.Watchers;
import io.aeyer.plowshare.server.archive.EntryStore;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pushes {@code conversation.appended} — the conversation and its highest ordinal, no content — to
 * every session following a conversation whose log grew. Best-effort: a push that cannot be made is
 * logged and dropped, and the next push or a replay catches the client up.
 */
public final class ConversationAppended implements LogGrowth {

  private static final Logger log = LoggerFactory.getLogger(ConversationAppended.class);

  /** The push's {@code kind}, which a client tells it from every other bare push by. */
  public static final String KIND = "conversation.appended";

  private final Watchers watchers;
  private final EntryStore entries;
  private final SessionPushes pushes;

  public ConversationAppended(Watchers watchers, EntryStore entries, SessionPushes pushes) {
    this.watchers = Objects.requireNonNull(watchers, "watchers");
    this.entries = Objects.requireNonNull(entries, "entries");
    this.pushes = Objects.requireNonNull(pushes, "pushes");
  }

  @Override
  public void appended(String conversationId) {
    if (conversationId == null) {
      return;
    }
    // Asked first and in memory, so a conversation nobody is showing costs nothing.
    Set<String> followers = watchers.followersOf(conversationId);
    if (followers.isEmpty()) {
      return;
    }
    try {
      int through = entries.through(conversationId);
      var body = new io.aeyer.plowshare.protocol.ConversationGrowth(conversationId, through);
      for (String session : followers) {
        pushes.tell(session, body);
      }
    } catch (RuntimeException notTold) {
      // JobRuntime.describe and not toString(): the first line only, the house rule for a
      // database failure, whose second line can quote a row.
      log.debug(
          "conversation {}: its followers could not be told the log grew; the next"
              + " push or a replay catches them up. Reason: {}",
          conversationId,
          JobRuntime.describe(notTold));
    }
  }
}
