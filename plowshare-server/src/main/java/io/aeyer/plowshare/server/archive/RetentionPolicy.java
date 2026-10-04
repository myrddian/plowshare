package io.aeyer.plowshare.server.archive;

import java.time.Duration;

/**
 * How long a machine's log is kept before its payloads are marked to go — and how long a job record
 * is kept before it is pruned.
 *
 * <p>Three numbers and three absences. The first two are conversation ages and the third is not: a
 * job row is deleted rather than demoted, and {@link #jobs} below says why that is a different verb
 * rather than a third origin.
 *
 * <h2>Two origins have an age, three do not, and not every absence is a decision</h2>
 *
 * <p>{@link Origin#TURN} has no age <b>because a person's conversation is never marked
 * automatically</b>. §5 of the retention design says it in one line — the person chooses to
 * archive, and the export is explicit — and a key here would be the server offering to make that
 * choice for them on a timer. A person marks their own conversation through {@code PUT
 * /v1/conversations/&#123;id&#125;/lifecycle} and a sweep then ejects it like any other marked
 * tree; what has no configuration is the <em>deciding</em>.
 *
 * <p>{@link Origin#DELEGATION} has no age <b>because it is already answered</b>. A child follows
 * its root, and it follows it structurally: the lifecycle lives on the root, so there is no state
 * on a child to age and no row for a policy to select. That is the return on {@code
 * conversations_a_root_is_where_the_lifecycle_lives} — a rule that would otherwise have to be
 * remembered here becomes a query that cannot express the mistake.
 *
 * <p>{@link Origin#ORCHESTRATION} has no age, and unlike the two above <b>that is not yet a
 * decision</b>: a conductor is spoken to across a run's whole lifetime the way a {@link
 * Origin#TURN} conversation is, so treating it as unattended like {@link Origin#SUBMISSION} risks
 * marking one a caller is still waiting to hear back from — but nothing here can yet tell a
 * conductor still running from one whose orchestration finished. {@code orchestrations.ended_at} is
 * where that fact lives, and this policy does not read it. Where its retention story belongs is a
 * question this file leaves open rather than answers with {@code null}.
 *
 * <h2>Neither of the two that remain has a shipped default</h2>
 *
 * <p>This server ships numbers when it has arithmetic for them — {@code curator-budget} is 200
 * because a ruling is two model calls and that settles about a hundred candidates, and {@code
 * default-budget} is 240 because a turn of the interlocutor measured about twelve calls. There is
 * no comparable arithmetic for "how many days a curator trace is worth keeping": it depends on what
 * the operator debugs, how often, and what their disk costs.
 *
 * <p><b>And the two directions of being wrong are not symmetric, which is the argument that decides
 * it.</b> {@code ConversationsProperties} can ship 240 because both ways of being wrong there are
 * recoverable — too small stops a person mid-thought, too large costs some inference budget, and
 * spending a budget is a designed ending. Here, too large costs disk, and <b>too small deletes a
 * file body</b>. A default that deletes is a default that has to be right, and this one cannot be
 * derived. So it is unset, a server that nobody has configured ejects nothing however often a sweep
 * is run, and an operator who wants retention says how long in the file where every other bound
 * this server takes is said.
 *
 * <p><b>The design's ordering is honoured rather than enforced.</b> §5 says a curator's trace is
 * trimmed soonest and a submission is looser by policy, and both keys are independent numbers an
 * operator sets — nothing here refuses a configuration that says otherwise, because "soonest" is a
 * claim about which number an operator will pick and not an invariant this server can hold.
 *
 * @param curator how long a curator's ruling is kept before it is marked, or {@code null} for an
 *     operator who has not enabled it. What a curator pass <em>decided</em> is recorded by the
 *     machinery it acts through — proposals, promotions, the reason log — independently of any
 *     conversation, so its conversation is a debugging trace of how it got there rather than the
 *     record of what was decided. That is a fact this policy relies on and not a guarantee it makes
 * @param submission how long a run started on its own behalf is kept, or {@code null}. <b>Looser is
 *     not the same as free, and the design corrects itself on exactly this.</b> The tempting
 *     justification is that the caller already has the record; it only half holds. A foreign
 *     harness logs the boundary — the call it made and the answer it got — and has no idea which
 *     files the run read, which calls failed or how many steps it took. Plowshare's record of the
 *     interior is the only one that exists, so this number is a deployment's decision about its own
 *     disk and not a discount justified by somebody else's log
 * @param jobs how long a job record is kept before it is <b>pruned</b>, or {@code null}. <b>A
 *     different verb from the two above, and the difference is the whole reason it is a third
 *     component rather than a third origin.</b> The two above name conversations whose payloads are
 *     EJECTED — the row survives, demoted, because a trajectory with holes in it is worse than a
 *     trajectory that is large ({@code V19__conversation_lifecycle.sql} argues it at length). A job
 *     row is not a record of anything anybody said: an identifier, an agent's name, a project, two
 *     instants and three counts, and every readable thing the run produced is in another table
 *     under its own policy. There is nothing to eject, so it is deleted. See {@code V24__jobs.sql},
 *     and {@link #ejectAfter} on why this is not reachable through that method
 */
public record RetentionPolicy(Duration curator, Duration submission, Duration jobs) {

  /**
   * A deployment that ejects nothing, which is what an unconfigured server is.
   *
   * <p>A sweep against this marks nothing and therefore, on the next run, ejects nothing. It is not
   * a broken state: the operation is still safe and still reports, and a person who marks their own
   * conversation by hand still has it ejected, because that mark is theirs rather than this
   * policy's.
   */
  public static final RetentionPolicy NOTHING = new RetentionPolicy(null, null, null);

  public RetentionPolicy {
    refuse("curator", curator);
    refuse("submission", submission);
    refuse("jobs", jobs);
  }

  /**
   * A retention age of zero or less is refused rather than read as "off".
   *
   * <p>Off is the absence — an unset key — and it has to stay a different value from any number,
   * because the two most likely readings of {@code 0} are opposite: "never eject" and "eject
   * immediately". An operator who typed one meaning the other would find out from their data.
   */
  private static void refuse(String field, Duration age) {
    if (age != null && !age.isPositive()) {
      throw new IllegalArgumentException(
          "a retention age for '"
              + field
              + "' is a length of time to keep something,"
              + " so it is more than nothing; leave the key unset to eject nothing"
              + " rather than setting it to "
              + age
              + ", which reads as 'never' and"
              + " as 'immediately' to two different people");
    }
  }

  /**
   * How long this origin is kept, or {@code null} for one no sweep marks.
   *
   * <p>Null for {@link Origin#TURN} and {@link Origin#DELEGATION} always, null for {@link
   * Origin#ORCHESTRATION} until this policy can tell a conductor still running from one whose
   * orchestration ended, and for the other two until an operator says otherwise — four absences
   * that mean four different things, which the class javadoc separates and this method deliberately
   * does not: a caller only needs to know whether there is an age.
   *
   * <p>{@link Origin#EVENT} shares {@code submission}'s knob rather than getting a fourth of its
   * own. An event-started conversation is, by V40's flow, exactly the untargeted case — a run
   * nobody will speak to again, started on its own behalf by a trigger instead of by a script —
   * which is the same allowance story {@code Origin.EVENT}'s own javadoc draws ("owns its
   * allowance, like a submission"). A targeted firing speaks into an existing conversation through
   * {@code Turn.speak} and never creates a row of this origin at all, so there is no event
   * conversation this policy would be asked to treat differently from a submission's.
   *
   * <p><b>{@link #jobs()} is not reachable through here and that is not an oversight.</b> A job has
   * no {@link Origin} — a curator pass and a document ingest are jobs and are not one agent's run
   * at all — so there is no value a caller could pass to ask for it, and a synthetic origin
   * invented to make one fit would be a value nothing can write, which is precisely what {@code
   * V17__conversation_origin.sql} refused a {@code schedule} origin for. A different question asked
   * by a different accessor is the honest shape, and it also keeps "eject" and "prune" from being
   * spelled as one verb.
   */
  public Duration ejectAfter(Origin origin) {
    return switch (origin) {
      case CURATOR, MEMORY -> curator;
      case SUBMISSION, EVENT -> submission;
      case TURN, DELEGATION, ORCHESTRATION, BOARD -> null;
    };
  }
}
