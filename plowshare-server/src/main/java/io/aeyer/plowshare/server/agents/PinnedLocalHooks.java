package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.LocalHookSetStore;
import io.aeyer.plowshare.server.hooks.HookRecord;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Snapshot at open: read, bound, hash, store, pin (spec 2026-09-30-local-hooks-are-served decisions
 * 3, 4 and 7).
 *
 * <ul>
 *   <li><b>Inherits:</b> a log naming {@link LogStages.LogOpened#inherits} is pinned to that log's
 *       set and reads nothing: the same person's work, and a re-read could see other files
 *       mid-tree.
 *   <li><b>Reads:</b> a log naming a live session that roots its project, held by the log's owner,
 *       whose listener and file channel are both signed in as that owner, before the read and still
 *       after it. {@link ChannelHooks} reads the set whole under decision 8's bounds; the set is
 *       stored by hash and the log is pinned to it once.
 *   <li><b>Has none, silently:</b> no session; the global tier; a session not connected or not
 *       rooting this project; a session either of whose sockets is not signed in as the log's
 *       owner, or a log nobody owns. A closed session's presence is gone, so it cannot be told from
 *       an unrooted one.
 *   <li><b>Has none, and says why:</b> a session that went, refused or broke a bound during the
 *       read; an archive that could not store or pin.
 * </ul>
 *
 * <p>Runs where {@code log.open} runs: after the opener's row committed, outside any transaction.
 */
public final class PinnedLocalHooks implements LocalHooks {

  private static final Logger log = LoggerFactory.getLogger(PinnedLocalHooks.class);

  private final Function<String, ChannelHooks.Served> serve;
  private final Predicate<String> live;
  private final BiPredicate<String, String> roots;
  private final Accounts accounts;
  private final ConversationStore conversations;
  private final LocalHookSetStore sets;

  /**
   * Whose a session is, three ways. A caller may name any live session, so only one held by the
   * log's owner on every count lends it hooks: another account's code never runs in this log
   * (Enzo's decision of 2026-09-30).
   *
   * @param bound the account the session is held by: {@code SessionRegistry.accountOf}
   * @param listener the account its listener signed in as: {@code SpeakerHandles.handleOf}
   * @param provider the account its file channel was opened as, the socket the hooks are read over:
   *     {@code FileChannelHandler.handleOf}
   */
  public record Accounts(
      Function<String, Optional<String>> bound,
      Function<String, Optional<String>> listener,
      Function<String, Optional<String>> provider) {

    public Accounts {
      Objects.requireNonNull(bound, "bound");
      Objects.requireNonNull(listener, "listener");
      Objects.requireNonNull(provider, "provider");
    }

    /** One lookup for all three: a fixture's. */
    public static Accounts all(Function<String, Optional<String>> each) {
      return new Accounts(each, each, each);
    }
  }

  /**
   * @param serve a session's {@code .plowshare/hooks/}: {@code new ChannelHooks(channel, s).read()}
   * @param live whether a session has a file channel attached now
   * @param roots whether a live session roots the named project: {@code (project, session)}
   * @param accounts whose the session is: all three must be the log's owner
   */
  public PinnedLocalHooks(
      Function<String, ChannelHooks.Served> serve,
      Predicate<String> live,
      BiPredicate<String, String> roots,
      Accounts accounts,
      ConversationStore conversations,
      LocalHookSetStore sets) {
    this.serve = Objects.requireNonNull(serve, "serve");
    this.live = Objects.requireNonNull(live, "live");
    this.roots = Objects.requireNonNull(roots, "roots");
    this.accounts = Objects.requireNonNull(accounts, "accounts");
    this.conversations = Objects.requireNonNull(conversations, "conversations");
    this.sets = Objects.requireNonNull(sets, "sets");
  }

  @Override
  public List<HookRecord> pin(LogStages.LogOpened opened) {
    try {
      return pinOrSay(opened);
    } catch (RuntimeException failed) {
      log.warn(
          "log {}: its local hooks could not be snapshotted, so it has none. Reason: {}",
          opened.log(),
          JobRuntime.describe(failed));
      return List.of(LocalHooks.unsnapshotted(failed));
    }
  }

  private List<HookRecord> pinOrSay(LogStages.LogOpened opened) {
    if (opened.inherits() != null) {
      conversations
          .localHooksOf(opened.inherits())
          .ifPresent(hash -> conversations.pinLocalHooks(opened.log(), hash));
      return List.of();
    }
    String session = opened.session();
    if (session == null
        || opened.home().isGlobal()
        || !live.test(session)
        || !roots.test(opened.home().project(), session)) {
      return List.of();
    }
    Optional<String> owner = conversations.ownerOf(opened.log());
    if (owner.isEmpty() || !heldBy(session, owner, true)) {
      return List.of();
    }
    ChannelHooks.Served served = serve.apply(session);
    if (!heldBy(session, owner, false)) {
      // The read takes up to its deadline, and the file channel can drop and be taken
      // meanwhile: what came back is only this owner's if the session and its file channel
      // still are. Otherwise nothing is pinned and nothing said, as for any other account.
      return List.of();
    }
    if (served.failed()) {
      return List.of(LocalHooks.unpinned("this log has no local hooks: " + served.unreadable()));
    }
    if (served.files().isEmpty()) {
      return List.of();
    }
    conversations.pinLocalHooks(opened.log(), sets.remember(served.files()));
    return List.of();
  }

  /**
   * Whether {@code session} is held by {@code owner} and its file channel signed in as them; with
   * {@code andListener}, its listener too. A socket opened as nobody lends no one's code.
   */
  private boolean heldBy(String session, Optional<String> owner, boolean andListener) {
    return owner.equals(asked(accounts.bound(), session, "bound"))
        && owner.equals(asked(accounts.provider(), session, "provider"))
        && (!andListener || owner.equals(asked(accounts.listener(), session, "listener")));
  }

  private static Optional<String> asked(
      Function<String, Optional<String>> lookup, String session, String which) {
    return Objects.requireNonNull(lookup.apply(session), which);
  }
}
