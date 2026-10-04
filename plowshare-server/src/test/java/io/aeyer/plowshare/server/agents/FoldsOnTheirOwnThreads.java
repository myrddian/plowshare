package io.aeyer.plowshare.server.agents;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Where a fold runs when a test wants production's asynchrony: a virtual thread of its own, with
 * the handle kept so the test can join it before it returns.
 *
 * <h2>Why a test has to hold these at all</h2>
 *
 * <p><b>A fold outlives the turn that started it, and it used to outlive the test as well.</b>
 * {@link Compaction#close} is {@code shutdown()} and not {@code close()} — deliberately, because a
 * fold's only deadline is the transport's read timeout — so it takes no new folds and waits for
 * none. A test that ran a fold on a real thread released it and returned, and the fold went on to
 * write its {@code compactions} row and its {@code entries} while the <em>next</em> test's
 * {@code @BeforeEach} was running {@code TRUNCATE}.
 *
 * <p>Postgres calls that a deadlock and says so. Measured, twice, in this repository:
 *
 * <pre>
 * PessimisticLockingFailureException; StatementCallback;
 *   SQL [TRUNCATE TABLE skill_executions, command_invocations, conversations, turns, compactions, entries];
 *   ERROR: deadlock detected
 *   Detail: Process 459 waits for AccessExclusiveLock on relation 16837 (entries);
 *           blocked by process 461.
 *           Process 461 waits for RowShareLock on relation 16788 (conversations);
 *           blocked by process 459.
 * </pre>
 *
 * <p>Inserting into {@code entries} takes a lock on {@code conversations} for the foreign key,
 * while the {@code TRUNCATE} holds {@code conversations} — the first table it names — and wants
 * {@code entries}. Neither side can give way. It surfaces on the {@code TRUNCATE}, which means
 * <b>it fails a test that has nothing to do with it</b>: in {@code CompactionTest} it took down
 * {@code a_history_past_a_third_of_the_window_is_folded_although_it_would_still_fit}, and in the
 * {@code TurnTest} reproduction it took down {@code
 * a_ceiling_raised_while_a_turn_is_in_flight_is_picked_up_by_that_turn}.
 *
 * <p><b>Joining the thread, and not polling for the row.</b> The runnable {@code
 * foldWhenTheTurnIsOver} submits is the whole fold — every read, the model call, every write, and
 * the {@code finally} that releases the conversation — so a thread that has returned from it is a
 * fold that has finished. {@link Thread#join()} with no timeout is the whole wait; there is nothing
 * here to guess at and no duration to be generous about.
 *
 * <p><b>Shared between test classes, and that is the point of the type.</b> The leak is a property
 * of the wiring rather than of any assertion — it belongs to whoever chose a real thread, not to
 * whoever chose what to assert about it — so the guard sits beside the choice rather than being
 * rewritten by each test that makes it. {@code CompactionTest} found it first; {@code TurnTest} had
 * the same wiring and was saved only by an accident of its fixture.
 *
 * <p><b>A test using this must release whatever it held.</b> {@link #join()} waits with no timeout,
 * so a summarising call left waiting on a latch hangs the build rather than flaking it, which is
 * the trade this helper makes on purpose.
 */
final class FoldsOnTheirOwnThreads implements Executor {

  private final List<Thread> started = Collections.synchronizedList(new ArrayList<>());

  /**
   * Starts the fold on a virtual thread and keeps the handle.
   *
   * <p><b>It takes nothing away from what an asynchrony test proves.</b> The turn still does not
   * wait — this returns the instant the thread is started, which is what {@code
   * Executors.newVirtualThreadPerTaskExecutor} does too — and a test's assertions still run with
   * the fold in flight. The only thing that changed is that the <em>test</em> waits, in its
   * teardown, after it has finished asserting that the turn did not.
   */
  @Override
  public void execute(Runnable command) {
    Thread fold = Thread.ofVirtual().unstarted(command);
    started.add(fold);
    fold.start();
  }

  /**
   * Waits for every fold started so far, and forgets them.
   *
   * <p>Called from a teardown, and <b>first</b>: a fold still writing is the one thing in a test's
   * wreckage that can reach the next test.
   */
  void join() throws InterruptedException {
    for (Thread fold : List.copyOf(started)) {
      fold.join();
    }
    started.clear();
  }
}
