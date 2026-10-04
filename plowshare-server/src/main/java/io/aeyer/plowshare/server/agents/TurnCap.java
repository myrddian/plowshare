package io.aeyer.plowshare.server.agents;

/**
 * How many <b>steps</b> a run may still take, or that nothing is stopping it.
 *
 * <h2>The name says turn and the thing is a step</h2>
 *
 * <p>A step is one model call plus every tool result it asked for. A turn is one thing a person
 * said and everything that answered it — {@code entries.turn_ordinal}, the {@code turns} table,
 * {@code agents.Turn}. This type bounds the first and is named after the second, and that is a name
 * kept on purpose rather than an accident left standing: {@code Ending.TURN_CAP} is frozen by
 * {@code turns_ending_is_known} in a shipped migration, {@code max-turns} is in every agent
 * definition under {@code resources/agents/}, and {@code conversations.turn_cap} is a shipped
 * column. Renaming any of the three is a migration or a breaking config change; what is read by a
 * person or a model is {@link #describe}, and that says steps.
 *
 * <h2>Why this is an object and not an {@code int}</h2>
 *
 * <p>Three properties an {@code int} cannot carry, and this class exists for all three.
 *
 * <p><b>No cap is a state and not a number.</b> An uncapped run is expressible here as {@link
 * #none()} and reads back as {@link #capped()} being false — never as a large number standing in
 * for infinity. {@link Budget}'s javadoc argues that a per-agent copy of a shared allowance is a
 * mistake that has to be designed out rather than remembered; this is the same argument about a
 * different confusion. A ceiling of {@code Integer.MAX_VALUE} is a number somebody reads a year
 * later as a decision about how many turns this agent needs, and it is one nobody made.
 *
 * <p><b>It changes while the run is going.</b> {@link JobRuntime}'s loop asks {@link #stops} at
 * every turn boundary rather than reading a number it captured at the start, so raising the cap on
 * a run that is nearly out is picked up at the next boundary and the run goes on. That is the whole
 * interaction this type exists for: <em>this run is nearly out — give it twenty more</em> instead
 * of the run stopping, the question being retyped, and the work being paid for twice.
 *
 * <p><b>Lowering it is defined and is not undefined behaviour.</b> A cap set below the turns a run
 * has already taken stops it at its next boundary, which is where every other stopping condition in
 * that loop is decided — cancellation, the budget — and it ends {@code TURN_CAP} like any other run
 * that reached its ceiling. Nothing is unwound: the turns it took, it took.
 *
 * <h2>Per run, where a {@link Budget} is per tree</h2>
 *
 * <p><b>The two are deliberately opposite and it matters which is which.</b> A budget is money and
 * is shared by reference down a whole delegation tree, so raising one raises it for a parent and
 * every child it starts. A turn cap is a runaway guard on <em>one agent's loop</em>: a child run
 * gets its own, built from its own definition, because "this agent needs more room" is a fact about
 * the agent that needed the room and says nothing about the one that delegated to it. {@code
 * AgentRunTool} reaches {@link JobRuntime#run} with the child's definition and no cap of the
 * parent's, which is what makes that true.
 *
 * <h2>Nothing here reaches the model</h2>
 *
 * <p>No tool takes one, no schema mentions one, and no system prompt names one. An agent that could
 * read or write its own ceiling would negotiate with it, and a ceiling an agent can raise is not a
 * ceiling. {@code ModelSurfaceTest} pins every shipped agent's system message and tool schemas, so
 * a knob leaking into either fails it.
 *
 * <p>The one sentence a model may see about the cap is {@code JobRuntime}'s second repeat note,
 * which states the number as an observation about the run it is in the way it states the count of
 * identical calls. It is read-only, it is not a control, and it predates this class.
 *
 * <h2>Volatile, though a run reads it on one thread</h2>
 *
 * <p>A run reads its own cap at its own boundaries on its own virtual thread. The writer is
 * somebody else entirely — an operator, on a request thread, while the run is in the middle of a
 * model call — so the field is {@code volatile} and the two states are held in one reference rather
 * than in a number and a flag that could be read half-updated.
 */
public final class TurnCap {

  /**
   * The ceiling, or {@code null} for a run nothing is stopping.
   *
   * <p>One field rather than an {@code int} and a {@code boolean}, so that there is no pair to read
   * between two writes and no combination of the two that means nothing. A boxed {@link Integer}
   * because that is the smallest thing that has both states; the box is allocated when a cap is set
   * or changed and read once per turn, against a turn that makes a model call.
   */
  private volatile Integer turns;

  private TurnCap(Integer turns) {
    this.turns = turns;
  }

  /**
   * A cap of {@code turns} turns.
   *
   * <p>Non-positive is refused rather than read as "no turns" or as "no cap". A run that may take
   * no turns can only end at its cap, having done nothing, which is a configuration mistake
   * presenting as a run that never answers — {@link Budget#of}'s refusal, for the same reason.
   * {@code AgentRegistry} already refuses a non-positive {@code max-turns} at boot naming the file,
   * and this is the guard for a cap built anywhere else.
   */
  public static TurnCap of(int turns) {
    if (turns <= 0) {
      throw new IllegalArgumentException("a turn cap needs at least one turn; got " + turns);
    }
    return new TurnCap(turns);
  }

  /**
   * A run with no turn cap at all.
   *
   * <p>Not a very large number. See the class javadoc: the whole reason this type exists rather
   * than an {@code int} is that the two are different facts, and only one of them is a decision
   * somebody made about how many turns an agent needs.
   */
  public static TurnCap none() {
    return new TurnCap(null);
  }

  /**
   * The cap this agent's own definition asks for.
   *
   * <p>The narrowest thing every run starts from and the widest thing a definition can say: a
   * definition names a number and cannot name "no cap", because a file that switched the runaway
   * guard off for every run of an agent for ever is not a default anybody should be able to set by
   * editing frontmatter. Turning it off is a decision taken per conversation or per run, by
   * somebody watching.
   */
  public static TurnCap from(AgentDefinition definition) {
    return of(definition.maxTurns());
  }

  /**
   * The cap this run actually gets, out of the three levels that may name one.
   *
   * <p><b>Narrowest wins</b>, which is the rule {@code POST /v1/conversations} already applied to
   * model calls: an agent definition carries what this agent usually needs, a conversation may
   * override it for every turn in it, and a single run may override that. Each level is {@code
   * null} when it says nothing, and the definition is not — every agent's frontmatter names a
   * number, which is why there is always an answer and never a fourth case to decide.
   *
   * <p><b>One place decides it</b>, so that "which wins" cannot be answered one way where a run is
   * submitted and another where a turn is. Both doors come here.
   *
   * <p>The object handed back is the one that was passed in, not a copy: a caller that means to
   * hold on to a run's cap and move it later is holding the same object the run is reading. What is
   * <em>not</em> shared is the conversation's stored ceiling, which is a fresh object per row read;
   * see {@code ConversationRecord.turnCap}.
   *
   * @param forRun what this run was submitted with, or null
   * @param forConversation what the conversation this run is a turn in decided, or null — including
   *     for a run that is in no conversation
   * @param definition the agent answering, whose {@code max-turns} is the answer when nothing
   *     narrower has one
   */
  public static TurnCap chosen(
      TurnCap forRun, TurnCap forConversation, AgentDefinition definition) {
    if (forRun != null) {
      return forRun;
    }
    if (forConversation != null) {
      return forConversation;
    }
    return from(definition);
  }

  /**
   * Whether a run that has completed {@code taken} turns must stop.
   *
   * <p>Always false for an uncapped run, which is the whole of what "no cap" does: {@link
   * JobRuntime}'s loop asks this and nothing else about the cap, so there is one place the two
   * states differ.
   *
   * <p>{@code >=} and not {@code >}: a cap of three turns permits three, so a run that has taken
   * three is done. It is the comparison the loop made against {@code definition.maxTurns()} before
   * this type existed, kept exactly, because a cap that quietly gained a turn on the way into an
   * object would be a change nobody asked for in the one number two shipped agents' frontmatter
   * argues about.
   */
  public boolean stops(int taken) {
    Integer ceiling = turns;
    return ceiling != null && taken >= ceiling;
  }

  /** Whether there is a cap at all. */
  public boolean capped() {
    Integer ceiling = turns;
    return ceiling != null;
  }

  /**
   * The ceiling.
   *
   * @throws IllegalStateException if there is no cap. A number is not invented for a run that has
   *     none — that is the substitution this whole type refuses — so a caller that wants to render
   *     one asks {@link #capped} first, or uses {@link #describe}, which answers for both states
   */
  public int turns() {
    Integer ceiling = turns;
    if (ceiling == null) {
      throw new IllegalStateException(
          "this run has no turn cap, so it has no number of"
              + " turns to report; ask capped() first");
    }
    return ceiling;
  }

  /**
   * Move the ceiling to {@code turns}, for a run that is already going.
   *
   * <p>Raising it is the point. Lowering it is permitted and defined — see the class javadoc —
   * including below what the run has already taken, which stops it at its next boundary rather than
   * doing something nobody chose.
   *
   * <p>Non-positive is refused here as it is at {@link #of}: a run stopped by a cap of zero and a
   * run stopped by a cap of one are the same run, and the refusal keeps one meaning for one number.
   */
  public void changeTo(int turns) {
    if (turns <= 0) {
      throw new IllegalArgumentException("a turn cap needs at least one turn; got " + turns);
    }
    this.turns = turns;
  }

  /**
   * Take the ceiling off a run that is already going.
   *
   * <p>Separate from {@link #changeTo} rather than a number it accepts, because that is the
   * distinction this type is for: "let it run" is not a bigger number, and an operator that meant
   * it should not have to pick one.
   */
  public void lift() {
    this.turns = null;
  }

  /**
   * This cap in words, for a message, in both of its states.
   *
   * <p><b>It says steps, and the type is still called {@code TurnCap}.</b> What this bounds is the
   * number of times one agent's loop goes round — one model call plus the tool results it asked for
   * — and calling that a turn is the ambiguity this vocabulary was corrected to remove: a turn is
   * one thing a person said and everything that answered it. The name of the type, of the {@code
   * max-turns} key, of {@code Ending.TURN_CAP} and of {@code conversations.turn_cap} all stayed,
   * because each of them is frozen somewhere a rename is a migration or a breaking config change;
   * {@code Outcome} lists them. What a person and a model actually read is this sentence, so this
   * sentence is what was corrected.
   */
  public String describe() {
    Integer ceiling = turns;
    return ceiling == null ? "no cap" : ceiling + " " + stepWord(ceiling);
  }

  /**
   * "step" or "steps", for a count.
   *
   * <p>Here rather than in {@link JobRuntime}, which is where it was and which still calls it, so
   * that the plural of the thing this type bounds is spelled once. Two copies would be two rules
   * with one subject, and the first rewording of either is where they stop agreeing — the failure
   * this repository sweeps for by name.
   */
  static String stepWord(int steps) {
    return steps == 1 ? "step" : "steps";
  }

  @Override
  public String toString() {
    return "TurnCap[" + describe() + "]";
  }
}
