package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.LogStages;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.events.FiringRecord;
import io.aeyer.plowshare.server.events.FiringStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A project's board: a topic opened, a message posted, a harness note written — spec 2026-09-29
 * §4–§6. Each one that addresses a seat owes it a wake, in the same transaction as the message and
 * the seat it may create; the wakes are drained once that commits. Nothing here runs a model:
 * {@code SeatRunner} turns a wake into a turn.
 */
public final class Board {

  private static final Logger log = LoggerFactory.getLogger(Board.class);

  /** A board refusal: the message is a sentence for whoever asked. */
  public static final class Refused extends io.aeyer.plowshare.server.faults.CallerFault {

    private static final long serialVersionUID = 1L;

    public Refused(String message) {
      super(message);
    }
  }

  public record Open(
      Home home,
      String title,
      String label,
      String body,
      String account,
      String openerKind,
      String opener,
      String originConversation,
      Integer budget) {}

  public record Opened(BoardTopic topic, BoardMessage opening, List<WakeRules.Wake> woken) {}

  public record Post(
      String topic,
      String authorKind,
      String author,
      String conversation,
      Integer entry,
      String kind,
      String title,
      String body,
      String replyTo,
      List<String> mentions,
      boolean alert) {

    public Post {
      mentions = List.copyOf(mentions);
    }
  }

  public record Posted(BoardMessage message, List<WakeRules.Wake> woken, boolean alertRefused) {}

  /** A seat conversation created in a transaction, told to the log stages after it commits. */
  private record Seated(String conversation, Home home, String agent, boolean bot) {}

  private final BoardStore store;
  private final Function<String, SwarmDefinitions.SwarmDefinition> swarmFor;
  private final ConversationStore conversations;
  private final FiringStore firings;
  private final Consumer<String> drain;
  private final UnitOfWork work;
  private final IntSupplier closingReserve;
  private final Supplier<Instant> clock;
  private final List<Consumer<String>> closedListeners = new CopyOnWriteArrayList<>();

  /** Seat runners stop their in-flight wakes at the next step boundary when a topic closes. */
  public void whenClosed(Consumer<String> listener) {
    closedListeners.add(Objects.requireNonNull(listener, "listener"));
  }

  private volatile LogStages logStages = LogStages.NONE;
  private volatile IntSupplier maxDepth = () -> 2;
  private volatile Runnable notices = () -> {};
  private volatile java.util.function.Predicate<String> running = topic -> false;

  public void useRunning(java.util.function.Predicate<String> running) {
    this.running = running;
  }

  public void useMaxDepth(IntSupplier maxDepth) {
    this.maxDepth = maxDepth;
  }

  public void useNotices(Runnable notices) {
    this.notices = notices;
  }

  public Board(
      BoardStore store,
      Function<String, SwarmDefinitions.SwarmDefinition> swarmFor,
      ConversationStore conversations,
      FiringStore firings,
      Consumer<String> drain,
      UnitOfWork work,
      IntSupplier closingReserve,
      Supplier<Instant> clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.swarmFor = Objects.requireNonNull(swarmFor, "swarmFor");
    this.conversations = Objects.requireNonNull(conversations, "conversations");
    this.firings = Objects.requireNonNull(firings, "firings");
    this.drain = Objects.requireNonNull(drain, "drain");
    this.work = Objects.requireNonNull(work, "work");
    this.closingReserve = Objects.requireNonNull(closingReserve, "closingReserve");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** The log stages a seat's log opening passes. {@link LogStages#NONE} until wired. */
  public void useLogStages(LogStages logStages) {
    this.logStages = Objects.requireNonNull(logStages, "logStages");
  }

  /**
   * Opens a root topic: the opening message, the opener's seat when a definition opens it, and a
   * wake owed to every member. Its title and label are {@link #fold}ed onto one line and capped.
   *
   * @throws Refused for a global home, a swarm with no members, a blank field, or a budget under
   *     two model calls
   */
  public Opened open(Open request) {
    if (request.home() == null || request.home().isGlobal()) {
      throw new Refused("a board belongs to a project, and this conversation has none");
    }
    if (!Set.of(BoardTopic.BY_BOT, BoardTopic.BY_AGENT, BoardTopic.BY_PERSON)
        .contains(String.valueOf(request.openerKind()))) {
      throw new Refused(
          "a root topic is opened by an agent, bot or person, never by '"
              + request.openerKind()
              + "'; a member requests a child topic instead");
    }
    String title = folded(request.title(), "title", BoardTopic.TITLE_MAX);
    String label = folded(request.label(), "label", BoardTopic.LABEL_MAX);
    requireText(request.body(), "body");
    SwarmDefinitions.SwarmDefinition swarm = swarmFor.apply(request.home().project());
    if (swarm.members().isEmpty()) {
      throw new Refused("this project's swarm has no members to wake — " + swarm.why());
    }
    int total =
        request.budget() == null ? swarm.budget() : Math.min(request.budget(), swarm.budget());
    if (total < 2) {
      throw new Refused(
          "a topic's budget is at least 2 model calls — one for a member and"
              + " one kept for closing it; "
              + total
              + " was asked for");
    }
    int reserve = reserveOf(total, closingReserve.getAsInt());
    List<Seated> seated = new ArrayList<>();
    Set<String> targets = new LinkedHashSet<>();
    Opened opened =
        work.inTransaction(
            () -> {
              BoardTopic topic =
                  store.openRoot(
                      new BoardStore.NewTopic(
                          request.home().project(),
                          title,
                          label,
                          request.account(),
                          request.openerKind(),
                          request.opener(),
                          request.originConversation(),
                          total,
                          reserve));
              boolean byDefinition = !BoardTopic.BY_PERSON.equals(request.openerKind());
              BoardMessage opening =
                  store.post(
                      new BoardStore.NewMessage(
                          topic.id(),
                          null,
                          byDefinition ? BoardMessage.BY_OPENER : BoardMessage.BY_PERSON,
                          request.opener(),
                          request.originConversation(),
                          null,
                          BoardMessage.POST,
                          null,
                          request.body(),
                          false,
                          List.of()));
              if (byDefinition) {
                seat(topic, BoardSeat.OPENER, seated);
              }
              // Spec §6: an opening wakes every member except the opener. WakeRules leaves out the
              // opening's author seat, which for a definition is @opener — not the member name it
              // may also sit under in swarm.md. Left in, it would be seated a second time and
              // woken by its own opening. A person's handle is no agent, so a person opens with
              // every member woken.
              List<String> members =
                  byDefinition
                      ? swarm.members().stream()
                          .filter(member -> !member.equals(request.opener()))
                          .toList()
                      : swarm.members();
              List<WakeRules.Wake> woken =
                  owe(topic, opening, WakeRules.opened(opening, members), seated, targets);
              return new Opened(topic, opening, woken);
            });
    afterCommit(seated, targets);
    return opened;
  }

  /**
   * Posts one message and owes a wake to every seat it addresses. A member's second alert is posted
   * as an ordinary message, and {@link Posted#alertRefused()} says so.
   *
   * @throws Refused for a missing or closed topic, a blank body, a mention of someone who is not a
   *     member, or a reply to a message that is not on this topic
   */
  public Posted post(Post request) {
    BoardTopic topic =
        store
            .topic(request.topic())
            .orElseThrow(() -> new Refused("there is no topic " + request.topic()));
    if (topic.isClosed()) {
      throw new Refused("topic " + topic.id() + " is closed; nothing more is said on it");
    }
    requireText(request.body(), "body");
    if (!Set.of(
            BoardMessage.BY_MEMBER,
            BoardMessage.BY_OPENER,
            BoardMessage.BY_PERSON,
            BoardMessage.BY_HARNESS)
        .contains(String.valueOf(request.authorKind()))) {
      throw new Refused("there is no board author kind '" + request.authorKind() + "'");
    }
    if (!Set.of(
            BoardMessage.POST,
            BoardMessage.DOCUMENT,
            BoardMessage.REQUEST,
            BoardMessage.NOTE,
            BoardMessage.HOOK)
        .contains(String.valueOf(request.kind()))) {
      throw new Refused(
          "this post cannot write kind '"
              + request.kind()
              + "'; pass and close have their own actions");
    }
    requireText(request.author(), "author");
    boolean titled = Set.of(BoardMessage.DOCUMENT, BoardMessage.REQUEST).contains(request.kind());
    if (titled) {
      requireText(request.title(), "title");
    }
    if (!titled && request.title() != null) {
      throw new Refused("only documents and requests have a title");
    }
    if (request.alert() && !BoardMessage.POST.equals(request.kind())) {
      throw new Refused("only a post can alert the swarm");
    }
    if (BoardMessage.REQUEST.equals(request.kind())) {
      if (!BoardMessage.BY_MEMBER.equals(request.authorKind())) {
        throw new Refused("only member seats request subtopics");
      }
      if (topic.depth() >= maxDepth.getAsInt()) {
        throw new Refused("this topic is at the maximum subtopic depth; ask on it instead");
      }
    }
    List<String> members = swarmFor.apply(topic.project()).members();
    for (String mentioned : request.mentions()) {
      if (!members.contains(mentioned)) {
        throw new Refused(
            "@"
                + mentioned
                + " is not a member of this project's swarm;"
                + " the members are "
                + String.join(", ", members));
      }
    }
    List<Seated> seated = new ArrayList<>();
    Set<String> targets = new LinkedHashSet<>();
    Posted posted =
        work.inTransaction(
            () -> {
              lockedOpenTopic(topic.id());
              if (BoardMessage.BY_MEMBER.equals(request.authorKind())) {
                if (!members.contains(request.author())) {
                  throw new Refused("@" + request.author() + " is not a swarm member");
                }
                seat(topic, request.author(), seated);
              }
              boolean alert = request.alert();
              boolean alertRefused = false;
              boolean memberAlert =
                  BoardMessage.BY_MEMBER.equals(request.authorKind())
                      || (BoardMessage.BY_OPENER.equals(request.authorKind())
                          && BoardTopic.BY_MEMBER.equals(topic.openerKind()));
              String alertSeat =
                  BoardMessage.BY_OPENER.equals(request.authorKind())
                      ? BoardSeat.OPENER
                      : request.author();
              if (alert && memberAlert && !store.useAlert(topic.id(), alertSeat)) {
                alert = false;
                alertRefused = true;
              }
              // Narrowed to this one call: an IllegalArgumentException out of ConversationStore.log
              // (reached from owe() below, a programming error and not a caller's bad reply) must
              // not be swallowed into the same Refused sentence a bad reply gets. The exception
              // still propagates out of this lambda either way, so the transaction still rolls
              // back on both paths.
              BoardMessage message;
              try {
                message =
                    store.post(
                        new BoardStore.NewMessage(
                            topic.id(),
                            request.replyTo(),
                            request.authorKind(),
                            request.author(),
                            request.conversation(),
                            request.entry(),
                            request.kind(),
                            titled ? folded(request.title(), "title", BoardTopic.TITLE_MAX) : null,
                            request.body(),
                            alert,
                            request.mentions()));
              } catch (IllegalArgumentException badReply) {
                throw new Refused(badReply.getMessage());
              }
              BoardMessage repliedTo =
                  request.replyTo() == null ? null : store.message(request.replyTo()).orElse(null);
              List<WakeRules.Wake> woken =
                  owe(
                      topic,
                      message,
                      WakeRules.after(message, repliedTo, members),
                      seated,
                      targets);
              return new Posted(message, woken, alertRefused);
            });
    afterCommit(seated, targets);
    return posted;
  }

  /** Retry exactly one failed member in its existing conversation, within the root's pot. */
  public Posted retry(String topicId, String member, String account, int maxTurns) {
    if (maxTurns < 1) throw new Refused("A member retry needs a positive step limit.");
    List<Seated> seated = new ArrayList<>();
    Set<String> targets = new LinkedHashSet<>();
    Posted retried =
        work.inTransaction(
            () -> {
              BoardTopic topic = lockedOpenTopic(topicId);
              if (!Objects.equals(account, topic.account())) {
                throw new Refused("Choose a topic owned by this account.");
              }
              if (!swarmFor.apply(topic.project()).members().contains(member)) {
                throw new Refused("Choose a member of this project's swarm.");
              }
              BoardSeat seat =
                  store
                      .seat(topicId, member)
                      .filter(s -> !s.isOpener())
                      .orElseThrow(() -> new Refused("No such member seat."));
              if (seat.failedEnding() == null
                  || firings.busy("conversation:" + seat.conversation())) {
                throw new Refused(
                    "Only a stopped, failed member can be retried; refresh its state.");
              }
              BoardTopic root = store.topic(topic.root()).orElseThrow();
              if (root.isClosed() || root.potSpent() >= root.potTotal() - root.reserve()) {
                throw new Refused(
                    "The topic has no member allowance left; raise its total before retrying.");
              }
              BoardMessage message =
                  store.post(
                      new BoardStore.NewMessage(
                          topicId,
                          null,
                          BoardMessage.BY_PERSON,
                          account,
                          null,
                          null,
                          BoardMessage.POST,
                          null,
                          "@"
                              + member
                              + " Continue from your existing conversation. Read the current"
                              + " topic with board_read({}); the topic argument is an ID, not a title.",
                          false,
                          List.of(member)));
              List<WakeRules.Wake> woken =
                  owe(
                      topic,
                      message,
                      List.of(new WakeRules.Wake(member, WakeRules.Reason.MENTION)),
                      seated,
                      targets,
                      maxTurns);
              return new Posted(message, woken, false);
            });
    afterCommit(seated, targets);
    return retried;
  }

  /** A harness note on {@code topic}, which wakes nobody. */
  public BoardMessage note(String topic, String body) {
    return store.post(
        new BoardStore.NewMessage(
            topic,
            null,
            BoardMessage.BY_HARNESS,
            "harness",
            null,
            null,
            BoardMessage.NOTE,
            null,
            body,
            false,
            List.of()));
  }

  /**
   * {@code text} on one line and at most {@code max} characters: every run of whitespace — a
   * newline, a tab, a Unicode line or paragraph separator — becomes one space, and a text still too
   * long is cut to {@code max − 1} characters and ends in "…".
   *
   * <p><b>Why a title and a label are folded at all</b> (the final review's I-4): each is an
   * author's words, and every seat the topic wakes is told them inside the harness's own wake line
   * ({@link WakeUtterance}). A newline in a title would start a line of its own there, in the
   * harness's voice; an unbounded one would be as long as its author liked. Folded and capped here,
   * bounded again by V74's CHECKs, and quoted as data where they are rendered. Counted in code
   * points, as Postgres's {@code char_length} counts, so a title of 120 accented letters is not cut
   * short, nor a surrogate pair cut in half.
   */
  static String fold(String text, int max) {
    String folded = text.replaceAll("(?U)\\s+", " ").strip();
    if (folded.codePointCount(0, folded.length()) <= max) {
      return folded;
    }
    return folded.substring(0, folded.offsetByCodePoints(0, max - 1)).stripTrailing() + "…";
  }

  /** A tenth (by default) of the pot, rounded up, at least one and below the total. */
  static int reserveOf(int total, int percent) {
    int reserve = Math.max(1, (int) Math.ceil(total * percent / 100.0));
    return Math.min(reserve, total - 1);
  }

  /**
   * Owes each named seat a wake, creating the seat when it has none. A seat whose last wake failed
   * is woken again only by a mention naming it, and that mention clears the failure — checked
   * against the message's own {@code mentions}, not against which reason {@link WakeRules} chose
   * for it: {@link WakeRules#after} ranks a reply ahead of a mention, so a reply to a failed seat's
   * own message that also {@code @}-mentions it would otherwise be filed as a REPLY and skipped,
   * leaving the most natural way to retry a stuck seat unable to. A request to a person-opened
   * topic's opener has no seat to wake — the person hears of it with the frames (step 4). Under the
   * caller's transaction.
   */
  private List<WakeRules.Wake> owe(
      BoardTopic topic,
      BoardMessage message,
      List<WakeRules.Wake> named,
      List<Seated> seated,
      Set<String> targets) {
    return owe(topic, message, named, seated, targets, null);
  }

  private List<WakeRules.Wake> owe(
      BoardTopic topic,
      BoardMessage message,
      List<WakeRules.Wake> named,
      List<Seated> seated,
      Set<String> targets,
      Integer maxTurns) {
    List<WakeRules.Wake> woken = new ArrayList<>();
    for (WakeRules.Wake wake : normalize(topic, message, named)) {
      if (wake.occupant().equals(BoardSeat.OPENER)
          && BoardTopic.BY_PERSON.equals(topic.openerKind())) {
        continue;
      }
      BoardSeat seat = seat(topic, wake.occupant(), seated);
      if (seat.failedEnding() != null && !seat.isOpener()) {
        if (!message.mentions().contains(seat.occupant())) {
          continue;
        }
        store.clearFailure(topic.id(), seat.occupant());
      }
      String target = "conversation:" + seat.conversation();
      // Coalescing a later addressed message must retain a person's queued retry limit.
      Integer queuedCap = maxTurns;
      if (queuedCap == null) {
        var pending = firings.oldestWaiting(target).filter(f -> topic.id().equals(f.topic()));
        if (pending.isPresent()) {
          Integer requested =
              ((io.aeyer.plowshare.server.events.EventPayload.Seat) pending.get().data())
                  .wake()
                  .maxTurns();
          if (requested != null) queuedCap = Math.max(queuedCap == null ? 0 : queuedCap, requested);
        }
      }
      FiringRecord firing =
          firings
              .owe(topic.id(), target, data(wake, message, queuedCap), clock.get())
              .orElseThrow();
      if (store.noticeKind(message.id()).isPresent()) {
        store.noticeWake(message.id(), firing.id());
      }
      firings.supersedeWakesBeyond(target, 1, firing.id());
      targets.add(target);
      woken.add(wake);
    }
    return woken;
  }

  /** A member who opens a child sits as @opener there, never twice under its own name. */
  private List<WakeRules.Wake> normalize(
      BoardTopic topic, BoardMessage message, List<WakeRules.Wake> named) {
    Map<String, WakeRules.Reason> unique = new java.util.LinkedHashMap<>();
    for (WakeRules.Wake wake : named) {
      String occupant =
          !BoardTopic.BY_PERSON.equals(topic.openerKind()) && topic.opener().equals(wake.occupant())
              ? BoardSeat.OPENER
              : wake.occupant();
      String agent = BoardSeat.OPENER.equals(occupant) ? topic.opener() : occupant;
      boolean agentAuthor =
          BoardMessage.BY_MEMBER.equals(message.authorKind())
              || BoardMessage.BY_OPENER.equals(message.authorKind());
      if (agentAuthor && agent.equals(message.author())) {
        continue;
      }
      unique.putIfAbsent(occupant, wake.reason());
    }
    return unique.entrySet().stream()
        .map(e -> new WakeRules.Wake(e.getKey(), e.getValue()))
        .toList();
  }

  private BoardSeat seat(BoardTopic topic, String occupant, List<Seated> seated) {
    return store
        .seat(topic.id(), occupant)
        .orElseGet(
            () -> {
              boolean opener = occupant.equals(BoardSeat.OPENER);
              String agent = opener ? topic.opener() : occupant;
              Home home = Home.of(topic.project());
              String conversation =
                  conversations.log(Origin.BOARD, home, agent, null, null, topic.account()).id();
              // Only the caller that actually wins the seat announces its log opening: a lost race
              // here has minted a conversation nothing on the topic will ever point at, and
              // announcing it as this seat's log.open would tell the log stages about a seat that
              // is, from the board's own row, somebody else's.
              if (store.seatIfAbsent(topic.id(), occupant, conversation)) {
                seated.add(
                    new Seated(
                        conversation,
                        home,
                        agent,
                        opener && BoardTopic.BY_BOT.equals(topic.openerKind())));
              }
              return store.seat(topic.id(), occupant).orElseThrow();
            });
  }

  private void afterCommit(List<Seated> seated, Set<String> targets) {
    work.afterCommit(() -> dispatch(seated, targets));
  }

  private void dispatch(List<Seated> seated, Set<String> targets) {
    for (Seated seat : seated) {
      try {
        logStages.opened(
            new LogStages.LogOpened(
                seat.conversation(), Origin.BOARD, seat.home(), seat.agent(), seat.bot(), null));
      } catch (RuntimeException contractBroken) {
        log.warn(
            "log.open for seat {} threw despite its contract", seat.conversation(), contractBroken);
      }
    }
    for (String target : targets) {
      try {
        drain.accept(target);
      } catch (RuntimeException undrained) {
        // The wake is queued and durable; the seat's next free moment or the boot drain
        // starts it.
        log.warn("draining {} after a board write failed", target, undrained);
      }
    }
  }

  private static io.aeyer.plowshare.server.events.EventPayload data(
      WakeRules.Wake wake, BoardMessage message, Integer maxTurns) {
    return new io.aeyer.plowshare.server.events.EventPayload.Seat(
        new SeatWake(wake.reason().wire(), message.id(), message.author(), maxTurns));
  }

  /**
   * {@link #fold}ed, and refused if nothing is left: whitespace {@link String#isBlank} does not
   * count — a no-break space, say — is still whitespace to {@link #fold}, and a title of only that
   * would otherwise fold to nothing and fail V74's CHECK instead of being refused.
   */
  private static String folded(String text, String field, int max) {
    requireText(text, field);
    String folded = fold(text, max);
    requireText(folded, field);
    return folded;
  }

  private static void requireText(String text, String field) {
    if (text == null || text.isBlank()) {
      throw new Refused("a topic's " + field + " must say something");
    }
  }

  public record Close(
      String topic,
      String authorKind,
      String author,
      String conversation,
      String body,
      List<String> cites) {

    public Close {
      cites = List.copyOf(cites);
    }
  }

  public record Closed(BoardTopic topic, BoardMessage resolution) {}

  /** Told a topic's id once it has closed, after commit. The delivery; nothing until wired. */
  private volatile Consumer<String> resolved = topic -> {};

  /** Set once at wiring, by the board's delivery. */
  public void useResolutions(Consumer<String> resolved) {
    this.resolved = Objects.requireNonNull(resolved, "resolved");
  }

  /**
   * What is new on {@code topic} for {@code occupant}'s seat, in order, and its watermark moved to
   * the last of them — the watermark moves only when a model is actually given the messages (spec
   * §4), which is here.
   *
   * @throws Refused for a missing topic or a seat that does not exist
   */
  public List<BoardMessage> read(String topic, String occupant) {
    BoardSeat seat =
        store
            .seat(topic, occupant)
            .orElseThrow(
                () -> new Refused("there is no seat for " + occupant + " on topic " + topic));
    List<BoardMessage> unread = store.messagesAfter(topic, seat.seenThrough());
    if (!unread.isEmpty()) {
      store.advanceSeen(topic, occupant, unread.getLast().id());
    }
    return unread;
  }

  /**
   * A member steps aside: a PASS message, and its seat marked passed — woken again only by a
   * mention, an alert or a reply to its own message (spec §6). A pass wakes nobody.
   *
   * @throws Refused for a closed topic, the opener (who closes rather than passes), or a blank
   *     reason
   */
  public BoardMessage pass(String topic, String occupant, String conversation, String reason) {
    if (BoardSeat.OPENER.equals(occupant)) {
      throw new Refused("the opener does not pass; it closes the topic with board_close");
    }
    BoardTopic open = openTopic(topic);
    if (reason == null || reason.isBlank()) {
      throw new Refused("a pass says why, in a sentence");
    }
    return work.inTransaction(
        () -> {
          lockedOpenTopic(open.id());
          store
              .seat(open.id(), occupant)
              .orElseThrow(
                  () -> new Refused("there is no seat for " + occupant + " on topic " + topic));
          BoardMessage message =
              store.post(
                  new BoardStore.NewMessage(
                      open.id(),
                      null,
                      BoardMessage.BY_MEMBER,
                      occupant,
                      conversation,
                      null,
                      BoardMessage.PASS,
                      null,
                      reason.strip(),
                      false,
                      List.of()));
          store.pass(open.id(), occupant);
          return message;
        });
  }

  /**
   * Closes a topic with its resolution (spec §7): a RESOLUTION message naming what it cites, the
   * topic closed, and its queued wakes refused, in one transaction; the delivery is told after
   * commit. Open children close deepest first; a running seat ends at its next boundary.
   *
   * @throws Refused for a missing or closed topic, a blank resolution, or a cite that is not a
   *     message on this topic
   */
  public Closed close(Close request) {
    BoardTopic topic = openTopic(request.topic());
    requireText(request.body(), "resolution");
    for (String cited : request.cites()) {
      BoardMessage message =
          store
              .message(cited)
              .orElseThrow(() -> new Refused("there is no message " + cited + " to cite"));
      if (!message.topic().equals(topic.id())) {
        throw new Refused(
            "message "
                + cited
                + " is on topic "
                + message.topic()
                + "; a resolution cites what was said on its own topic");
      }
    }
    String body =
        request.cites().isEmpty()
            ? request.body().strip()
            : request.body().strip() + "\n\nCites: " + String.join(", ", request.cites());
    List<String> closedIds = new ArrayList<>();
    List<Seated> seated = new ArrayList<>();
    Set<String> targets = new LinkedHashSet<>();
    Closed closed =
        work.inTransaction(
            () -> {
              lockedOpenTopic(topic.id());
              for (BoardTopic child : store.descendants(topic.id())) {
                finishClose(
                    child,
                    BoardMessage.BY_HARNESS,
                    "harness",
                    null,
                    documents(child, "closed with its parent"),
                    closedIds,
                    seated,
                    targets);
              }
              return finishClose(
                  topic,
                  request.authorKind(),
                  request.author(),
                  request.conversation(),
                  body,
                  closedIds,
                  seated,
                  targets);
            });
    afterCommit(seated, targets);
    work.afterCommit(() -> closedIds.forEach(this::announceClosed));
    return closed;
  }

  private Closed finishClose(
      BoardTopic topic,
      String kind,
      String author,
      String conversation,
      String body,
      List<String> closedIds,
      List<Seated> seated,
      Set<String> targets) {
    BoardMessage resolution =
        store.post(
            new BoardStore.NewMessage(
                topic.id(),
                null,
                kind,
                author,
                conversation,
                null,
                BoardMessage.RESOLUTION,
                null,
                body,
                false,
                List.of()));
    if (!store.close(topic.id(), resolution.id())) {
      throw new Refused("topic " + topic.id() + " was closed while this was written");
    }
    firings.refuseWakes(topic.id(), "topic closed");
    closedIds.add(topic.id());
    if (!topic.isRoot()) {
      BoardTopic parent = store.topic(topic.parent()).orElseThrow();
      if (!parent.isClosed()) {
        BoardMessage reply =
            store.post(
                new BoardStore.NewMessage(
                    parent.id(),
                    store.requestForChild(topic.id()).orElseThrow(),
                    BoardMessage.BY_HARNESS,
                    "harness",
                    null,
                    null,
                    BoardMessage.POST,
                    null,
                    BoardText.resolution(topic, resolution),
                    false,
                    List.of()));
        owe(
            parent,
            reply,
            WakeRules.after(
                reply,
                store.message(reply.replyTo()).orElseThrow(),
                swarmFor.apply(parent.project()).members()),
            seated,
            targets);
      }
      store.resolutionDelivered(topic.id());
    }
    return new Closed(store.topic(topic.id()).orElseThrow(), resolution);
  }

  private String documents(BoardTopic topic, String explanation) {
    List<String> docs =
        store.messages(topic.id()).stream()
            .filter(m -> BoardMessage.DOCUMENT.equals(m.kind()))
            .map(m -> m.id() + " — " + fold(m.title(), BoardTopic.TITLE_MAX))
            .toList();
    return explanation
        + (docs.isEmpty() ? "\n\nDocuments: none." : "\n\nDocuments:\n" + String.join("\n", docs));
  }

  private void announceClosed(String topic) {
    for (Consumer<String> listener : closedListeners) {
      try {
        listener.accept(topic);
      } catch (RuntimeException failed) {
        log.warn("could not stop topic {}", topic, failed);
      }
    }
    try {
      resolved.accept(topic);
    } catch (RuntimeException failed) {
      log.warn("resolution stays owed for {}", topic, failed);
    }
  }

  /** Approves or refuses exactly one pending request, under the tree's lock. */
  public BoardStore.Decision decide(
      String topicId, String requestId, boolean approve, String reason, String conversation) {
    requireText(reason, "decision reason");
    List<Seated> seated = new ArrayList<>();
    Set<String> targets = new LinkedHashSet<>();
    BoardStore.Decision decision =
        work.inTransaction(
            () -> {
              BoardTopic parent = lockedOpenTopic(topicId);
              BoardMessage request =
                  store
                      .message(requestId)
                      .orElseThrow(() -> new Refused("there is no request " + requestId));
              if (!request.topic().equals(topicId)
                  || !BoardMessage.REQUEST.equals(request.kind())
                  || !BoardMessage.BY_MEMBER.equals(request.authorKind())) {
                throw new Refused("that message is not a member's request on this topic");
              }
              if (store.decision(requestId).isPresent()) {
                throw new Refused("that request has already been decided");
              }
              BoardTopic child = null;
              if (approve) {
                if (parent.depth() >= maxDepth.getAsInt()) {
                  throw new Refused("this topic is at the maximum subtopic depth");
                }
                BoardTopic root = store.topic(parent.root()).orElseThrow();
                if (!BoardTopic.OPEN.equals(root.state())) {
                  throw new Refused(
                      "the root's budget is exhausted; top it up before opening a child");
                }
                child =
                    store.openChild(
                        parent,
                        folded(request.title(), "title", BoardTopic.TITLE_MAX),
                        folded(parent.label(), "label", BoardTopic.LABEL_MAX),
                        request.author());
                BoardMessage opening =
                    store.post(
                        new BoardStore.NewMessage(
                            child.id(),
                            null,
                            BoardMessage.BY_OPENER,
                            request.author(),
                            null,
                            null,
                            BoardMessage.POST,
                            null,
                            request.body(),
                            false,
                            List.of()));
                seat(child, BoardSeat.OPENER, seated);
                List<String> members =
                    swarmFor.apply(parent.project()).members().stream()
                        .filter(m -> !m.equals(request.author()))
                        .toList();
                owe(child, opening, WakeRules.opened(opening, members), seated, targets);
              }
              store.decide(requestId, approve, reason.strip(), child == null ? null : child.id());
              BoardMessage reply =
                  store.post(
                      new BoardStore.NewMessage(
                          parent.id(),
                          requestId,
                          BoardMessage.BY_OPENER,
                          parent.opener(),
                          conversation,
                          null,
                          BoardMessage.POST,
                          null,
                          (approve ? "Approved: " + child.id() : "Refused") + ". " + reason.strip(),
                          false,
                          List.of()));
              owe(
                  parent,
                  reply,
                  WakeRules.after(reply, request, swarmFor.apply(parent.project()).members()),
                  seated,
                  targets);
              return store.decision(requestId).orElseThrow();
            });
    afterCommit(seated, targets);
    return decision;
  }

  /** Ancestors are readable using this seat's own independent watermarks. */
  public List<BoardMessage> read(String from, String occupant, String requested) {
    if (requested == null || from.equals(requested)) {
      return read(from, occupant);
    }
    BoardSeat seat = store.seat(from, occupant).orElseThrow(() -> new Refused("no such seat"));
    BoardTopic current = store.topic(from).orElseThrow();
    while (current.parent() != null && !current.parent().equals(requested)) {
      current = store.topic(current.parent()).orElseThrow();
    }
    if (current.parent() == null) {
      throw new Refused("board_read can read your topic and its ancestors, never a sibling");
    }
    List<BoardMessage> messages =
        store.messagesAfter(
            requested, store.ancestorSeen(seat.conversation(), requested).orElse(null));
    if (!messages.isEmpty()) {
      store.ancestorSeen(seat.conversation(), requested, messages.getLast().id());
    }
    return messages;
  }

  /** An operator raises the tree's absolute model-call ceiling, then recovers owed members. */
  public BoardTopic topup(String topicId, Integer total) {
    if (total == null) {
      throw new Refused("a top-up requires maxModelCalls, the new total");
    }
    BoardTopic topic = openTopic(topicId);
    String rootId = topic.root();
    BoardTopic topped =
        work.inTransaction(
            () -> {
              BoardTopic root = lockedOpenTopic(rootId);
              if (total <= root.potTotal()) {
                throw new Refused(
                    "a top-up raises maxModelCalls above the current total " + root.potTotal());
              }
              store.topup(rootId, total, reserveOf(total, closingReserve.getAsInt()));
              return store.topic(rootId).orElseThrow();
            });
    work.afterCommit(
        () -> {
          for (BoardTopic open : store.openTree(rootId)) {
            for (BoardSeat seat : store.seats(open.id())) {
              if (!seat.isOpener()) {
                reowe(open.id(), seat.occupant());
              }
            }
          }
        });
    return topped;
  }

  /** Called after a firing finishes/refuses and at boot: exhaust, close, or notify quiet. */
  public void settled(String rootId) {
    Optional<BoardTopic> found = store.topic(rootId);
    if (found.isEmpty() || found.get().isClosed()) {
      return;
    }
    List<Seated> seated = new ArrayList<>();
    Set<String> targets = new LinkedHashSet<>();
    List<String> closedIds = new ArrayList<>();
    work.inTransaction(
        () -> {
          BoardTopic root = store.lockTopic(rootId).orElseThrow();
          if (root.isClosed()) {
            return null;
          }
          if (root.potSpent() >= root.potTotal()) {
            for (BoardTopic open : store.openTree(rootId)) {
              finishClose(
                  open,
                  BoardMessage.BY_HARNESS,
                  "harness",
                  null,
                  documents(open, "closed by the harness: budget exhausted"),
                  closedIds,
                  seated,
                  targets);
            }
          } else if (root.potTotal() - root.potSpent() <= root.reserve()) {
            if (store.exhaust(rootId)) {
              for (BoardTopic open : store.openTree(rootId)) {
                store.refuseMemberWakes(open.id(), "the root's budget is exhausted");
                notifyOpener(
                    open,
                    "exhausted",
                    "budget exhausted: close with what you have, or ask for more",
                    WakeRules.Reason.EXHAUSTED,
                    seated,
                    targets);
              }
            }
          } else {
            for (BoardTopic open : store.openTree(rootId)) {
              if (!store.activeWakes(open.id())
                  && !running.test(open.id())
                  && store.descendants(open.id()).isEmpty()
                  && store.markQuiet(open.id())) {
                notifyOpener(
                    open,
                    "quiet",
                    "quiet: close it, mention someone, or ask",
                    WakeRules.Reason.QUIET,
                    seated,
                    targets);
              }
            }
          }
          return null;
        });
    afterCommit(seated, targets);
    work.afterCommit(
        () -> {
          closedIds.forEach(this::announceClosed);
          notices.run();
        });
  }

  private void notifyOpener(
      BoardTopic topic,
      String kind,
      String body,
      WakeRules.Reason reason,
      List<Seated> seated,
      Set<String> targets) {
    BoardMessage message = note(topic.id(), body);
    boolean person = BoardTopic.BY_PERSON.equals(topic.openerKind());
    store.notice(message, kind, !person);
    if (!person) {
      owe(topic, message, List.of(new WakeRules.Wake(BoardSeat.OPENER, reason)), seated, targets);
    }
  }

  /**
   * Owes {@code occupant}'s seat one fresh wake when a message past its watermark addresses it by
   * §6's rules — used after a wake ends (the cap reached with something unread, or a message that
   * arrived mid-wake) and at boot. Nothing for a closed topic, a missing seat, or a member seat
   * whose last wake failed.
   *
   * @return whether a wake was owed
   */
  public boolean reowe(String topic, String occupant) {
    return reowe(topic, occupant, false);
  }

  public boolean reowe(String topic, String occupant, boolean recoverNotices) {
    Optional<BoardTopic> found = store.topic(topic).filter(t -> !t.isClosed());
    Optional<BoardSeat> seat = store.seat(topic, occupant);
    if (found.isEmpty()
        || seat.isEmpty()
        || (seat.get().failedEnding() != null && !seat.get().isOpener())) {
      return false;
    }
    List<String> members = swarmFor.apply(found.get().project()).members();
    List<String> openingMembers =
        BoardTopic.BY_PERSON.equals(found.get().openerKind())
            ? members
            : members.stream().filter(m -> !m.equals(found.get().opener())).toList();
    List<BoardMessage> all = store.messages(topic);
    String openingId = all.isEmpty() ? null : all.getFirst().id();
    BoardMessage addressing = null;
    WakeRules.Wake wake = null;
    for (BoardMessage message : store.messagesAfter(topic, seat.get().seenThrough())) {
      List<WakeRules.Wake> named =
          message.id().equals(openingId)
              ? WakeRules.opened(message, openingMembers)
              : WakeRules.after(
                  message,
                  message.replyTo() == null ? null : store.message(message.replyTo()).orElse(null),
                  members);
      Optional<String> notice = store.noticeKind(message.id());
      if (notice.isPresent()) {
        if (!seat.get().isOpener() || !recoverNotices || !store.noticeNeedsRecovery(message.id())) {
          continue;
        }
        named =
            List.of(new WakeRules.Wake(BoardSeat.OPENER, WakeRules.Reason.fromWire(notice.get())));
      }
      for (WakeRules.Wake each : normalize(found.get(), message, named)) {
        if (each.occupant().equals(occupant)
            && !(seat.get().passed() && each.reason() == WakeRules.Reason.OPENED)) {
          addressing = message;
          wake = each;
        }
      }
    }
    if (wake == null) {
      return false;
    }
    BoardMessage message = addressing;
    WakeRules.Wake owed = wake;
    List<Seated> seated = new ArrayList<>();
    Set<String> targets = new LinkedHashSet<>();
    work.inTransaction(
        () -> {
          BoardTopic current = store.topic(topic).orElseThrow();
          store.lockTopic(current.root());
          current = store.lockTopic(topic).orElseThrow();
          return current.isClosed()
              ? List.<WakeRules.Wake>of()
              : owe(current, message, List.of(owed), seated, targets);
        });
    afterCommit(seated, targets);
    return !targets.isEmpty();
  }

  private BoardTopic lockedOpenTopic(String id) {
    BoardTopic topic = openTopic(id);
    if (!topic.isRoot()) {
      requireOpenTopic(store.lockTopic(topic.root()), topic.root());
    }
    return requireOpenTopic(store.lockTopic(id), id);
  }

  private BoardTopic openTopic(String id) {
    return requireOpenTopic(store.topic(id), id);
  }

  private static BoardTopic requireOpenTopic(Optional<BoardTopic> found, String id) {
    BoardTopic topic = found.orElseThrow(() -> new Refused("there is no topic " + id));
    if (topic.isClosed()) {
      throw new Refused("topic " + id + " is closed; nothing more is said on it");
    }
    return topic;
  }
}
