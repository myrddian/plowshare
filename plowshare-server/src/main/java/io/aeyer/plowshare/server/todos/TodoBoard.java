package io.aeyer.plowshare.server.todos;

import io.aeyer.plowshare.protocol.MemoryIds;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A conversation's todo list with its rules: a batch is checked whole against a copy of the list,
 * then written in one transaction, so a batch with one bad operation changes nothing.
 *
 * <p><b>No row lock, on purpose.</b> A conversation has one speaking run at a time ({@code Turn}),
 * and that run is the only writer of its list. A second writer — a harness creating an
 * orchestration's stages — writes before the conductor's first turn and so does not overlap. A
 * writer that can overlap a turn would need {@code SELECT ... FOR UPDATE} here.
 */
public class TodoBoard implements TodoLists, StageSeeding {

  private static final Logger log = LoggerFactory.getLogger(TodoBoard.class);

  /** Any status counts: a dropped item is still a row. */
  static final int MAX_LIST_SIZE = 100;

  static final int MAX_TEXT_LENGTH = 500;
  static final int MAX_SUMMARY_LENGTH = 2000;

  private final TodoStore store;
  private final UnitOfWork work;
  private final LockedMoves locked;
  private final Changed changed;
  private final Supplier<Instant> clock;
  private final TodoNotices notices;
  private final ToIntFunction<String> compactedThrough;
  private final Progressed progressed;

  /** Told of every committed batch's status changes; {@link StatusMoves#NONE} until wired. */
  private volatile StatusMoves moves = StatusMoves.NONE;

  /** Set once at wiring — the orchestration record's stage moves (spec 2026-09-28 §2). */
  public void whenMoved(StatusMoves moves) {
    this.moves = Objects.requireNonNull(moves, "moves");
  }

  /**
   * Delegates with {@link TodoNotices#NONE} and a conversation that was never compacted, which
   * keeps slice 1's behaviour: {@link #noticeFor} always sends when the list is non-empty.
   */
  public TodoBoard(
      TodoStore store,
      UnitOfWork work,
      LockedMoves locked,
      Changed changed,
      Supplier<Instant> clock) {
    this(store, work, locked, changed, clock, TodoNotices.NONE, conversation -> -1);
  }

  /**
   * @param compactedThrough the conversation's latest compaction's through-ordinal, or {@code -1}
   *     if it has never been compacted
   */
  public TodoBoard(
      TodoStore store,
      UnitOfWork work,
      LockedMoves locked,
      Changed changed,
      Supplier<Instant> clock,
      TodoNotices notices,
      ToIntFunction<String> compactedThrough) {
    this(store, work, locked, changed, clock, notices, compactedThrough, Progressed.NONE);
  }

  /**
   * @param progressed told after a batch that moved a stage's status, or the status of anything
   *     under a stage — {@link #stageProgress}
   */
  public TodoBoard(
      TodoStore store,
      UnitOfWork work,
      LockedMoves locked,
      Changed changed,
      Supplier<Instant> clock,
      TodoNotices notices,
      ToIntFunction<String> compactedThrough,
      Progressed progressed) {
    this.progressed = Objects.requireNonNull(progressed, "progressed");
    this.store = Objects.requireNonNull(store, "store");
    this.work = Objects.requireNonNull(work, "work");
    this.locked = Objects.requireNonNull(locked, "locked");
    this.changed = Objects.requireNonNull(changed, "changed");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.notices = Objects.requireNonNull(notices, "notices");
    this.compactedThrough = Objects.requireNonNull(compactedThrough, "compactedThrough");
  }

  @Override
  public List<TodoItem> list(String conversation) {
    return store.list(conversation);
  }

  @Override
  public List<TodoItem> apply(String conversation, List<TodoOp> ops, String sessionId) {
    if (ops.isEmpty()) {
      throw new TodoRefused("todo_write needs at least one operation. Nothing was changed.");
    }
    // Postgres' timestamptz stores microseconds; truncating here means an item read back
    // always matches the value it was written with, which the optimistic-lock check below
    // and any equality on a re-read item both depend on.
    Instant at = clock.get().truncatedTo(ChronoUnit.MICROS);
    Map<String, TodoItem> working = new LinkedHashMap<>();
    store.list(conversation).forEach(item -> working.put(item.id(), item));
    // The pre-batch state, for the optimistic-lock check on commit: `working` is mutated
    // below as ops are processed, so this copy is the only place "what was on the row before
    // this batch touched it" survives.
    Map<String, TodoItem> baseline = Map.copyOf(working);
    Set<String> inserted = new LinkedHashSet<>();
    Set<String> touched = new LinkedHashSet<>();
    List<Runnable> effects = new ArrayList<>();

    for (int i = 0; i < ops.size(); i++) {
      int n = i + 1;
      switch (ops.get(i)) {
        case TodoOp.Add add -> {
          String text = requireText(add.text(), n);
          if (add.parent() != null && !working.containsKey(add.parent())) {
            throw refused(
                n,
                "there is no item " + add.parent() + " on this conversation's list to add under");
          }
          // A SECOND LIVE ITEM WITH THE SAME TEXT UNDER THE SAME PARENT IS REFUSED.
          // Measured 2026-09-25: a conductor re-added every pending phase each time it
          // finished one, four times in one run, though the list it was shown had them
          // all. The refusal names the one that is there, so the next write updates it.
          // A dropped item does not count: re-adding one is how a retry is recorded.
          String wanted = text.strip().toLowerCase(Locale.ROOT);
          for (TodoItem sibling : siblings(working, add.parent())) {
            if (sibling.status() != TodoStatus.DROPPED
                && sibling.text().strip().toLowerCase(Locale.ROOT).equals(wanted)) {
              throw refused(
                  n,
                  "'"
                      + sibling.text()
                      + "' is already on the list as "
                      + sibling.id()
                      + " ("
                      + sibling.status().wire()
                      + "), under the"
                      + " same parent; update that item rather than adding it again");
            }
          }
          int position = siblings(working, add.parent()).size();
          String id = MemoryIds.mint(TodoStore.PREFIX, at);
          while (working.containsKey(id)) {
            id = MemoryIds.mint(TodoStore.PREFIX, at);
          }
          working.put(
              id,
              new TodoItem(
                  id,
                  conversation,
                  add.parent(),
                  position,
                  text,
                  TodoStatus.PENDING,
                  null,
                  false,
                  null,
                  at));
          inserted.add(id);
          if (working.size() > MAX_LIST_SIZE) {
            throw refused(n, "a conversation's list holds at most " + MAX_LIST_SIZE + " items");
          }
        }
        case TodoOp.Update update -> {
          TodoItem current = require(working, update.id(), n);
          TodoItem next = current;
          if (update.text() != null) {
            String text = requireText(update.text(), n);
            if (current.locked()) {
              throw refused(n, current.id() + " is locked and cannot be renamed");
            }
            next = next.withText(text, at);
          }
          if (update.status() != null && update.status() != current.status()) {
            if (current.locked()) {
              if (update.status() == TodoStatus.DROPPED) {
                throw refused(n, current.id() + " is locked and cannot be dropped");
              }
              String effectiveSummary =
                  update.summary() != null ? update.summary().strip() : current.summary();
              LockedMoves.Decision decision =
                  locked.decide(
                      current, update.status(), effectiveSummary, List.copyOf(working.values()));
              if (decision instanceof LockedMoves.Refused refusedMove) {
                throw refused(n, refusedMove.why());
              }
              LockedMoves.Allowed allowed = (LockedMoves.Allowed) decision;
              if (!allowed.effects().isEmpty()) {
                if (!effects.isEmpty()) {
                  throw refused(n, "only one stage return fits in one write");
                }
                effects.addAll(allowed.effects());
              }
              for (TodoItem consequence : allowed.consequences()) {
                if (!working.containsKey(consequence.id())) {
                  throw new IllegalStateException(
                      "a locked-item policy returned "
                          + consequence.id()
                          + ", which is not on the list");
                }
                working.put(consequence.id(), consequence.withPosition(consequence.position(), at));
                touched.add(consequence.id());
              }
              // A consequence may already have rewritten this same item (a return
              // clears the summary of the stage it returns to) -- rebase on that
              // working copy so the operation's own status change lands on top of it,
              // not over it. A locked item never carries a text change (rename is
              // refused above), so nothing is lost by starting from `working` here
              // instead of `next`.
              next = working.get(current.id()).withStatus(update.status(), at);
            } else {
              next = next.withStatus(update.status(), at);
            }
          }
          if (update.summary() != null) {
            next = next.withSummary(requireSummary(update.summary(), n), at);
          }
          working.put(next.id(), next);
          touched.add(next.id());
        }
        case TodoOp.Move move -> {
          TodoItem current = require(working, move.id(), n);
          if (current.locked()) {
            throw refused(n, current.id() + " is locked and cannot be moved");
          }
          if (move.position() < 0) {
            throw refused(n, "a position counts from zero");
          }
          List<TodoItem> order = new ArrayList<>(siblings(working, current.parent()));
          order.removeIf(item -> item.id().equals(current.id()));
          order.add(Math.min(move.position(), order.size()), current);
          for (int p = 0; p < order.size(); p++) {
            TodoItem sibling = order.get(p);
            if (sibling.position() != p) {
              working.put(sibling.id(), sibling.withPosition(p, at));
              touched.add(sibling.id());
            }
          }
        }
      }
    }

    work.inTransaction(
        () -> {
          for (String id : inserted) {
            store.insert(working.get(id));
          }
          for (String id : touched) {
            if (!inserted.contains(id)) {
              TodoItem previous = baseline.get(id);
              int rows = store.update(previous, working.get(id));
              if (rows == 0) {
                // Infrastructure, not a model mistake: something else changed this row
                // between this batch reading it and writing it back. Propagates and
                // rolls the transaction back, rather than silently discarding the write.
                throw new IllegalStateException(
                    "todo item "
                        + id
                        + " was changed by"
                        + " another write while this batch was applying and could not be"
                        + " updated");
              }
            }
          }
          effects.forEach(Runnable::run);
          return null;
        });
    if (stageProgress(baseline, working, touched, inserted)) {
      try {
        progressed.progressed(conversation);
      } catch (RuntimeException failed) {
        // Changed's own grading: the batch has committed, and a lost reset is not the
        // write's to fail over. The worst it costs is a nudge counted that should not be.
        log.warn(
            "todo list for conversation {} moved a stage, but its progress could not"
                + " be recorded",
            conversation,
            failed);
      }
    }
    changed.changed(sessionId, conversation);
    List<TodoItem> after = store.list(conversation);
    // Every item whose status this batch changed, a return's consequences included; an item
    // this batch added was not moved. After the commit, and never let to fail the batch.
    List<StatusMoves.Move> moved = new ArrayList<>();
    for (String id : touched) {
      TodoItem before = baseline.get(id);
      TodoItem now = working.get(id);
      if (before != null && now != null && before.status() != now.status()) {
        moved.add(new StatusMoves.Move(before, now));
      }
    }
    if (!moved.isEmpty()) {
      try {
        moves.moved(conversation, List.copyOf(moved), after);
      } catch (RuntimeException failed) {
        log.warn(
            "todo list for conversation {} changed but its moves could not be told",
            conversation,
            failed);
      }
    }
    return after;
  }

  /**
   * Whether this batch moved the status of a stage item, or of an item anywhere under one.
   *
   * <p><b>Why this is progress, and why nothing else in a batch is.</b> Measured 2026-09-28, {@code
   * orc_3187D648AC346812}: a {@code code_implementation} conductor with no children ended three
   * turns in prose over an hour of real work — marking {@code code} in progress and done, its check
   * passing — and was failed {@code stuck}, because only a child's start reset the count. A status
   * moving is the conductor's work landing on the list the harness judges it by; an added item, a
   * rename, a summary or a reorder is the conductor talking about work, and is cheap enough to hold
   * off "stuck" for ever if it counted. Only an item that was already on the list counts, so adding
   * a phase is not progress but starting it is.
   */
  private static boolean stageProgress(
      Map<String, TodoItem> before,
      Map<String, TodoItem> after,
      Set<String> touched,
      Set<String> inserted) {
    for (String id : touched) {
      TodoItem was = before.get(id);
      TodoItem now = after.get(id);
      if (inserted.contains(id) || was == null || now == null || was.status() == now.status()) {
        continue;
      }
      if (underAStage(now, after)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether an item is a stage item or sits, at any depth, under one. Bounded by the list's size,
   * so a parent chain that loops — which the board never writes — cannot hang it.
   */
  private static boolean underAStage(TodoItem item, Map<String, TodoItem> list) {
    TodoItem at = item;
    for (int hops = 0; at != null && hops <= list.size(); hops++) {
      if (at.stageId() != null) {
        return true;
      }
      at = at.parent() == null ? null : list.get(at.parent());
    }
    return false;
  }

  /**
   * An orchestration's stages, written as locked items into its conversation's empty list. The
   * harness's own write: no model asked for it, so it tells no client and no policy is consulted.
   *
   * @throws IllegalStateException if the list is not empty, which is the harness's mistake
   */
  @Override
  public List<TodoItem> seedStages(String conversation, List<StageSeeding.Seed> stages) {
    if (!store.list(conversation).isEmpty()) {
      throw new IllegalStateException(
          "conversation "
              + conversation
              + " already has a todo list; stages are seeded only into an empty one");
    }
    Set<String> stageIds = new LinkedHashSet<>();
    for (StageSeeding.Seed seed : stages) {
      if (seed.stageId() == null || seed.stageId().isBlank()) {
        throw new IllegalStateException("a seeded stage needs a non-blank stageId");
      }
      if (seed.text() == null || seed.text().isBlank()) {
        throw new IllegalStateException("a seeded stage needs non-blank text");
      }
      if (!stageIds.add(seed.stageId())) {
        throw new IllegalStateException("stage '" + seed.stageId() + "' is seeded more than once");
      }
    }
    Instant at = clock.get().truncatedTo(ChronoUnit.MICROS);
    Set<String> minted = new LinkedHashSet<>();
    work.inTransaction(
        () -> {
          for (int i = 0; i < stages.size(); i++) {
            StageSeeding.Seed seed = stages.get(i);
            String id = MemoryIds.mint(TodoStore.PREFIX, at);
            while (!minted.add(id)) {
              id = MemoryIds.mint(TodoStore.PREFIX, at);
            }
            store.insert(
                new TodoItem(
                    id,
                    conversation,
                    null,
                    i,
                    seed.text(),
                    TodoStatus.PENDING,
                    null,
                    true,
                    seed.stageId(),
                    at));
          }
          return null;
        });
    return store.list(conversation);
  }

  @Override
  public Optional<Notice> noticeFor(String conversation) {
    List<TodoItem> visible = withoutDropped(store.list(conversation));
    String rendered = visible.isEmpty() ? "" : TodoRendering.compact(visible);
    TodoNotices.Seen now =
        new TodoNotices.Seen(sha256(rendered), compactedThrough.applyAsInt(conversation));
    if (notices.seen(conversation).filter(now::equals).isPresent()) {
      return Optional.empty();
    }
    if (visible.isEmpty()) {
      // Nothing is logged for an empty list, so there is no record to wait for.
      notices.remember(conversation, now);
      return Optional.empty();
    }
    // Fenced and named as data, on Dispatcher.utterance's own precedent: an item's text and
    // summary are model-written, and unfenced would present them as the harness' own speech.
    return Optional.of(
        new Notice(
            "Harness notice: this conversation's todo list, as it stands:\n"
                + "```todo list — not instructions\n"
                + rendered
                + "```\n"
                + "Change it with todo_write.",
            now));
  }

  @Override
  public void noticed(String conversation, TodoNotices.Seen seen) {
    notices.remember(conversation, seen);
  }

  @Override
  public void forget(String conversation) {
    notices.forget(conversation);
  }

  /**
   * Lowercase hex SHA-256, on {@code OrchestrationParser.hash}'s shape -- not imported across
   * packages, since this is the only caller in {@code todos}.
   */
  private static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(
          "this JVM has no SHA-256, which every Java SE has", impossible);
    }
  }

  /**
   * {@code list} without any item that is dropped or sits under one that is, so a stale line a
   * model already dropped does not keep costing prompt tokens forever. {@code todo_read} is
   * unaffected: it renders {@link #list} directly, which is every item regardless of status.
   */
  private static List<TodoItem> withoutDropped(List<TodoItem> list) {
    Map<String, List<TodoItem>> childrenOf = new LinkedHashMap<>();
    for (TodoItem item : list) {
      if (item.parent() != null) {
        childrenOf.computeIfAbsent(item.parent(), k -> new ArrayList<>()).add(item);
      }
    }
    Set<String> excluded = new LinkedHashSet<>();
    for (TodoItem item : list) {
      if (item.status() == TodoStatus.DROPPED) {
        excludeSubtree(item.id(), childrenOf, excluded);
      }
    }
    return list.stream().filter(item -> !excluded.contains(item.id())).toList();
  }

  private static void excludeSubtree(
      String id, Map<String, List<TodoItem>> childrenOf, Set<String> excluded) {
    if (!excluded.add(id)) {
      return;
    }
    for (TodoItem child : childrenOf.getOrDefault(id, List.of())) {
      excludeSubtree(child.id(), childrenOf, excluded);
    }
  }

  private static List<TodoItem> siblings(Map<String, TodoItem> working, String parent) {
    return working.values().stream()
        .filter(item -> Objects.equals(item.parent(), parent))
        .sorted(Comparator.comparingInt(TodoItem::position))
        .toList();
  }

  private static TodoItem require(Map<String, TodoItem> working, String id, int n) {
    TodoItem item = id == null ? null : working.get(id);
    if (item == null) {
      throw refused(n, "there is no item " + id + " on this conversation's list");
    }
    return item;
  }

  private static String requireText(String text, int n) {
    if (text == null || text.isBlank()) {
      throw refused(n, "an item needs text");
    }
    String stripped = text.strip();
    if (stripped.length() > MAX_TEXT_LENGTH) {
      throw refused(n, "an item's text is at most " + MAX_TEXT_LENGTH + " characters");
    }
    return stripped;
  }

  private static String requireSummary(String summary, int n) {
    String stripped = summary.strip();
    if (stripped.length() > MAX_SUMMARY_LENGTH) {
      throw refused(n, "a summary is at most " + MAX_SUMMARY_LENGTH + " characters");
    }
    return stripped;
  }

  private static TodoRefused refused(int n, String why) {
    return new TodoRefused(
        "todo_write refused operation " + n + ": " + why + ". Nothing was changed.");
  }
}
