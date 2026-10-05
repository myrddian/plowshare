package io.aeyer.plowshare.protocol;

/**
 * One thing a running job did, on its way to an ending — or, once, that it is still there.
 *
 * <h2>{@link #ALIVE} is the one kind that is not about work</h2>
 *
 * <p>The other four name something that <em>happened</em>: a run began, a call was claimed, a tool
 * ran, a run ended. {@link #ALIVE} names the absence of any of that, and it exists because the
 * absence was indistinguishable from a dead server. Measured against a live one: a turn ran twelve
 * minutes and this stream carried {@code started}, {@code model_call}, then nothing at all until
 * {@code ended} — so for the whole expensive part of a turn a client could not tell a model that
 * was thinking from a process that was gone. A client-side timeout cannot tell them apart either,
 * because every number it could pick is wrong for some legitimate run. This one inverts the
 * question: "no answer yet" stops being evidence of anything, and "no beat for three intervals"
 * becomes evidence of exactly one thing.
 *
 * <p>It carries {@link #steps} and {@link #modelCalls} and nothing else new, which is the same
 * containment every other kind is under — see below — and also the whole of what it is for: a
 * person watching a beat with two numbers on it is watching progress rather than a spinner, and a
 * listener that attached in the middle of a run learns where the run is without asking.
 *
 * <h2>Every string in this record comes from a set the server owns</h2>
 *
 * <p><b>That is the whole containment rule, expressed as a property of the type rather than as a
 * habit at the call sites.</b> {@link #job} is minted by {@code JobStore}; {@link #agent} is the
 * name of a definition file an operator wrote; {@link #tool} is taken from the {@code ToolSchema}
 * of a tool <em>this server registered</em>, never from the name a model asked for; {@link #kind}
 * and {@link #ending} are constants declared here and in {@code Outcome.Ending}. No field carries a
 * model's prose, a tool call's arguments, a file's contents or path, an exception's message, or
 * anything a transport wrote.
 *
 * <p>The three routes that would break it, and why none of them is representable here:
 *
 * <ul>
 *   <li><b>a tool call's arguments.</b> {@code file_edit} is handed the content it is about to
 *       write, so an event that echoed arguments would be a second, unaudited copy of the file
 *       channel — one that bypasses every check {@code FileAccess} performs and that a browser
 *       could read. There is no field for them: {@link #tool} is a name, and the method that fills
 *       it is handed a name;
 *   <li><b>an exception's message.</b> {@code Scribe} settled this one: it puts only the
 *       exception's <em>type</em> in a reason, on the grounds that a transport-layer message can
 *       carry a URL, a header or a request body. Not hypothetical — {@code
 *       OpenAiTransport.withheldIfItQuotesTheKey} exists because the LM Studio endpoint really does
 *       echo a submitted token back in an error body. This record goes one step further than {@code
 *       Scribe} and carries no failure text at all, not even a type: {@link #ending} already says
 *       which <em>kind</em> of stop it was, and {@code Outcome.detail()} — which does hold the type
 *       and its message — stays behind {@code GET /v1/jobs/&#123;id&#125;}, the only contractual
 *       record of a run. <b>"Behind the job endpoint" is a smaller boundary than it sounds, and
 *       this bullet leans on it, so what it is worth is written out.</b> Job ids are sequential —
 *       {@code JobStore} mints {@code "job_" + %06d}; {@code JobStore.get}'s not-found message
 *       enumerates <em>every</em> job id in the process, and {@code AgentController.find} passes
 *       that message verbatim into the 404 body; and {@code JobView} carries {@code detail}. So
 *       anyone who can reach the port can walk the ids and read the exception types and messages
 *       this record refuses to carry. All of that predates these events and none of it is this
 *       record's to fix. What the containment here buys is precise: <b>the event stream is the one
 *       surface a cross-origin page could read without reaching that endpoint at all</b>, and it
 *       carries nothing. Who can reach the port is bounded elsewhere, and there are now three
 *       bounds rather than two: {@code EventChannelConfig}'s absent {@code setAllowedOrigins} for a
 *       page, {@code application.yml}'s {@code server.address:} {@code ${PLOWSHARE_BIND}} for
 *       everyone else, and — since slice 4 — {@code AuthFilter}, which refuses the upgrade to
 *       {@code /v1/events} and every {@code /v1} route to a caller with no access token. <b>That
 *       does not make this record's emptiness less load-bearing.</b> The console this stream was
 *       opened for renders model output and file contents, so a page holding the operator's cookie
 *       is exactly the reader an XSS gets to be, and the sentence above is about what such a reader
 *       learns;
 *   <li><b>the model's answer.</b> Not carried, and that is the lifecycle-only decision rather than
 *       an oversight. The answer is what the job endpoint returns; streaming it is what a REPL
 *       needs and is a later slice's to design, next to the thing that uses it.
 * </ul>
 *
 * <h2>The shape is {@code FileRequest}'s, on purpose</h2>
 *
 * <p>An operation with arguments, not five record types behind a discriminator: {@link #kind} says
 * which fields mean anything, the five factory methods are the only way this is built inside
 * Plowshare, and the fields a kind does not use are null or zero. {@link #kind} and {@link #ending}
 * are strings and not enums for the reason {@link FileReply#outcome()} is one — the two halves of
 * this wire ship separately, so a client built against a later server must not fail to bind over a
 * constant it has never heard of.
 *
 * <h2>What it does not carry, beyond the rule</h2>
 *
 * <p><b>No timestamp.</b> A listener stamps what it receives, and a server clock on a droppable
 * stream would be a second, partial record of a run whose real record is {@code JobStore}'s. <b>No
 * sequence number</b>, for the same reason: a listener that attached late, or one that a burst
 * overran, has holes by design, and a number that made those holes countable would invite somebody
 * to try to fill them.
 *
 * @param job the job id these events belong to, as {@code GET /v1/jobs/&#123;id&#125;} spells it.
 *     On every kind, and that is deliberate: one session may have several jobs running at once, so
 *     a listener that could not tell them apart would be watching an interleaving of runs rather
 *     than a run
 * @param kind one of {@link #STARTED}, {@link #MODEL_CALL}, {@link #TOOL_CALLED}, {@link #ALIVE},
 *     {@link #ENDED}
 * @param agent whose turn it is: the agent definition's own name. On every kind too, so that a
 *     listener which attached after {@link #STARTED} still knows what it is watching
 * @param tool which tool was called, for {@link #TOOL_CALLED}. <b>The name the server registered
 *     the tool under and never the name the model asked for</b>: a call to a tool that does not
 *     exist produces no event, because the string in it would be a model's invention rather than
 *     one of this server's own names
 * @param ending how the run ended, for {@link #ENDED}: the name of an {@code Outcome.Ending}
 *     constant
 * @param steps how many <b>steps</b> had completed, for {@link #MODEL_CALL}, {@link #ALIVE} and
 *     {@link #ENDED}. Zero elsewhere.
 *     <p><b>A step is one model call plus every tool result it asked for, and it is not a turn.</b>
 *     This field was called {@code turns} and the word was wrong twice over. A <em>turn</em> is one
 *     thing a person said and everything that answered it — {@code entries.turn_ordinal} and the
 *     {@code turns} table both mean that — while this counts the iterations of one agent's loop
 *     <em>inside</em> a single utterance. A console rendering "ANSWERED after 4 turns" for one
 *     question therefore told a person they had spoken four times. The two now have two words, in
 *     the server's own vocabulary, so that every client reads the same one: the console, the CLI
 *     and any harness over MCP
 * @param modelCalls how many model calls had been spent, for {@link #MODEL_CALL}, {@link #ALIVE}
 *     and {@link #ENDED}. Counted the way {@code Outcome.modelCalls} counts — claimed before the
 *     call is made, so a call that fails is spent. Zero elsewhere.
 *     <p>On {@link #ALIVE} both counts are <b>the last ones the run reported</b> and not a second
 *     measurement of it: a beat sent from inside a model call repeats what that call's {@link
 *     #MODEL_CALL} said, which is the truth — nothing has happened since, and that is what the beat
 *     is for. Two consecutive beats carrying the same numbers therefore mean a run is in one long
 *     call rather than a run that is stuck, and the two are the same thing seen from here
 */
public record JobEvent(
    String job, String kind, String agent, String tool, String ending, int steps, int modelCalls)
    implements ServerPush {
  public JobEvent {
    if (job == null
        || job.isBlank()
        || job.length() > 1024
        || agent == null
        || agent.isBlank()
        || agent.length() > 256
        || job.codePoints()
            .anyMatch(code -> Character.isISOControl(code) || code == 0x2028 || code == 0x2029)
        || agent
            .codePoints()
            .anyMatch(code -> Character.isISOControl(code) || code == 0x2028 || code == 0x2029)
        || kind == null
        || !kind.matches("[a-z][a-z0-9_]{0,63}")
        || steps < 0
        || modelCalls < 0) throw new IllegalArgumentException("invalid job notification");
    if (tool != null
        && (tool.isBlank()
            || tool.length() > 256
            || tool.codePoints()
                .anyMatch(
                    code -> Character.isISOControl(code) || code == 0x2028 || code == 0x2029)))
      throw new IllegalArgumentException("invalid tool identity");
    if (ending != null && !ending.matches("[A-Z_]{1,64}"))
      throw new IllegalArgumentException("invalid job ending");
    if ("tool_called".equals(kind) && tool == null || "ended".equals(kind) && ending == null)
      throw new IllegalArgumentException("job notification fields do not match kind");
  }

  /** The run has begun. Sent before the first model call. */
  public static final String STARTED = "started";

  /**
   * One model call has been claimed from the budget and is about to be made. The budget is claimed
   * before the call, so this is "spent" and not "answered".
   */
  public static final String MODEL_CALL = "model_call";

  /**
   * One tool the model asked for is about to run. One event per call in a batch, in the order the
   * loop runs them.
   */
  public static final String TOOL_CALLED = "tool_called";

  /**
   * The job exists and is still running. Nothing happened; that is the point.
   *
   * <h2>Why {@code alive} and not {@code heartbeat}</h2>
   *
   * <p><b>The word names the fact and not the mechanism.</b> A listener does not need to know that
   * a timer somewhere fired; it needs to know that the run it is waiting on is still there, which
   * is what this says and what {@code heartbeat} would say only by convention. The mechanism is
   * also the part most likely to change — {@code JobStore} sends these on a schedule today, and a
   * later one might send a beat off something else entirely — whereupon {@code heartbeat} would be
   * a name describing an implementation that had moved and {@code alive} would still be exactly
   * true.
   *
   * <p>{@code running} was the other candidate and it collides: {@code Job.State.RUNNING} is a fact
   * about a handle that {@code GET /v1/jobs/&#123;id&#125;} reports, and a client holding both
   * would have one word for two things that can legitimately disagree for a moment — a job is
   * {@code RUNNING} from the instant it is registered, which is before its thread has started and
   * long before anything on this stream says so.
   */
  public static final String ALIVE = "alive";

  /** The run is over and its outcome is filed. The last event for a job. */
  public static final String ENDED = "ended";

  public static JobEvent started(String job, String agent) {
    return new JobEvent(job, STARTED, agent, null, null, 0, 0);
  }

  public static JobEvent modelCall(String job, String agent, int steps, int modelCalls) {
    return new JobEvent(job, MODEL_CALL, agent, null, null, steps, modelCalls);
  }

  public static JobEvent toolCalled(String job, String agent, String tool) {
    return new JobEvent(job, TOOL_CALLED, agent, tool, null, 0, 0);
  }

  /**
   * The run is still going, and here is where it has got to.
   *
   * @param steps steps completed so far, as the run last reported them
   * @param modelCalls model calls claimed so far, on the same terms
   */
  public static JobEvent alive(String job, String agent, int steps, int modelCalls) {
    return new JobEvent(job, ALIVE, agent, null, null, steps, modelCalls);
  }

  public static JobEvent ended(String job, String agent, String ending, int steps, int modelCalls) {
    return new JobEvent(job, ENDED, agent, null, ending, steps, modelCalls);
  }
}
