package io.aeyer.plowshare.server.archive;

/**
 * Which door a conversation came through, and the two things that follow from it.
 *
 * <h2>Why this is stored and not derived</h2>
 *
 * <p>Every run in this server gets a conversation now — a person's turn, a delegated child, a
 * curator's ruling and a submission alike — so the row alone no longer says whose it is. A nullable
 * parent reference is not enough to decide it: a {@link #SUBMISSION} is parentless too and is not a
 * person's conversation, which is precisely the distinction {@code GET /v1/conversations} has to
 * draw before it puts a listing in front of somebody.
 *
 * <p>It does two jobs, which is why it earns a column rather than being inferred: it decides
 * <b>which listing</b> a conversation belongs in, and it decides <b>which allowance story</b> it is
 * under. A third job is coming and is not built here — which retention policy applies — and that
 * one is why the value is written when the row is written and never recomputed. Once a payload has
 * been exported and nulled the row cannot be reclassified; whatever it said it was is what it
 * stays.
 *
 * <h2>These constants, and one name the design asked for that is not here</h2>
 *
 * <p>{@code V17__conversation_origin.sql} carries the argument at length. The short version: {@code
 * mcp} cannot be stored at write time, because {@code client.tools.AgentTools.run} reaches {@code
 * POST /v1/agents/&#123;name&#125;/runs} as an ordinary submission with nothing on it that says a
 * foreign harness is on the other end. A constant nothing can write in a constraint that exists to
 * make unclassified values unwritable is the one shape this enum must not take. {@link #EVENT} is
 * the origin a scheduled or otherwise event-started run carries.
 *
 * <p><b>Nothing holds this enum and {@code conversations_origin_is_known} together at compile
 * time</b>, which is the situation {@code EntryKind}, {@code Outcome.Ending} and {@code
 * ProposalState} are already in. A constant added here is not a compile error against the schema;
 * it is a row Postgres refuses the first time a real run reaches it. {@code
 * ConversationStoreTest.every_origin_this_server_can_write_is_one_this_table_holds} enumerates
 * {@link #values()} and writes one of each, so the build that adds a constant without a migration
 * is what fails.
 */
public enum Origin {

  /** System-owned digest construction or navigation; never a caller delegation. */
  MEMORY("memory"),

  /**
   * A person speaking, through {@code POST /v1/conversations} and then {@code POST
   * /v1/agents/&#123;name&#125;/runs} with a conversation named.
   *
   * <p>The only origin whose conversation <b>names no agent</b>: a person may put two agents' turns
   * in one conversation, which is the property {@code ConversationController}'s javadoc has
   * defended since conversations existed and which this enum does not weaken. It is also the only
   * one a listing shows.
   */
  TURN("turn"),

  /**
   * A child a run started with {@code agent_run}.
   *
   * <p>Its allowance is its parent's, shared by reference down the whole tree, so its row holds no
   * budget at all — see {@link #ownsItsAllowance()}. Its entries are its own and reach no parent's
   * prompt, which is the whole justification for delegation and is structural rather than filtered:
   * a separate conversation is a separate log and a separate projection.
   */
  DELEGATION("delegation"),

  /**
   * One ruling of a curator pass.
   *
   * <p><b>Parentless despite sharing an allowance</b>, and the pair is why {@link
   * #ownsItsAllowance()} keys off the origin rather than off the parent column. A pass is a job and
   * not a conversation — {@code JobStore.submit(String, Function)} takes the work itself — so there
   * is nothing for a ruling to point at, and yet every ruling in a pass spends one {@code Budget}
   * the pass minted.
   */
  CURATOR("curator"),

  /**
   * A run started on its own behalf: {@code POST /v1/agents/&#123;name&#125;/runs} with no
   * conversation.
   *
   * <p>A script, a CI job, curl, and the MCP {@code agent_run} tool, which is indistinguishable
   * from the others by the time it reaches this server. Its allowance is built from the agent's own
   * {@code max-model-calls}, because there is no parent to inherit from and nobody is going to
   * speak to it again.
   */
  SUBMISSION("submission"),

  /**
   * A run an event started: a trigger a person wrote, answering a tick or another adapter's event.
   * It owns its allowance, like a submission: there is no parent to inherit from.
   */
  EVENT("event"),

  /**
   * An orchestration's conductor speaking: its own conversation, owning the orchestration's
   * allowance, spoken to by the harness at each turn. Spec §5.
   */
  ORCHESTRATION("orchestration"),

  /**
   * A seat on a project's board — spec 2026-09-29, the project board and the swarm, §4. One agent's
   * place on one topic, woken turn by turn. It spends the root topic's pot, so its row holds no
   * allowance, the same shape as a delegation's; unlike a delegation it is a root.
   */
  BOARD("board");

  private final String wireName;

  Origin(String wireName) {
    this.wireName = wireName;
  }

  /**
   * What the column holds — lower case, as {@code EntryKind} and {@code ProposalState} spell
   * theirs, and not the constant's own name the way {@code turns.ending} does.
   */
  public String wireName() {
    return wireName;
  }

  /**
   * Whether a conversation of this origin holds an allowance of its own.
   *
   * <p><b>The one place this quietly goes wrong, answered once.</b> A {@link #DELEGATION} spends
   * its parent's {@code Budget} by reference and a {@link #CURATOR} ruling spends the pass's; a row
   * carrying a copy of either would be a second allowance of the same size, and it would double the
   * accounting the first time anything summed the column — with both rows holding plausible
   * numbers, which is what makes it quiet. So such a row holds nothing, {@code
   * conversations_an_allowance_is_owned_or_shared} refuses to let it hold anything, and {@code
   * ConversationRecord.budget()} is null for it.
   *
   * <p>Keyed off the origin and not off the parent reference, because {@link #CURATOR} is a root
   * that shares: "is a root" and "owns its allowance" are two different questions.
   */
  public boolean ownsItsAllowance() {
    return this == TURN
        || this == SUBMISSION
        || this == MEMORY
        || this == EVENT
        || this == ORCHESTRATION;
  }

  /**
   * Whether a conversation of this origin is one agent's from end to end.
   *
   * <p>The complement of {@link #TURN}, said as what it means rather than as a comparison, because
   * the two consequences are unrelated to each other: such a conversation must name an agent on its
   * row — {@code conversations_a_person_s_conversation_names_no_agent} — and it is not a
   * conversation a person speaks into.
   */
  public boolean isOneAgentsRun() {
    return this != TURN;
  }

  /**
   * The constant a row's value spells, or a refusal naming what was read.
   *
   * <p>{@code EntryKind.of}'s shape and its reason: an origin this server cannot read back is a row
   * written successfully and unreadable for ever, so the one place that can produce that fault is
   * the one place that parses it.
   *
   * @throws IllegalArgumentException if nothing is spelled that
   */
  public static Origin of(String wireName) {
    for (Origin origin : values()) {
      if (origin.wireName.equals(wireName)) {
        return origin;
      }
    }
    throw new IllegalArgumentException(
        "no conversation origin is spelled '"
            + wireName
            + "'; this row was written by"
            + " something that knows an origin this server does not, and"
            + " conversations_origin_is_known should have refused it");
  }
}
