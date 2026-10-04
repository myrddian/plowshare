package io.aeyer.plowshare.client.cli;

import io.aeyer.plowshare.client.ServerClient;
import io.aeyer.plowshare.client.SessionClient;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

/**
 * A conversation held in a terminal: read a line, submit a turn, render its events, print what came
 * back, repeat.
 *
 * <h2>This extends the terminal rather than replacing it</h2>
 *
 * <p>{@link Plowshare} already opens a session, attaches both roles, renders a run's lifecycle with
 * two clocks and a {@code waiting} line, and reports an outcome without ever dressing a truncated
 * run as an answer. All of that is a turn's, unchanged: {@link Plowshare#follow} is the same loop
 * with the same argument about the job endpoint deciding when to stop, and {@link Plowshare#say} is
 * the same reading of {@code answered}. What this class adds is everything <em>around</em> a run —
 * a conversation to put it in, a person to hear from while it goes, and the fact that it comes back
 * and asks for another.
 *
 * <h2>What a person sees</h2>
 *
 * <pre>
 *   session 1a2b3c4d… → http://127.0.0.1:8080/
 *   lending [/Users/me/proj/pay]
 *   conversation cnv_000007 — interlocutor, 40 model calls for the whole of it, in
 *       the global tier
 *   a sentence and return speaks. :stop interrupts a turn, :quit leaves. ctrl-C
 *       leaves at once and sends no cancel.
 *
 *   › what does the retry code actually retry on?
 *   job job_000001
 *      0.0s  +0.0s  started      interlocutor
 *      0.5s  +0.5s  model call   turn 1, 1 model call
 *      2.9s  +2.4s  tool called  file_read
 *     13.0s +10.1s  waiting      still nothing since tool called file_read
 *     18.4s +15.5s  ended        ANSWERED after 2 turns, 2 model calls
 *   ANSWERED after 2 turns, 2 model calls
 *
 *   Only on a 5xx. Retry.java:41 catches IOException and rethrows.
 *
 *   › and on a timeout?
 *   ...
 * </pre>
 *
 * <p><b>The ending is printed twice and that is not a slip.</b> The indented one is an event, which
 * may not arrive — the stream is droppable by design. The plain one is read from {@code GET
 * /v1/jobs/&#123;id&#125;}, the only contractual record of a run. A REPL that printed only the
 * first would go quiet on exactly the turn whose socket dropped.
 *
 * <h2>Which agent, and the first shipped name in Java</h2>
 *
 * <p>{@link #INTERLOCUTOR} is the first agent name anything in this repository hardcodes. Nothing
 * else does: {@code Turn.speak} takes an {@code AgentDefinition} from its caller, and {@code
 * AgentController} reads the name out of a path. <b>So it is named here and it is also a flag, and
 * both halves are load-bearing.</b>
 *
 * <ul>
 *   <li><b>Named, because a REPL that demanded one would make the first thing a person types a
 *       lookup rather than a sentence.</b> There is exactly one shipped definition for being talked
 *       to and its own description says so; asking which of four agents they meant, when three of
 *       them are a scribe, a promotion judge and a reviewer that cannot see the conversation, is a
 *       question with one answer.
 *   <li><b>A flag, because a conversation names no agent and must not start to.</b> {@code
 *       ConversationController} says it plainly: the agent is named per turn, which is what lets
 *       one conversation hold two agents' turns. A hardcode with no way past it would make this
 *       terminal the one caller that cannot do that, and would put a shipped file's name into a
 *       client that ships separately from it — so a deployment whose agents directory does not hold
 *       {@code interlocutor.md} would have a REPL that cannot be used at all rather than one that
 *       needs an argument.
 * </ul>
 *
 * <p>What it is <em>not</em> is a name the protocol learns. The server still takes it per turn, in
 * the path, and this constant is a default in one client.
 *
 * <h2>Interruption, and the two things ctrl-C is not</h2>
 *
 * <p>{@link #STOP} typed while a turn is going is {@code POST /v1/jobs/&#123;id&#125;/cancel}: the
 * run stops at its next turn boundary, ends {@code CANCELLED}, and <b>the conversation goes on</b>
 * — a turn's ending is a fact about that turn, which is why {@code Turn} writes the spending back
 * for every one of the seven and why this loop asks for another line afterwards.
 *
 * <p>It is read between polls of the event stream, from the same input the utterances come from, so
 * it needs no second thread and no signal handler. <b>A line that is not {@link #STOP} is put back
 * rather than consumed</b>, and that is deliberate: a person typing ahead, and a script piping
 * lines in, are the same thing to this loop, and eating the next utterance because it happened to
 * arrive early would make {@code plowshare --talk < script} answer one line in two.
 *
 * <p><b>There is no shutdown hook and ctrl-C sends no cancel.</b> A hook would have to make an HTTP
 * call while the JVM is exiting, on a client whose read timeout is a minute, which turns ctrl-C
 * into a terminal that will not close.
 *
 * <p>So the banner says what ctrl-C does, and says it exactly rather than comfortably. <b>It is not
 * "the turn keeps going", because that is only usually true.</b> Leaving closes both sockets, and
 * closing a socket is not what ends a run — {@code Outcome.Ending.SESSION_GONE} comes from a file
 * request that cannot be served and from nothing else, which is the rule {@link
 * Plowshare#stillRunning} already states to somebody who lost the server. So a turn that never asks
 * this machine for a file runs to its own ending and spends what it spends, and a turn that does
 * asks ends {@code SESSION_GONE}. Neither is a cancel. {@link #STOP} is the one that is, made while
 * there is still somebody to tell about it.
 *
 * <h2>What a cancelled turn contributes to the history</h2>
 *
 * <p>The spec leaves this open and warns which way is wrong: "the partial answer is not an answer,
 * and threading it as one would teach the model that its own interrupted output is something it
 * said and meant."
 *
 * <p><b>The decision: a cancelled turn contributes the utterance that was made and the fact that it
 * was cancelled, and never a word the model produced.</b> Not a partial answer, not a truncation
 * marker around one, and not nothing either — an utterance with no reply after it is a conversation
 * that never took place, and the next turn reading one would answer as though the person had not
 * spoken.
 *
 * <p><b>It is already true by construction, which is why nothing here implements it</b> — and that
 * was measured rather than assumed, because a decision that turns out to be the existing behaviour
 * is exactly the one that gets recorded and then quietly reverted:
 *
 * <ul>
 *   <li>{@code JobRuntime.stopped} builds every non-answer ending and <b>has no parameter a model's
 *       prose could be passed in</b>. {@code
 *       JobRuntimeTest.no_ending_but_answered_carries_the_model_s_last_prose} drives {@code
 *       CANCELLED} with the model saying a distinctive sentence and refuses to find it in the
 *       outcome;
 *   <li>{@code Turn.writeDown} records {@code outcome.text()} for every ending, and {@code
 *       CompactionTest.a_turn_that_did_not_answer_is_still_written_into_the_transcript} pins that
 *       it is the outcome's own text and not a special case, over a turn that stopped;
 *   <li>{@code Compaction.messages} threads that text as the assistant's half of the turn, so what
 *       the model is shown for a cancelled turn is the sentence <em>the server</em> wrote about the
 *       ending — "This run was cancelled after 2 turns and did not reach an answer" — with the
 *       tools it called.
 * </ul>
 *
 * <p>What this class adds is the person's half of the same rule, and it is the half that could have
 * gone wrong here. {@link Plowshare#say} prints a stopping turn from {@code ending}, {@code turns}
 * and {@code modelCalls} and never from {@code text}. The person is told the same fact the model is
 * told, in this terminal's words rather than the server's; neither of them is shown what the model
 * had got as far as saying.
 *
 * <h2>Seeing a compaction, which is the one thing a speaker cannot</h2>
 *
 * <p>Everything else about a turn reaches this terminal already — it typed the utterance, watched
 * the events and read the answer — so its own scrollback is the transcript. A fold is different: it
 * happens inside the next turn, before that turn's first model call, publishes no {@code JobEvent},
 * and is not in the model calls the outcome reports. Nothing on the wire changes, and the design's
 * whole mitigation for a summary that quietly drops what mattered is that a person "can see the
 * seam and read what was behind it".
 *
 * <p>So after every turn this asks {@code GET /v1/conversations/&#123;id&#125;/compactions} and
 * prints any seam it has not printed before. What is behind the seam is above it on the same
 * screen.
 *
 * <h2>Exit status, and the one it deliberately does not use</h2>
 *
 * <p>{@code 0} when the person left — {@link #QUIT} or end of input. {@code 1} when the
 * conversation ended and they did not end it: the server refused an utterance because the budget it
 * was opened with is gone. {@code 2} for anything that stopped this holding a conversation at all.
 *
 * <p><b>A turn that did not answer is none of those.</b> The one-shot terminal exits 1 for a run
 * that stopped, because that run was the whole errand; here it is one utterance of many, and the
 * spec is explicit that "a REPL that silently retried, or that died on the first capped turn, would
 * both be lying about what happened". So a {@code TURN_CAP}, a {@code CANCELLED} or an {@code
 * UNAVAILABLE} is printed and the prompt comes back.
 */
final class Repl {

  /**
   * The agent a person talks to when they name none.
   *
   * <p>Spelled as it is on disk, minus the extension, because that is what the server reads it as:
   * {@code AgentRegistry} keys a definition by the {@code name:} in its front matter and {@code
   * agents.Callers} looks up the one the path names. A mismatch is a 400 naming the agents that
   * exist, which is the correction rather than a mystery.
   */
  static final String INTERLOCUTOR = "interlocutor";

  /** Typed while a turn is running: ask the server to stop it. */
  static final String STOP = ":stop";

  /** Typed at the prompt: leave. */
  static final String QUIT = ":quit";

  /**
   * The prompt, and it ends without a newline so that what a person types sits on the same line as
   * the mark that asked for it.
   */
  private static final String PROMPT = "› ";

  /**
   * How far {@link BufferedReader#reset} may have to reach back to put an early line where it was
   * found.
   *
   * <p>A megabyte, which is not a guess about how much anybody types: the buffer grows to hold what
   * is actually read and this only bounds how much it is <em>allowed</em> to grow before {@code
   * reset} gives up. What it has to cover is one line, and the line it has to cover is a paste — a
   * stack trace, a file, a diff — because those are what a person puts in front of an agent that
   * reads code. A limit small enough to be exceeded would lose that paste rather than truncating
   * it.
   */
  private static final int PUT_BACK = 1 << 20;

  private Repl() {}

  /**
   * Hold the conversation until the person leaves it or something stops this.
   *
   * @param session already attached in both roles, and still owned by the caller — every turn is a
   *     job on it, and closing it is what ends the lending
   * @param server the same server the session's runs go to, for the two things that are not a job:
   *     cancelling one, and asking what has been folded
   * @param conversation what {@code POST /v1/conversations} answered. Its id is what makes each of
   *     these runs a turn rather than a run, and its allowance is what every turn spends from
   * @param agent who answers each turn
   * @param lines where the utterances come from. A terminal, or a pipe — this loop does not
   *     distinguish, and {@link #interrupted} is written so that it does not have to
   */
  static int hold(
      SessionClient session,
      ServerClient server,
      ServerClient.Conversation conversation,
      String agent,
      BufferedReader lines,
      PrintStream out,
      PrintStream problems) {

    out.println(
        "conversation "
            + conversation.id()
            + " — "
            + agent
            + ", "
            + (conversation.maxModelCalls() == null
                ? "unlimited model calls"
                : Plowshare.count(conversation.maxModelCalls(), "model call"))
            + " for the whole of it, in "
            + (conversation.project() == null ? "the global tier" : conversation.project()));
    out.println(
        "a sentence and return speaks. "
            + STOP
            + " interrupts a turn, "
            + QUIT
            + " leaves. ctrl-C leaves at once and sends no cancel: the turn goes on until"
            + " it ends by itself, or until it asks this machine for a file that is no"
            + " longer being lent.");

    // What has already been shown, so that a seam is announced once and a
    // hole in one turn's view is not reported again in every turn after it.
    int foldedThrough = 0;
    int alreadyDropped = 0;
    // Turned off by the first server that cannot answer for folds, so that a
    // server without that endpoint costs one sentence and not one per turn.
    boolean watchingForFolds = true;

    while (true) {
      out.print(PROMPT);
      out.flush();
      String said;
      try {
        said = lines.readLine();
      } catch (IOException noMoreInput) {
        problems.println("could not read from this terminal: " + noMoreInput.getMessage());
        return 2;
      }
      if (said == null) {
        // End of input, which is ctrl-D at a terminal and the last line
        // of a script in a pipe. The newline is this terminal's, because
        // the prompt above did not print one and a shell drawing its own
        // prompt over ours reads as a crash.
        out.println();
        out.println("left conversation " + conversation.id() + ".");
        return 0;
      }
      said = said.strip();
      if (said.isEmpty()) {
        continue;
      }
      if (QUIT.equals(said)) {
        out.println("left conversation " + conversation.id() + ".");
        return 0;
      }
      if (STOP.equals(said)) {
        // Said rather than sent. There is no turn in flight at a prompt,
        // and a cancel for the turn that just finished would be accepted
        // by the server — cancelling a finished job is not an error — and
        // would do nothing, which is the worst of both.
        out.println(
            "nothing is running to stop. "
                + STOP
                + " interrupts a turn while it"
                + " is going; at the prompt, "
                + QUIT
                + " leaves.");
        continue;
      }

      String job;
      try {
        // No project: the conversation was opened in a home and its turns
        // run there. The server refuses a body carrying both rather than
        // preferring one, and this is the caller that must not make it
        // choose.
        job = session.submit(agent, said, null, conversation.id());
      } catch (IllegalStateException refused) {
        problems.println(refused.getMessage());
        return 2;
      } catch (ServerClient.ServerError refused) {
        // The server's own sentence and nothing added to it. A 409 here is
        // a conversation that will not take this turn and says which of
        // the two reasons it is — spent, or already speaking — and a
        // second sentence from this side would have to be vague enough to
        // cover both, which is how one message becomes two half-true ones.
        problems.println(refused.getMessage());
        return refused.status() == 409 ? 1 : 2;
      } catch (IOException unreachable) {
        problems.println(
            "could not speak into "
                + conversation.id()
                + " at "
                + session.serverUrl()
                + ": "
                + unreachable.getMessage());
        return 2;
      }
      out.println("job " + job);

      ServerClient.JobStatus status;
      try {
        status = Plowshare.follow(session, job, out, () -> interrupted(lines, server, job, out));
      } catch (ServerClient.ServerError said2) {
        return Plowshare.stillRunning(job, session.serverUrl(), problems, said2.getMessage());
      } catch (IOException lost) {
        return Plowshare.stillRunning(job, session.serverUrl(), problems, lost.getMessage());
      } catch (InterruptedException stopped) {
        Thread.currentThread().interrupt();
        return Plowshare.stillRunning(
            job, session.serverUrl(), problems, "this terminal was interrupted");
      }

      int dropped = session.dropped();
      Plowshare.say(status.outcome(), dropped - alreadyDropped, out, problems);
      alreadyDropped = dropped;
      out.println();

      if (watchingForFolds) {
        int shown = folds(server, conversation.id(), foldedThrough, out);
        watchingForFolds = shown >= 0;
        foldedThrough = watchingForFolds ? shown : foldedThrough;
      }
    }
  }

  /**
   * Somebody typed while the turn was running.
   *
   * <p>Called between polls of the event stream, so the longest a {@link #STOP} waits to be noticed
   * is one poll.
   *
   * <p><b>A line that is not {@link #STOP} is put back and is not read as anything.</b> {@link
   * BufferedReader#mark} and {@link BufferedReader#reset} are what make that possible, and it is
   * the difference between a REPL and something that only works when a human is slow: a script
   * piping utterances in has every one of them available from the moment the first turn starts, so
   * a loop that consumed what it found would answer line one and swallow line two. A person typing
   * ahead is the same case and deserves the same answer — what they typed is their next utterance
   * and it is still there when the prompt comes back.
   *
   * <p>{@link BufferedReader#ready} is what keeps this from blocking. A terminal in its ordinary
   * mode delivers nothing until return is pressed, so a half-typed line is not "ready" and this
   * does not sit inside {@code readLine} waiting for somebody to finish a sentence.
   */
  private static void interrupted(
      BufferedReader lines, ServerClient server, String job, PrintStream out) throws IOException {
    if (!lines.ready()) {
      return;
    }
    lines.mark(PUT_BACK);
    String typed = lines.readLine();
    if (typed == null || !STOP.equals(typed.strip())) {
      lines.reset();
      return;
    }
    server.cancelJob(job);
    out.println(
        "  asked the server to stop "
            + job
            + ". A run stops at its next turn"
            + " boundary, so this is not instant, and what it already did is kept.");
  }

  /**
   * Print any seam this conversation has grown since the last time it was asked.
   *
   * @param throughOrdinal the furthest reach already shown
   * @return the furthest reach now shown, or {@code -1} if the server could not answer — which the
   *     caller reads as "stop asking". A server that predates this endpoint answers 404 to every
   *     turn, and a terminal that said so every turn would be reporting the same fact about itself
   *     over and over in the middle of somebody's conversation
   */
  private static int folds(
      ServerClient server, String conversation, int throughOrdinal, PrintStream out) {
    List<ServerClient.Seam> seams;
    try {
      seams = server.compactions(conversation);
    } catch (IOException | ServerClient.ServerError cannot) {
      out.println(
          "(this server did not answer for "
              + conversation
              + "'s compactions: "
              + cannot.getMessage()
              + ". The turn above is unaffected; what cannot be seen"
              + " from here is whether the history behind it has been summarised. Not"
              + " asking again.)");
      out.println();
      return -1;
    }
    int furthest = throughOrdinal;
    for (ServerClient.Seam seam : seams) {
      if (seam.throughOrdinal() <= furthest) {
        continue;
      }
      // The turn after the previous row's reach, and no longer a literal
      // one. A fold covers the span since the fold before it, so the
      // second row of this list starts where the first one stopped and
      // "turns 1 to" would describe a span it does not stand for -- a
      // summary sold as covering ground another summary already holds.
      // The rows carry only their upper bound, so the lower one is read
      // off the row before: `furthest` is the previous row's reach, and
      // for the first row of the first poll it is the 0 the caller seeds,
      // which reads as "turns 1 to".
      int from = furthest + 1;
      furthest = seam.throughOrdinal();
      out.println(
          "— turns "
              + from
              + " to "
              + seam.throughOrdinal()
              + " were summarised to"
              + " make room. They are unchanged in the transcript and above on this screen;"
              + " what the next turn is shown in their place is:");
      out.println();
      out.println(seam.summary());
      out.println();
    }
    return furthest;
  }
}
