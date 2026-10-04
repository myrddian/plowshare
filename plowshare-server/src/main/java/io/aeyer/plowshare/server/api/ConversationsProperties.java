package io.aeyer.plowshare.server.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What a conversation is opened with when whoever opened it did not say.
 *
 * <h2>The refusal this replaces, and the half of it that still stands</h2>
 *
 * <p>{@code POST /v1/conversations} required {@code maxModelCalls} and argued it: "how much a
 * person is going to say has no arithmetic behind it", so "a configured default here would be a
 * number with no method, which is the defect this project keeps correcting". <b>That argument was
 * about the server inventing a number, and it stands.</b> Nothing in this class computes an
 * allowance from a workload, because there is no workload to compute one from, and no code path
 * here picks a number that an operator has not.
 *
 * <p><b>What has changed is who is being asked.</b> The refusal reached a person, through a console
 * that had to put a number field in front of them before they could open a conversation at all, and
 * the judgement recorded against it is the owner's: a budget "shouldnt be there — from a user point
 * of view it is messy and quite convoluted with no explanation ... it should just inherit that from
 * the system". An operator setting one number, once, for their own box is a different act from a
 * server picking one on a caller's behalf: it is a policy about what this deployment lets a
 * conversation cost, said in the file where every other cost bound on this server is said. A person
 * opening a conversation is not choosing a cost bound; they are starting to talk.
 *
 * <p><b>It is a default and not a ceiling.</b> A body that names {@code maxModelCalls} is honoured
 * exactly as before — the number is still the caller's to state, and the three clients that know
 * what they want (the CLI's {@code conversation_open}, a harness, a script) go on stating it. This
 * answers only the body that says nothing, which used to be answered with a 400.
 *
 * <h2>Why the prefix is its own</h2>
 *
 * <p>Not {@code plowshare.agents}, where {@code curator-budget} lives: a conversation is not an
 * agent's, and its allowance is spent by whichever agents are asked to answer turns in it. {@code
 * conversations_a_person_s_conversation_names_no_agent} is that same fact held in the schema, and a
 * key filed under the agents prefix would say the opposite of it.
 */
@ConfigurationProperties(prefix = "plowshare.conversations")
public class ConversationsProperties {

  /**
   * How many model calls a conversation opened without an allowance may spend.
   *
   * <p><b>240, and the arithmetic is the interlocutor's own measurement rather than a round
   * number.</b> {@code interlocutor.md} records that "a full turn of this agent costs about twelve
   * calls" and sets its own {@code max-model-calls: 40} at "three of those with room" — that number
   * bounding a run started on the agent's own behalf and being inert for a turn of an actual
   * conversation, which spends the conversation's. Twenty of those turns is 240, and twenty
   * utterances is a conversation rather than a question: long enough that a person is not stopped
   * in the middle of a thought by a bound they never set, small enough that a tab left open on a
   * loop costs a bounded amount of somebody's inference budget.
   *
   * <p><b>Spending it is not a failure.</b> {@code CALL_BUDGET} is a designed ending — the
   * conversation stops, says so, and every turn it did take is in the archive — and the next
   * conversation starts with the whole of this again. That is what makes a conservative number the
   * safe one to ship, on {@code AgentsProperties.curatorBudget}'s reasoning exactly.
   *
   * <p>An operator whose agents are cheaper, or whose people talk longer, sets {@code
   * PLOWSHARE_CONVERSATION_BUDGET} and never reads this sentence again. {@code application.yml}
   * carries the same argument beside the key.
   */
  private int defaultBudget = 240;

  public int getDefaultBudget() {
    return defaultBudget;
  }

  public void setDefaultBudget(int defaultBudget) {
    this.defaultBudget = defaultBudget;
  }

  /**
   * What becomes of a conversation's stored tool results, and where they are written first.
   *
   * <p>Under this prefix and not a new one, for the reason the prefix exists: retention is a policy
   * about conversations, applied per origin, and every key here is one an operator sets about the
   * conversations their box holds. A {@code plowshare.retention} prefix would file "how long a
   * curator trace is kept" somewhere a reader looking at conversation configuration would not find
   * it.
   */
  private Retention retention = new Retention();

  public Retention getRetention() {
    return retention;
  }

  public void setRetention(Retention retention) {
    this.retention = retention;
  }

  /**
   * The retention keys, as an operator sets them.
   *
   * <p>Plain nullable values with getters, because Spring binds a nested object this way and
   * because <b>unset has to survive the binding</b>: an {@code int} would arrive as 0 for a key
   * nobody set, and 0 is the one value a retention age must never quietly take — see {@link
   * io.aeyer.plowshare.server.archive.RetentionPolicy}, which refuses it and says why the two
   * readings of it are opposite.
   *
   * <p>{@code turn} and {@code delegation} have no keys, and both absences are decisions rather
   * than omissions. A person's conversation is never marked automatically; a delegated child
   * follows its root structurally, because the lifecycle lives on the root and a child has no state
   * of its own to age. {@code application.yml} carries both arguments beside the keys that do
   * exist.
   */
  public static class Retention {

    /**
     * Where an ejected payload is written before it is taken out of the row, or unset for the data
     * directory's own place for them.
     *
     * <p><b>This is now the override rather than the primary way to say it.</b> It was the only way
     * while there was no data directory, and its default was a bare relative {@code exports} that
     * landed wherever the server happened to start — which is the pile the data directory replaced
     * with a tree. Unset means {@code <data-dir>/projects/<project-id>/exports/}; set means that
     * directory instead, with the same per-project shape inside it, which {@code
     * DataLayout.exportsUnder} argues.
     *
     * <p><b>Three states out of one string, and the third is why the key is absent from {@code
     * application.yml} rather than present and blank.</b> Null is "not overridden" and reaches the
     * convention; blank is "keep no export at all" — a real configuration, the operator who wants
     * the liability gone rather than moved, which {@code result_read} reports to a model rather
     * than naming an empty place; a value is the override. A key written into the YAML with an
     * empty placeholder default would collapse the first two into the second, and the one it would
     * land on is the one that keeps nothing.
     *
     * <p><b>{@code PLOWSHARE_EXPORT_DIR} is retired by that absence</b>, and the cost is stated
     * rather than discovered: the short variable worked because the key was written here with it as
     * a placeholder, and there is now no key for it to substitute into. {@code PLOWSHARE_DATA_DIR}
     * moves the whole tree; {@code PLOWSHARE_CONVERSATIONS_RETENTION_EXPORTDIRECTORY} moves only
     * this. An operator upgrading with the old variable set loses nothing — what is written stays
     * where it is and stays readable, since the manifest's paths are relative to their own tree —
     * and new trees go under the data directory.
     *
     * <p><b>Server-rooted, and said rather than discovered.</b> Under presence the server may hold
     * no user disk at all, so there is no "their machine" for an export to land on. See {@code
     * PayloadExport}, where that argument turned out to be the argument for the data directory
     * itself.
     */
    private String exportDirectory;

    /**
     * How many days a curator's ruling is kept before a sweep marks it, or unset for an operator
     * who has not enabled it.
     *
     * <p>Days and not a {@code Duration} string, because the unit an operator thinks in here is
     * days and a key spelled {@code PT168H} is a key people get wrong. The conversion is one line
     * in {@code RetentionConfig} and the refusal of zero is in {@code RetentionPolicy}.
     */
    private Integer curatorAfterDays;

    /** How many days a run started on its own behalf is kept, or unset. */
    private Integer submissionAfterDays;

    public String getExportDirectory() {
      return exportDirectory;
    }

    public void setExportDirectory(String exportDirectory) {
      this.exportDirectory = exportDirectory;
    }

    public Integer getCuratorAfterDays() {
      return curatorAfterDays;
    }

    public void setCuratorAfterDays(Integer curatorAfterDays) {
      this.curatorAfterDays = curatorAfterDays;
    }

    public Integer getSubmissionAfterDays() {
      return submissionAfterDays;
    }

    public void setSubmissionAfterDays(Integer submissionAfterDays) {
      this.submissionAfterDays = submissionAfterDays;
    }
  }
}
