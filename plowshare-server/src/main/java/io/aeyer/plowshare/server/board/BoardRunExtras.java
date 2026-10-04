package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.server.agents.AgentTool;
import io.aeyer.plowshare.server.agents.BoardTools;
import io.aeyer.plowshare.server.agents.RunExtras;
import io.aeyer.plowshare.server.agents.TurnEnd;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Who is handed which board tools — spec 2026-09-29 §7. A run in a seat's conversation gets that
 * seat's tools, bound to its topic and occupant; an opted-in definition's run in a person's
 * conversation with a project gets {@code board_open}. Account-owned project runs also get
 * messaging, composed with the ordinary tools and orchestration extras.
 */
public final class BoardRunExtras implements RunExtras {

  private final BoardStore store;
  private final Board board;
  private final ConversationStore conversations;
  private final BoardMessaging messaging;

  public BoardRunExtras(BoardStore store, Board board, ConversationStore conversations) {
    this(store, board, conversations, null);
  }

  public BoardRunExtras(
      BoardStore store, Board board, ConversationStore conversations, BoardMessaging messaging) {
    this.store = Objects.requireNonNull(store, "store");
    this.board = Objects.requireNonNull(board, "board");
    this.conversations = Objects.requireNonNull(conversations, "conversations");
    this.messaging = messaging;
  }

  @Override
  public Extras forRun(Context context) {
    String conversation = context.conversationId();
    if (conversation == null) {
      return Extras.NONE;
    }
    if (messaging != null && messaging.isTransport(conversation)) {
      // Public board participation is a definition capability; its private
      // messaging transport never exposes its own seat or board tools.
      return new Extras(
          context.definition().board()
              ? List.of(messaging.tool(context), BoardTools.open(opening(context)))
              : List.of(messaging.tool(context)),
          context.end(),
          false);
    }
    Optional<BoardSeat> seat = store.seatByConversation(conversation);
    if (seat.isPresent()) {
      TurnEnd end = context.end() != null ? context.end() : new TurnEnd();
      BoardTools.Seat actions = new SeatActions(seat.get(), conversation);
      List<AgentTool> tools =
          seat.get().isOpener()
              ? BoardTools.forOpenerSeat(actions, end)
              : BoardTools.forMember(actions, end);
      if (messaging != null) {
        tools = new java.util.ArrayList<>(tools);
        tools.add(messaging.tool(context));
      }
      return new Extras(tools, end, false);
    }
    if (context.definition().board()
        && context.home() != null
        && !context.home().isGlobal()
        && isPersons(conversation)) {
      return new Extras(
          messaging == null
              ? List.of(BoardTools.open(opening(context)))
              : List.of(BoardTools.open(opening(context)), messaging.tool(context)),
          context.end(),
          false);
    }
    if (messaging != null
        && context.home() != null
        && !context.home().isGlobal()
        && conversations.ownerOf(conversation).isPresent()) {
      return new Extras(List.of(messaging.tool(context)), context.end(), false);
    }
    return Extras.NONE;
  }

  private boolean isPersons(String conversation) {
    return conversations.find(conversation).map(row -> row.origin() == Origin.TURN).orElse(false);
  }

  private BoardTools.Opening opening(Context context) {
    return (title, label, body, budget) -> {
      String account =
          context.callerHandle() != null
              ? context.callerHandle()
              : conversations.ownerOf(context.conversationId()).orElse(null);
      if (account == null) {
        return "No account owns this conversation, so a topic opened from it would have"
            + " no one to be accounted to; it was not opened.";
      }
      try {
        Board.Opened opened =
            board.open(
                new Board.Open(
                    context.home(),
                    title,
                    label,
                    body,
                    account,
                    context.definition().bot() ? BoardTopic.BY_BOT : BoardTopic.BY_AGENT,
                    context.definition().name(),
                    context.conversationId(),
                    budget));
        return "Opened "
            + opened.topic().id()
            + " — ["
            + opened.topic().label()
            + "] \""
            + opened.topic().title()
            + "\", "
            + opened.topic().potTotal()
            + " model calls. Woke: "
            + names(opened.woken())
            + ". You are woken on"
            + " it as its opener when someone answers you; close it with board_close"
            + " there, and its resolution comes back into this conversation.";
      } catch (Board.Refused refused) {
        return refused.getMessage();
      }
    };
  }

  /** One seat's verbs, bound to its topic, occupant and conversation. */
  private final class SeatActions implements BoardTools.Seat {
    private final BoardSeat seat;
    private final String conversation;

    SeatActions(BoardSeat seat, String conversation) {
      this.seat = seat;
      this.conversation = conversation;
    }

    private String authorKind() {
      return seat.isOpener() ? BoardMessage.BY_OPENER : BoardMessage.BY_MEMBER;
    }

    private String author() {
      return seat.isOpener() ? topic().opener() : seat.occupant();
    }

    private BoardTopic topic() {
      return store.topic(seat.topic()).orElseThrow();
    }

    @Override
    public String read() {
      return read(null);
    }

    @Override
    public String read(String requested) {
      if (requested != null && !requested.startsWith(BoardStore.TOPIC_PREFIX)) {
        return "board_read takes a topic ID, not a title or path. Your topic ID is "
            + seat.topic()
            + ". Call board_read({}) to read your current topic.";
      }
      try {
        List<BoardMessage> messages = board.read(seat.topic(), seat.occupant(), requested);
        BoardTopic readTopic = requested == null ? topic() : store.topic(requested).orElseThrow();
        return BoardText.messages(readTopic, messages);
      } catch (Board.Refused refused) {
        return refused.getMessage()
            + ". Your current topic ID is "
            + seat.topic()
            + "; call board_read({}) to read it.";
      }
    }

    @Override
    public String post(String body, String replyTo, List<String> mentions, boolean alert) {
      return write(BoardMessage.POST, null, body, replyTo, mentions, alert);
    }

    @Override
    public String document(String title, String body, String replyTo) {
      return write(BoardMessage.DOCUMENT, title, body, replyTo, List.of(), false);
    }

    private String write(
        String kind,
        String title,
        String body,
        String replyTo,
        List<String> mentions,
        boolean alert) {
      try {
        Board.Posted posted =
            board.post(
                new Board.Post(
                    seat.topic(),
                    authorKind(),
                    author(),
                    conversation,
                    null,
                    kind,
                    title,
                    body,
                    replyTo,
                    mentions,
                    alert));
        String said =
            (BoardMessage.DOCUMENT.equals(kind) ? "Documented as " : "Posted as ")
                + posted.message().id()
                + ". Woke: "
                + names(posted.woken())
                + ".";
        return posted.alertRefused()
            ? said
                + " Your alert was not sent: a seat sends"
                + " one per topic, and this message went out as an ordinary post."
            : said;
      } catch (Board.Refused refused) {
        return refused.getMessage();
      }
    }

    @Override
    public String requestTopic(String title, String why) {
      return write(BoardMessage.REQUEST, title, why, null, List.of(), false);
    }

    @Override
    public String decide(String request, boolean approve, String reason) {
      try {
        BoardStore.Decision decided =
            board.decide(seat.topic(), request, approve, reason, conversation);
        return decided.approved()
            ? "Approved. Opened " + decided.child() + "; the requester holds its opener seat."
            : "Refused. " + decided.reason();
      } catch (Board.Refused refused) {
        return refused.getMessage();
      }
    }

    @Override
    public Optional<String> pass(String reason) {
      try {
        board.pass(seat.topic(), seat.occupant(), conversation, reason);
        return Optional.empty();
      } catch (Board.Refused refused) {
        return Optional.of(refused.getMessage());
      }
    }

    @Override
    public Optional<String> close(String resolution, List<String> cites) {
      try {
        board.close(
            new Board.Close(seat.topic(), authorKind(), author(), conversation, resolution, cites));
        return Optional.empty();
      } catch (Board.Refused refused) {
        return Optional.of(refused.getMessage());
      }
    }
  }

  private static String names(List<WakeRules.Wake> woken) {
    return woken.isEmpty()
        ? "nobody"
        : String.join(", ", woken.stream().map(WakeRules.Wake::occupant).toList());
  }
}
