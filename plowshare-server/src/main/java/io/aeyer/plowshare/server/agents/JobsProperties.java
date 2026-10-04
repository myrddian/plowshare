package io.aeyer.plowshare.server.agents;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What an operator decides about the record this server keeps of the runs it has done.
 *
 * <h2>Why the prefix is its own</h2>
 *
 * <p><b>Not {@code plowshare.conversations.retention}, where the two ejection ages live</b>, and
 * the reason is the same one that put those under {@code plowshare.conversations} in the first
 * place. That prefix's own javadoc says "retention is a policy about conversations, applied per
 * origin, and every key here is one an operator sets about the conversations their box holds". A
 * job is not a conversation and does not have one: a curator pass and a document ingest are both
 * jobs, and neither is one agent's run. Filing "how long a job record is kept" under the
 * conversation prefix would say the opposite of what {@code V24__jobs.sql} is at pains to keep
 * separate — that these are operational records rather than anybody's transcript, deleted rather
 * than demoted.
 *
 * <p><b>Not {@code plowshare.agents} either</b>, which holds where agent definitions live and what
 * a curator pass may spend. A job is polled under an agent's name when there is one, and under
 * {@code memory_curator} or {@code document_ingest} when there is not.
 *
 * <p>So: its own prefix, which is the shape {@code ConversationsProperties} argued for when it took
 * its own. It held one key when it was opened and it now holds two, and the second belongs for the
 * identical reason: how often a running job says it is running is a fact about jobs, not about
 * anybody's conversation and not about an agent.
 *
 * <p>The two are otherwise unlike each other — one is about a row that outlives the run and one
 * about a frame that does not outlive the second it was sent in — and neither is a default the
 * other's javadoc argues for. Read each on its own; this prefix is a place, not a policy.
 */
@ConfigurationProperties(prefix = "plowshare.jobs")
public class JobsProperties {

  /**
   * How many days a job record is kept before a sweep prunes it, or unset for an operator who has
   * not enabled it.
   *
   * <p><b>Unset by default, and that is {@code RetentionPolicy}'s rule rather than caution.</b>
   * This server ships numbers when it has arithmetic for them — 240 because a turn measured about
   * twelve calls, 200 because a ruling is two — and there is no arithmetic for how many days a
   * record of a run is worth keeping: it depends on what the operator debugs, how often, and what
   * their disk costs. The two directions of being wrong are not symmetric either. Too large costs a
   * few hundred bytes a run; too small deletes the only durable evidence that a twenty-six minute
   * ingest happened. So a server nobody has configured prunes nothing however often a sweep is run.
   *
   * <p>Days and not a {@link java.time.Duration} string, matching the two conversation ages beside
   * it: the unit an operator thinks in here is days, and a key spelled {@code PT168H} is a key
   * people get wrong. The conversion is one line in {@code ArchiveConfig} and the refusal of zero
   * is in {@code RetentionPolicy}, which explains why nought must not be readable as "off".
   *
   * <p>A boxed {@link Integer} so that <b>unset survives the binding</b>: an {@code int} would
   * arrive as 0 for a key nobody set, and 0 is the one value a retention age must never quietly
   * take.
   */
  private Integer retentionAfterDays;

  public Integer getRetentionAfterDays() {
    return retentionAfterDays;
  }

  public void setRetentionAfterDays(Integer retentionAfterDays) {
    this.retentionAfterDays = retentionAfterDays;
  }

  /**
   * How often a job that is still running says so.
   *
   * <p><b>Twenty seconds, and this server ships a number here because there is arithmetic for
   * it</b> — which is the same test the key above fails. {@link JobStore#HEARTBEAT} is where that
   * arithmetic is written: under the sixty seconds at which an idle connection is commonly cut, by
   * a margin wide enough that one dropped beat is still not a cut connection, and not so low that a
   * long run is a flood of identical frames.
   *
   * <p><b>The default is that constant and not a second copy of it.</b> {@code AuthProperties}
   * writes its numbers in both the YAML and the class, on the grounds that "the YAML is where an
   * operator looks" and the field is what a context that never read one gets — and that holds here
   * too. What must not be in two places is the <em>argument</em>: a fixture's store and a
   * configured one differing by a number nobody noticed had drifted is a beat that tests at one
   * interval and ships at another.
   *
   * <p>A {@link Duration} and not the days-as-{@code Integer} above, because the unit an operator
   * thinks in is seconds here and {@code 20s} is a spelling Spring reads. Zero or negative is a
   * real configuration rather than a mistake — a deployment with no proxy between it and its
   * clients, whose operator would rather have the silence — so unlike a retention age it is not
   * refused.
   */
  private Duration heartbeat = JobStore.HEARTBEAT;

  public Duration getHeartbeat() {
    return heartbeat;
  }

  public void setHeartbeat(Duration heartbeat) {
    this.heartbeat = heartbeat;
  }
}
