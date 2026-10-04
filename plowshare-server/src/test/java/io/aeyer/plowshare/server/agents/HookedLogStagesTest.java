package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ArchiveUnavailableException;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryRecord;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.events.InboxItem;
import io.aeyer.plowshare.server.events.InboxStore;
import io.aeyer.plowshare.server.hooks.ApprovalAnswer;
import io.aeyer.plowshare.server.hooks.Deadline;
import io.aeyer.plowshare.server.hooks.DeliveryPre;
import io.aeyer.plowshare.server.hooks.FoldPost;
import io.aeyer.plowshare.server.hooks.Handover;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.HookRecord;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.hooks.LogClosing;
import io.aeyer.plowshare.server.hooks.LogOpen;
import io.aeyer.plowshare.server.hooks.LogOpening;
import io.aeyer.plowshare.server.hooks.Notified;
import io.aeyer.plowshare.server.hooks.Stage;
import io.aeyer.plowshare.server.hooks.Summarised;
import io.aeyer.plowshare.server.hooks.Tier;
import io.aeyer.plowshare.server.llm.tokens.TokenCount;
import io.aeyer.plowshare.server.llm.tokens.Tokenizer;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Spec 2026-09-28-hooks-reach-the-log decisions 7–9, over a real Postgres and a Java hook layer.
 */
@Testcontainers
class HookedLogStagesTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Home LEDGER = Home.of("ledger");
  private static final Instant T0 = Instant.parse("2026-09-29T09:00:00Z");

  private static JdbcTemplate jdbc;

  private ConversationStore conversations;
  private TurnStore turns;
  private EntryStore entries;
  private InboxStore inbox;

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
  }

  @BeforeEach
  void fresh() {
    jdbc.execute("TRUNCATE TABLE user_inbox, entries, turns, conversations, admins CASCADE");
    jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('enzo', 'h')");
    conversations = new ConversationStore(jdbc);
    turns = new TurnStore(jdbc);
    entries = new EntryStore(jdbc);
    inbox = new InboxStore(jdbc);
  }

  private HookedLogStages over(Hooks hooks) {
    return over(hooks, conversation -> false, name -> false);
  }

  private HookedLogStages over(Hooks hooks, Predicate<String> speaking, Predicate<String> bots) {
    return over(hooks, turns, entries, speaking, bots);
  }

  private HookedLogStages over(
      Hooks hooks,
      TurnStore turns,
      EntryStore entries,
      Predicate<String> speaking,
      Predicate<String> bots) {
    return over(hooks, turns, entries, speaking, bots, Duration.ofSeconds(2));
  }

  private HookedLogStages over(
      Hooks hooks,
      TurnStore turns,
      EntryStore entries,
      Predicate<String> speaking,
      Predicate<String> bots,
      Duration timeLimit) {
    return new HookedLogStages(
        hooks,
        conversations,
        turns,
        speaking,
        entries,
        (handle, kind, text) -> inbox.notice(handle, kind, text, T0),
        bots,
        () -> T0,
        () -> timeLimit,
        new WordsTokenizer());
  }

  private static HookRecord said(Stage stage, String decision) {
    return new HookRecord(
        "fixture", "10-fixture.js", Tier.PROJECT, stage, null, decision, null, null, null, 0);
  }

  /** Adds at log.open, notes at delivery.pre, and notifies at log.close and delivery.post. */
  private static final Hooks EVERYTHING =
      new Hooks() {
        @Override
        public LogOpen logOpen(HookContext context, LogOpening opening) {
          return new LogOpen(
              List.of("rules for " + context.origin() + ", owned by " + opening.owner()),
              List.of(said(Stage.LOG_OPEN, HookRecord.ADD)));
        }

        @Override
        public Notified logClose(HookContext context, LogClosing closing) {
          return new Notified(
              List.of(
                  new Notified.Notice(
                      "fixture",
                      context.conversation()
                          + " ended "
                          + closing.ending()
                          + " after "
                          + closing.turns())),
              List.of(said(Stage.LOG_CLOSE, HookRecord.NOTIFY)));
        }

        @Override
        public DeliveryPre deliveryPre(HookContext context, Handover handover) {
          return new DeliveryPre(
              List.of("checked"), List.of(said(Stage.DELIVERY_PRE, HookRecord.NOTE)));
        }

        @Override
        public Notified deliveryPost(HookContext context, Handover handover) {
          return new Notified(
              List.of(new Notified.Notice("fixture", "sent to " + handover.destination())),
              List.of(said(Stage.DELIVERY_POST, HookRecord.NOTIFY)));
        }
      };

  private String submission(String owner) {
    return conversations.log(Origin.SUBMISSION, LEDGER, "scribe", null, Budget.of(5), owner).id();
  }

  private static LogStages.LogOpened opened(String log) {
    return new LogStages.LogOpened(log, Origin.SUBMISSION, LEDGER, "scribe", false, null);
  }

  private List<InboxItem> told() {
    return inbox.list("enzo", false, 0, 10);
  }

  @Test
  void log_open_fixes_the_additions_as_the_opening_and_records_at_turn_one() {
    String log = submission("enzo");

    over(EVERYTHING).opened(opened(log));

    assertEquals(Optional.of("rules for submission, owned by enzo"), conversations.opening(log));
    List<EntryRecord> written = entries.forConversation(log);
    assertEquals(List.of(EntryKind.HOOK), written.stream().map(EntryRecord::kind).toList());
    assertEquals(1, written.get(0).turnOrdinal(), "a log with no turns files its record at 1");
    assertTrue(entries.thatProjectFor(log).isEmpty(), "a HOOK entry never reaches the chat");
  }

  @Test
  void a_log_with_no_hooks_carries_no_opening_and_no_record() {
    String log = submission("enzo");

    over(Hooks.NONE).opened(opened(log));

    assertEquals(Optional.empty(), conversations.opening(log));
    assertTrue(entries.forConversation(log).isEmpty());
  }

  @Test
  void log_close_fires_once_at_the_latest_turn_and_tells_the_owner() {
    String log = submission("enzo");
    turns.record(log, "hi", "hello", Outcome.Ending.ANSWERED, null, "scribe", null);
    turns.record(log, "again", "hello again", Outcome.Ending.ANSWERED, null, "scribe", null);
    HookedLogStages stages = over(EVERYTHING);

    stages.closed(log, "answered");
    stages.closed(log, "answered");

    List<EntryRecord> written = entries.forConversation(log);
    assertEquals(1, written.size(), "log.close fires once per log");
    assertEquals(2, written.get(0).turnOrdinal(), "filed at the log's latest turn");
    assertEquals(1, told().size());
    assertEquals(InboxStore.KIND_HOOK, told().get(0).kind());
    assertEquals("fixture: " + log + " ended answered after 2", told().get(0).answer());
  }

  @Test
  void a_log_closed_inside_its_own_turn_files_at_that_turn_and_counts_it() {
    String log = submission("enzo");
    turns.record(log, "hi", "hello", Outcome.Ending.ANSWERED, null, "scribe", null);

    over(EVERYTHING, log::equals, name -> false).closed(log, "finished");

    List<EntryRecord> written = entries.forConversation(log);
    assertEquals(2, written.get(0).turnOrdinal(), "filed in the turn still in flight");
    assertEquals("fixture: " + log + " ended finished after 2", told().get(0).answer());
  }

  /**
   * Decision 7: the ending turn's row is written before its transcript closes the log, while the
   * log still reads as speaking, so the turn it names — not latest + 1 — is where the close lands
   * and what it counts.
   */
  @Test
  void a_log_its_ending_turn_closes_files_at_the_turn_it_names_and_counts_to_it() {
    String log = submission("enzo");
    turns.record(log, "do it", "may I?", Outcome.Ending.AWAITING, null, "scribe", null);
    turns.record(log, "yes", "done", Outcome.Ending.ANSWERED, null, "scribe", null);

    over(EVERYTHING, log::equals, name -> false).closed(log, "answered", 2);

    List<EntryRecord> written = entries.forConversation(log);
    assertEquals(1, written.size());
    assertEquals(2, written.get(0).turnOrdinal(), "filed at the ending turn, not one past it");
    assertEquals("fixture: " + log + " ended answered after 2", told().get(0).answer());
  }

  @Test
  void an_agent_registry_that_cannot_answer_reads_as_no_bot_and_the_stage_still_runs() {
    String log = submission("enzo");
    List<Boolean> bots = new ArrayList<>();
    Hooks watching =
        new Hooks() {
          @Override
          public Notified logClose(HookContext context, LogClosing closing) {
            bots.add(context.bot());
            return EVERYTHING.logClose(context, closing);
          }
        };

    over(
            watching,
            conversation -> false,
            name -> {
              throw new IllegalStateException("no agent registry in this context");
            })
        .closed(log, "answered");

    assertEquals(List.of(false), bots);
    assertEquals(1, entries.forConversation(log).size());
    assertEquals(1, told().size());
  }

  @Test
  void a_log_nobody_owns_records_the_notice_and_tells_nobody() {
    String log = submission(null);

    over(EVERYTHING).closed(log, "turn_cap");

    assertEquals(1, entries.forConversation(log).size());
    assertTrue(told().isEmpty());
  }

  @Test
  void a_person_s_conversation_closes_on_its_first_move_out_of_active_only() {
    String conversation = conversations.open(LEDGER, Budget.of(5), null, "enzo").id();
    HookedLogStages stages = over(EVERYTHING);

    stages.moved(conversations.moveTo(conversation, ConversationLifecycle.ARCHIVED));
    stages.moved(conversations.moveTo(conversation, ConversationLifecycle.ACTIVE));
    stages.moved(conversations.moveTo(conversation, ConversationLifecycle.ARCHIVED));

    assertEquals(
        List.of("fixture: " + conversation + " ended archived after 0"),
        told().stream().map(InboxItem::answer).toList());
  }

  @Test
  void a_delivery_note_reaches_the_text_and_both_records_land_in_the_source_log() {
    String source = submission("enzo");
    String elsewhere = conversations.open(LEDGER, Budget.of(5), null, "enzo").id();
    HookedLogStages stages = over(EVERYTHING);

    String delivered = stages.deliveryPre(source, Handover.INBOX, "the answer");
    stages.deliveryPost(source, Handover.INBOX, delivered);

    assertEquals("the answer\n\nchecked", delivered);
    assertEquals(
        List.of(EntryKind.HOOK, EntryKind.HOOK),
        entries.forConversation(source).stream().map(EntryRecord::kind).toList());
    assertTrue(entries.forConversation(elsewhere).isEmpty(), "never the destination's log");
    assertEquals("fixture: sent to inbox", told().get(0).answer());
  }

  @Test
  void a_hook_layer_that_throws_costs_the_transition_nothing() {
    Hooks throwing =
        new Hooks() {
          @Override
          public LogOpen logOpen(HookContext context, LogOpening opening) {
            throw new IllegalStateException("boom");
          }

          @Override
          public DeliveryPre deliveryPre(HookContext context, Handover handover) {
            throw new IllegalStateException("boom");
          }
        };
    String log = submission("enzo");
    HookedLogStages stages = over(throwing);

    stages.opened(opened(log));

    assertEquals(Optional.empty(), conversations.opening(log));
    assertEquals("t", stages.deliveryPre(log, Handover.INBOX, "t"));
  }

  @Test
  void two_additions_are_fixed_as_one_opening_a_blank_line_apart() {
    String log = submission("enzo");
    Hooks twice =
        new Hooks() {
          @Override
          public LogOpen logOpen(HookContext context, LogOpening opening) {
            return new LogOpen(List.of("first rule", "second rule"), List.of());
          }
        };

    over(twice).opened(opened(log));

    assertEquals(Optional.of("first rule\n\nsecond rule"), conversations.opening(log));
  }

  @Test
  void a_second_log_open_does_not_overwrite_the_opening_the_first_fixed() {
    String log = submission("enzo");
    AtomicInteger calls = new AtomicInteger();
    Hooks counting =
        new Hooks() {
          @Override
          public LogOpen logOpen(HookContext context, LogOpening opening) {
            return new LogOpen(List.of("opening " + calls.incrementAndGet()), List.of());
          }
        };
    HookedLogStages stages = over(counting);

    stages.opened(opened(log));
    stages.opened(opened(log));

    assertEquals(2, calls.get());
    assertEquals(Optional.of("opening 1"), conversations.opening(log));
  }

  /** Decision 8: user_inbox references admins, so a notice to an unknown owner is refused. */
  @Test
  void an_owner_the_inbox_refuses_costs_the_close_its_record_and_nothing_else() {
    String log = submission("ghost");
    HookedLogStages stages = over(EVERYTHING);

    stages.closed(log, "answered");
    stages.deliveryPost(log, Handover.INBOX, "t");

    assertEquals(2, entries.forConversation(log).size(), "both stages still recorded");
    assertTrue(told().isEmpty());
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM user_inbox", Integer.class));
  }

  @Test
  void an_append_that_fails_loses_that_record_and_not_the_next_or_the_notice() {
    String log = submission("enzo");
    EntryStore failingOnce = spy(entries);
    doThrow(new IllegalStateException("disk full"))
        .doCallRealMethod()
        .when(failingOnce)
        .append(anyString(), anyInt(), any(LoggedEntry.class));
    Hooks twoRecords =
        new Hooks() {
          @Override
          public Notified logClose(HookContext context, LogClosing closing) {
            return new Notified(
                List.of(new Notified.Notice("fixture", "closed")),
                List.of(
                    said(Stage.LOG_CLOSE, HookRecord.NOTIFY),
                    said(Stage.LOG_CLOSE, HookRecord.NOTIFY)));
          }
        };

    over(twoRecords, turns, failingOnce, conversation -> false, name -> false)
        .closed(log, "answered");

    assertEquals(1, entries.forConversation(log).size(), "the second record still lands");
    assertEquals(List.of("fixture: closed"), told().stream().map(InboxItem::answer).toList());
  }

  /** A turn that cannot be read loses the records, and nothing ordered after them. */
  @Test
  void a_turn_that_cannot_be_read_still_fixes_the_opening_notes_and_tells() {
    String log = submission("enzo");
    TurnStore unreadable = spy(turns);
    doThrow(new IllegalStateException("turns unreadable"))
        .when(unreadable)
        .latestOrdinal(anyString());
    HookedLogStages stages =
        over(EVERYTHING, unreadable, entries, conversation -> false, name -> false);

    stages.opened(opened(log));
    String delivered = stages.deliveryPre(log, Handover.INBOX, "the answer");
    stages.deliveryPost(log, Handover.INBOX, delivered);

    assertEquals(Optional.of("rules for submission, owned by enzo"), conversations.opening(log));
    assertEquals("the answer\n\nchecked", delivered);
    assertEquals(
        List.of("fixture: sent to inbox"), told().stream().map(InboxItem::answer).toList());
    assertTrue(entries.forConversation(log).isEmpty());
  }

  /**
   * Spec 2026-09-28-hooks-reach-the-log decision 7: the root conversation the approval is filed
   * against.
   */
  @Test
  void approval_post_records_in_the_root_conversation_and_tells_its_owner() {
    String root = submission("enzo");
    turns.record(root, "do it", "may I?", Outcome.Ending.AWAITING, null, "scribe", null);
    List<ApprovalAnswer> seen = new ArrayList<>();
    Hooks answered =
        new Hooks() {
          @Override
          public Notified approvalPost(HookContext context, ApprovalAnswer answer) {
            seen.add(answer);
            return new Notified(
                List.of(
                    new Notified.Notice(
                        "fixture",
                        context.conversation() + " " + answer.decision() + " " + answer.scope())),
                List.of(said(Stage.APPROVAL_POST, HookRecord.NOTIFY)));
          }
        };

    over(answered).approvalAnswered(root, "apr_1", ApprovalAnswer.ALLOW, "once");

    assertEquals(List.of(new ApprovalAnswer("apr_1", ApprovalAnswer.ALLOW, "once")), seen);
    List<EntryRecord> written = entries.forConversation(root);
    assertEquals(1, written.size());
    assertEquals(1, written.get(0).turnOrdinal(), "at the root's latest turn");
    assertEquals("fixture: " + root + " allow once", told().get(0).answer());
  }

  @Test
  void approval_post_for_a_log_that_is_not_there_does_nothing_and_throws_nothing() {
    over(EVERYTHING).approvalAnswered("cnv_gone", "apr_1", ApprovalAnswer.DENY, null);

    assertTrue(told().isEmpty());
  }

  private static final Summarised SUMMARISED =
      new Summarised(1, 2, 900, "They agreed to roll back.");

  /**
   * Spec 2026-09-28-hooks-reach-the-log decision 7 and §3, amended 2026-09-29: the folded log, at
   * the fold's own turn, and the kept text a blank line apart, for the fold to append.
   */
  @Test
  void fold_post_keeps_what_its_hooks_keep_and_records_at_the_turn_the_fold_names() {
    String log = submission("enzo");
    turns.record(log, "hi", "hello", Outcome.Ending.ANSWERED, null, "scribe", null);
    turns.record(log, "again", "hello again", Outcome.Ending.ANSWERED, null, "scribe", null);
    Hooks keeping =
        new Hooks() {
          @Override
          public FoldPost foldPost(HookContext context, Summarised summarised, Deadline deadline) {
            return new FoldPost(
                List.of(kept("skill a@1 was loaded"), kept("through " + summarised.through())),
                List.of(),
                List.of(said(Stage.FOLD_POST, HookRecord.KEEP)));
          }
        };

    LogStages.Held held = over(keeping).foldPost(log, 2, SUMMARISED);

    assertEquals("skill a@1 was loaded\n\nthrough 1", held.kept());
    List<EntryRecord> written = entries.forConversation(log);
    assertEquals(1, written.size());
    assertEquals(2, written.get(0).turnOrdinal(), "at the ordinal the fold files its diagnostic");
  }

  /**
   * Spec §3, amended 2026-09-29: fold.post's notices are held, and reach the owner only when the
   * fold says it was saved; its records are written when the hooks run.
   */
  @Test
  void fold_post_holds_its_notices_until_the_fold_says_it_was_saved() {
    String log = submission("enzo");
    Hooks notifying =
        new Hooks() {
          @Override
          public FoldPost foldPost(HookContext context, Summarised summarised, Deadline deadline) {
            return new FoldPost(
                List.of(),
                List.of(new Notified.Notice("fixture", "folded to " + summarised.through())),
                List.of(said(Stage.FOLD_POST, HookRecord.NOTIFY)));
          }
        };

    LogStages.Held held = over(notifying).foldPost(log, 0, SUMMARISED);

    assertEquals("", held.kept());
    assertTrue(told().isEmpty(), "nobody hears of a fold before it is saved");
    assertEquals(
        1,
        entries.forConversation(log).get(0).turnOrdinal(),
        "recorded now, and a turn of 0 files at 1, as every out-of-turn record does");

    held.tell().run();

    assertEquals("fixture: folded to 1", told().get(0).answer());
  }

  /**
   * {@link LogStages.Held}'s tell never throws: the owner is read when the fold is saved, and an
   * archive that cannot answer then loses the notices with a warning, not an exception.
   */
  @Test
  void telling_held_notices_does_not_throw_when_the_owner_cannot_be_read() {
    String log = submission("enzo");
    ConversationStore unreadable = spy(conversations);
    doThrow(new ArchiveUnavailableException("the archive is down", null))
        .when(unreadable)
        .ownerOf(anyString());
    Hooks notifying =
        new Hooks() {
          @Override
          public FoldPost foldPost(HookContext context, Summarised summarised, Deadline deadline) {
            return new FoldPost(
                List.of(), List.of(new Notified.Notice("fixture", "folded")), List.of());
          }
        };
    HookedLogStages stages =
        new HookedLogStages(
            notifying,
            unreadable,
            turns,
            conversation -> false,
            entries,
            (handle, kind, text) -> inbox.notice(handle, kind, text, T0),
            name -> false,
            () -> T0,
            () -> Duration.ofSeconds(2),
            new WordsTokenizer());

    LogStages.Held held = stages.foldPost(log, 1, SUMMARISED);

    assertDoesNotThrow(() -> held.tell().run());
    assertTrue(told().isEmpty());
  }

  @Test
  void a_fold_post_that_throws_keeps_nothing_and_holds_nobody_to_tell() {
    String log = submission("enzo");
    Hooks throwing =
        new Hooks() {
          @Override
          public FoldPost foldPost(HookContext context, Summarised summarised, Deadline deadline) {
            throw new IllegalStateException("boom");
          }
        };

    LogStages.Held held = over(throwing).foldPost(log, 0, SUMMARISED);
    held.tell().run();

    assertEquals("", held.kept());
    assertTrue(told().isEmpty());
  }

  /**
   * Isolates {@code atTurn} from {@code currentTurn}'s speaking-based guess: with the log reading
   * as speaking and 3 turns already written, {@code currentTurn} would compute 4, but the fold
   * names 2 explicitly and its records must land there, not at what "in flight" would give the
   * other stages.
   */
  @Test
  void fold_post_records_at_the_turn_the_fold_names_not_the_one_speaking_implies() {
    String log = submission("enzo");
    turns.record(log, "1", "a", Outcome.Ending.ANSWERED, null, "scribe", null);
    turns.record(log, "2", "b", Outcome.Ending.ANSWERED, null, "scribe", null);
    turns.record(log, "3", "c", Outcome.Ending.ANSWERED, null, "scribe", null);
    Hooks folding =
        new Hooks() {
          @Override
          public FoldPost foldPost(HookContext context, Summarised summarised, Deadline deadline) {
            return new FoldPost(
                List.of(),
                List.of(),
                List.of(
                    said(Stage.FOLD_POST, HookRecord.KEEP),
                    said(Stage.FOLD_POST, HookRecord.NOTIFY)));
          }
        };
    HookedLogStages stages = over(folding, log::equals, name -> false);

    stages.foldPost(log, 2, SUMMARISED);

    List<EntryRecord> written = entries.forConversation(log);
    assertEquals(2, written.size());
    assertEquals(
        List.of(2, 2),
        written.stream().map(EntryRecord::turnOrdinal).toList(),
        "both land at the turn the fold names (2), not latest + 1 (4) a speaking log implies");
  }

  /**
   * Spec decision 4, amended 2026-09-29: the stage starts one deadline from the configured hook
   * time limit and hands the whole chain that one.
   */
  @Test
  void fold_post_hands_its_chain_one_deadline_from_the_configured_limit() {
    String log = submission("enzo");
    List<Deadline> given = new ArrayList<>();
    Hooks capturing =
        new Hooks() {
          @Override
          public FoldPost foldPost(HookContext context, Summarised summarised, Deadline deadline) {
            given.add(deadline);
            return FoldPost.NOTHING;
          }
        };

    over(capturing, turns, entries, conversation -> false, name -> false, Duration.ofMillis(750))
        .foldPost(log, 1, SUMMARISED);

    assertEquals(1, given.size());
    assertEquals(Duration.ofMillis(750), given.get(0).limit());
    Duration left = given.get(0).remaining();
    assertTrue(
        left.compareTo(Duration.ZERO) > 0 && left.compareTo(Duration.ofMillis(750)) <= 0,
        "started from the configured limit, and not yet spent: " + left);
  }

  /**
   * Spec §3, amended 2026-09-29: what the hooks keep together is capped at 512 tokens, the blank
   * lines between keeps counted. Keeps are taken in chain order; one that would pass the cap is
   * dropped whole and recorded against its hook, and a later one that still fits is kept.
   */
  @Test
  void a_keep_that_would_pass_the_cap_is_dropped_whole_and_recorded_and_a_later_one_still_fits() {
    String log = submission("enzo");
    String first = WordsTokenizer.words(509);
    String tooMuch = WordsTokenizer.words(10);
    // 509 words, a blank line and 2 words: 512 exactly. The 10 words would have made 520.
    String last = "skill X@1";
    String past = "and one more";
    Hooks keeping =
        new Hooks() {
          @Override
          public FoldPost foldPost(HookContext context, Summarised summarised, Deadline deadline) {
            List<FoldPost.Kept> keeps =
                List.of(
                    kept("first", first),
                    kept("greedy", tooMuch),
                    kept("marker", last),
                    kept("late", past));
            return new FoldPost(
                keeps, List.of(), keeps.stream().map(HookedLogStagesTest::keptRecord).toList());
          }
        };

    LogStages.Held held = over(keeping).foldPost(log, 1, SUMMARISED);

    assertEquals(first + "\n\n" + last, held.kept(), "no keep is cut short");
    assertEquals(512, new WordsTokenizer().count(held.kept()).tokens());
    assertEquals(
        List.of(
            "first keep",
            "greedy keep",
            "greedy unused",
            "marker keep",
            "late keep",
            "late unused"),
        entries.forConversation(log).stream()
            .map(entry -> json(entry.content()))
            .map(record -> record.get("hook").asText() + " " + record.get("decision").asText())
            .toList(),
        "each dropped keep's record follows that hook's keep");
    List<JsonNode> dropped =
        entries.forConversation(log).stream()
            .map(entry -> json(entry.content()))
            .filter(record -> record.get("decision").asText().equals(HookRecord.UNUSED))
            .toList();
    assertEquals(
        List.of("greedy", "late"),
        dropped.stream().map(record -> record.get("hook").asText()).toList());
    assertEquals(
        List.of(tooMuch, past),
        dropped.stream().map(record -> record.get("original").asText()).toList());
    assertTrue(
        dropped.get(0).get("reason").asText().contains("past the cap of 512"),
        dropped.get(0).toString());
    assertEquals("fold.post", dropped.get(0).get("stage").asText());
    assertEquals("10-greedy.js", dropped.get(0).get("file").asText());
  }

  private static JsonNode json(String content) {
    try {
      return new ObjectMapper().readTree(content);
    } catch (IOException unreadable) {
      throw new AssertionError("a HOOK entry is not JSON: " + content, unreadable);
    }
  }

  /** A keep that cannot be counted cannot be shown to fit, so it is dropped and recorded too. */
  @Test
  void a_keep_the_tokenizer_cannot_count_is_dropped_and_recorded() {
    String log = submission("enzo");
    Hooks keeping =
        new Hooks() {
          @Override
          public FoldPost foldPost(HookContext context, Summarised summarised, Deadline deadline) {
            return new FoldPost(List.of(kept("marker", "skill X@1")), List.of(), List.of());
          }
        };
    HookedLogStages stages =
        new HookedLogStages(
            keeping,
            conversations,
            turns,
            conversation -> false,
            entries,
            (handle, kind, text) -> inbox.notice(handle, kind, text, T0),
            name -> false,
            () -> T0,
            () -> Duration.ofSeconds(2),
            new Tokenizer() {
              @Override
              public TokenCount count(String text) {
                throw new IllegalStateException("this server has no tokenizer");
              }

              @Override
              public String describe() {
                return "none";
              }
            });

    LogStages.Held held = stages.foldPost(log, 1, SUMMARISED);

    assertEquals("", held.kept());
    List<EntryRecord> written = entries.forConversation(log);
    assertEquals(1, written.size());
    assertTrue(
        written.get(0).content().contains("\"decision\":\"unused\""), written.get(0).content());
    assertTrue(written.get(0).content().contains("could not be counted"), written.get(0).content());
  }

  /** The keep record a hook's layer writes for {@code keep}, as ScriptHooks does. */
  private static HookRecord keptRecord(FoldPost.Kept keep) {
    return new HookRecord(
        keep.hook(),
        keep.file(),
        keep.tier(),
        Stage.FOLD_POST,
        null,
        HookRecord.KEEP,
        null,
        keep.text(),
        null,
        0);
  }

  private static FoldPost.Kept kept(String hook, String text) {
    return new FoldPost.Kept(hook, "10-" + hook + ".js", Tier.PROJECT, text);
  }

  private static FoldPost.Kept kept(String text) {
    return new FoldPost.Kept("fixture", "10-fixture.js", Tier.PROJECT, text);
  }

  private HookedLogStages over(Hooks hooks, LocalHooks pins) {
    return new HookedLogStages(
        hooks,
        conversations,
        turns,
        conversation -> false,
        entries,
        (handle, kind, text) -> inbox.notice(handle, kind, text, T0),
        name -> false,
        () -> T0,
        () -> Duration.ofSeconds(2),
        new WordsTokenizer(),
        pins);
  }

  /**
   * Spec 2026-09-30-local-hooks-are-served decision 3: pinned first, so the local tier sees its own
   * log.open.
   */
  @Test
  void a_log_s_local_hooks_are_pinned_before_log_open_fires_and_a_failed_pin_is_recorded() {
    String log =
        conversations.log(Origin.SUBMISSION, LEDGER, "scribe", null, Budget.of(5), "enzo").id();
    List<String> order = new ArrayList<>();
    LocalHooks pins =
        opened -> {
          order.add("pin " + opened.session());
          return List.of(LocalHooks.unpinned("the session went"));
        };
    Hooks hooks =
        new Hooks() {
          @Override
          public LogOpen logOpen(HookContext context, LogOpening opening) {
            order.add("log.open");
            return LogOpen.NOTHING;
          }
        };

    over(hooks, pins)
        .opened(
            new LogStages.LogOpened(
                log, Origin.SUBMISSION, LEDGER, "scribe", false, null, "tui-1", null));

    assertEquals(List.of("pin tui-1", "log.open"), order);
    List<EntryRecord> recorded = entries.forConversation(log);
    assertEquals(1, recorded.size());
    String content = recorded.get(0).content();
    assertTrue(
        content.contains("\"tier\":\"local\"")
            && content.contains("the session went")
            && content.contains("\"stage\":\"log.open\""),
        content);
  }

  @Test
  void a_pin_that_breaks_its_contract_still_lets_the_log_open() {
    String log =
        conversations.log(Origin.SUBMISSION, LEDGER, "scribe", null, Budget.of(5), "enzo").id();
    LocalHooks throwing =
        opened -> {
          throw new IllegalStateException("boom");
        };
    Hooks adding =
        new Hooks() {
          @Override
          public LogOpen logOpen(HookContext context, LogOpening opening) {
            return new LogOpen(List.of("rules"), List.of());
          }
        };

    over(adding, throwing)
        .opened(
            new LogStages.LogOpened(
                log, Origin.SUBMISSION, LEDGER, "scribe", false, null, "tui-1", null));

    assertEquals(Optional.of("rules"), conversations.opening(log));
    assertTrue(
        entries.forConversation(log).stream()
            .anyMatch(
                entry ->
                    entry.content().contains("\"tier\":\"local\"")
                        && entry.content().contains("boom")));
  }
}
