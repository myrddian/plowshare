package io.aeyer.plowshare.server.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Default spending policy for new conversations, independent of agent definitions and step limits.
 * Omitted policy is unlimited. Explicit request limits override this default; stored allowances
 * retain their selected policy across restart and configuration changes.
 */
@ConfigurationProperties(prefix = "plowshare.conversations")
public class ConversationsProperties {

  /** "unlimited", zero (no inference), or a positive model-call ceiling. */
  private String defaultBudget = "unlimited";

  public String getDefaultBudget() {
    return defaultBudget;
  }

  public void setDefaultBudget(String value) {
    io.aeyer.plowshare.server.embedding.SpendingPolicy.limit(value, "default-budget");
    defaultBudget = value;
  }

  /**
   * Validated runtime ceiling; null is unlimited. Keep this separate from the String bean property
   * so configuration binding never tries to convert "unlimited" into an integer.
   */
  public Integer defaultBudgetLimit() {
    return io.aeyer.plowshare.server.embedding.SpendingPolicy.limit(
        defaultBudget, "default-budget");
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
