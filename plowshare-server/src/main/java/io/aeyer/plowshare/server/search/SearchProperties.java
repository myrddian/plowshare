package io.aeyer.plowshare.server.search;

import io.aeyer.plowshare.server.config.Live;
import io.aeyer.plowshare.server.config.RuntimeConfig;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The operator's policy over search providers — what {@link Registration} deliberately does not
 * hold.
 *
 * <p>Task 2 split a provider's row into what it <em>is</em> — key, base URL, the facts it fetched
 * about itself, its health — and left out what an operator <em>wants</em>: whether it is used at
 * all, where it sits in the walk, and the numbers that decide when it gets skipped. This class is
 * the other half of that split, and all four keys here are {@link Live}: a {@code PUT} to {@code
 * /v1/config} reaches the next search without a restart, on the same terms {@code
 * plowshare.documents.ingest-budget} already does.
 *
 * <h2>Keys, and not a fifth table</h2>
 *
 * <p>{@link RuntimeConfig}'s own class comment names the reason: the properties classes already
 * turn strings into the shapes a caller wants and already carry the javadoc explaining what each
 * one means, so a second, validated table for search settings would be a second place that knows
 * what a ladder or a threshold is — and two places that know one thing is what drifts. A row in
 * {@code search_providers} and a key in the runtime map can disagree about a provider's existence
 * today only because they are two different questions; a settings table answering the same question
 * as this class would be two answers to one question, on two schedules.
 *
 * <h2>What that costs: nothing is validated on the way in</h2>
 *
 * <p>The map takes any text, and this class does not check it against anything else this server
 * knows. An operator can write {@code plowshare.search.ladder=searxng,a-typo} naming a provider
 * {@link ProviderStore} has no row for, and this class accepts it — nothing here checks the ladder
 * against the registry, because a live accessor's whole job is to answer a string question, not a
 * lookup. The mistake surfaces later and elsewhere: as a model's refusal out of {@code
 * SearchLadder}, once a search actually tries to walk a rung that names nothing. That is the same
 * bargain {@code plowshare.documents.ingest-budget} already made — a mistake in a string is caught
 * at use rather than at write — taken knowingly here rather than re-argued.
 *
 * <h2>The empty ladder is the shipped default, and that is a decision</h2>
 *
 * <p>A server that has never had a provider registered has nothing to search through, and {@code
 * application.yml} ships {@code ladder: ""} rather than guessing at a provider that may not exist
 * on this deployment. There is no "try whatever is registered" fallback: an empty ladder means an
 * empty answer, said plainly, and not a search that silently picked a rung nobody configured.
 *
 * <h2>No {@code DomainRules}, and that is a decision too</h2>
 *
 * <p>{@link #ignoredDomainsNow()} is the list; nothing in this slice matches a result's host
 * against it. The design gives that list to a provider that declares {@code domainExclusion} in its
 * {@code ProviderFacts} and to nothing else, so a matcher here would be production code whose only
 * caller was its own test. Aletheia's {@code SearchDomainPolicy} is the pattern to port once the
 * fetch facade in slice 2 has a reason to run it — not now.
 *
 * <h2>{@link #getTimeout()} is a fifth key, and deliberately not a fifth {@link Live} one</h2>
 *
 * <p>{@code RemoteSearchProvider} needs a {@link Duration} and nothing else in this slice supplies
 * one, so this class is where it has to bind. It is not marked {@link Live} alongside the four
 * above: this class comment opens by naming "all four keys here are live" as a fact about the
 * design, not an incidental count, and a fifth would make that sentence false the moment it was
 * added. This server's own default, stated in {@link RuntimeConfig}'s class comment, is that
 * configuration binds once at boot and freezes into a constructor; {@link Live} is the argued
 * exception for the four keys above, each of which a running server needs to change without a
 * restart to recover from a bad rung or a provider gone noisy. A per-call HTTP timeout is not that
 * — an operator who wants a different one is retuning a client library's dial, not steering traffic
 * away from a misbehaving rung, and nothing about it needs to reach the next search before the next
 * deploy does. {@link SearchConfig} reads {@link #getTimeout()} — the bound value, never a {@code
 * ...Now()} accessor that does not exist — once, when it builds {@code RemoteSearchProvider}.
 */
@ConfigurationProperties(prefix = "plowshare.search")
public class SearchProperties {

  private static final Logger log = LoggerFactory.getLogger(SearchProperties.class);

  /**
   * The key {@link #ladderNow()} is live under, written once.
   *
   * <p>{@link Live} takes a compile-time constant and {@link RuntimeConfig} takes the same string
   * at runtime, so the alternative is the key spelled twice a few lines apart — and the boot check
   * that makes a misspelling a refusal reads the annotation, so the copy that could drift is the
   * one nothing checks. {@code DocumentsProperties#INGEST_BUDGET} is the pattern.
   */
  private static final String LADDER = "plowshare.search.ladder";

  /** {@link #ignoredDomainsNow()}'s key, on {@link #LADDER}'s reasoning. */
  private static final String IGNORED_DOMAINS = "plowshare.search.ignored-domains";

  /** {@link #failureThresholdNow()}'s key, on {@link #LADDER}'s reasoning. */
  private static final String FAILURE_THRESHOLD = "plowshare.search.failure-threshold";

  /** {@link #resultSetTtlNow()}'s key, on {@link #LADDER}'s reasoning. */
  private static final String RESULT_SET_TTL = "plowshare.search.result-set-ttl";

  /**
   * The map every {@code ...Now()} accessor reads through, or {@code null} where there is none.
   *
   * <p>Null is a supported state, on {@code DocumentsProperties#live}'s reasoning: this is a POJO
   * Spring binds, and a context with no datasource has no {@link RuntimeConfig} bean to inject.
   * Every accessor below falls through to its bound field in that case, which is what {@link
   * SearchPropertiesTest} pins for an instance built with {@code new}.
   *
   * <p>Not {@code final} and not a constructor parameter — Spring binds this class by calling
   * setters on an instance it made, and a constructor argument that is not a bound property would
   * take that away from it.
   */
  private RuntimeConfig live;

  /**
   * The ladder as Spring bound it, comma-separated and unsplit — a search walks these provider
   * keys, in this order, until one answers.
   *
   * <p>A {@code String} and not a {@code List<String>}: {@link #live} answers a raw string for a
   * runtime write, and a second parser for the bound value that had to agree with the one below
   * would be exactly the drift the class comment argues against. See {@link #ladderNow()} for the
   * one place the split happens.
   */
  private String ladder;

  /** {@link #ladder}'s counterpart for {@link #ignoredDomainsNow()}. */
  private String ignoredDomains;

  /**
   * How many consecutive failures a provider may have before the ladder treats it as unhealthy and
   * skips it, as Spring bound it.
   */
  private int failureThreshold;

  /**
   * How long one search's result set is held for a follow-up question before it is treated as gone,
   * as Spring bound it.
   */
  private Duration resultSetTtl;

  /**
   * The budget {@code RemoteSearchProvider} gives one call, as Spring bound it — see the class
   * comment for why this is an ordinary bound property rather than a fifth {@link Live} key.
   */
  private Duration timeout;

  /**
   * {@link #ladder}, split on this call rather than cached — <b>the ladder as of this instant</b>,
   * which is the one accessor here that can answer differently twice in a row.
   *
   * <p>No {@code get} prefix, on {@link Live}'s own naming rule: Boot's {@code JavaBeanBinder} must
   * not see this as a property, and the key this answers is named once, by the annotation, rather
   * than derived from a method name.
   *
   * <p>{@link RuntimeConfig} has no {@code stringOr} — it is deliberately a store that holds no
   * opinion about what a key <em>is</em> — so the fallback is spelled out here: {@code
   * live.get(LADDER).orElse(ladder)}, naming this accessor's own type and its own default, which is
   * the shape {@link RuntimeConfig}'s class comment sanctions.
   */
  @Live(LADDER)
  public List<String> ladderNow() {
    String raw = live == null ? ladder : live.get(LADDER).orElse(ladder);
    return split(raw, false);
  }

  /**
   * {@link #ignoredDomains}, live and trimmed — and lowercased, because a host is. {@code
   * FoxNews.com} and {@code foxnews.com} name one domain, and a list that kept an operator's casing
   * would ask every future reader of it to normalise before comparing.
   */
  @Live(IGNORED_DOMAINS)
  public List<String> ignoredDomainsNow() {
    String raw = live == null ? ignoredDomains : live.get(IGNORED_DOMAINS).orElse(ignoredDomains);
    return split(raw, true);
  }

  /**
   * {@link #failureThreshold}, live — {@link RuntimeConfig#intOr} is the convenience the class
   * comment sanctions: this accessor names its own type ({@code int}) and its own default ({@link
   * #failureThreshold}), and {@code RuntimeConfig} holds no opinion of its own about what the key
   * means.
   */
  @Live(FAILURE_THRESHOLD)
  public int failureThresholdNow() {
    return live == null ? failureThreshold : live.intOr(FAILURE_THRESHOLD, failureThreshold);
  }

  /**
   * {@link #resultSetTtl}, live — parsed by hand, because {@link RuntimeConfig} exposes no {@code
   * durationOr} and is not meant to grow one: a per-key typed registry is exactly the opinion its
   * class comment refuses to let it hold. This accessor is where the type and the default belong
   * instead.
   *
   * <p>The three cases this falls back on mirror {@link RuntimeConfig#intOr} exactly, because it is
   * the same fallback for a different type: no row, and a row that is not a parseable {@link
   * Duration}. Both answer {@link #resultSetTtl}; only the second warns, naming the key and the
   * value that would not parse — an absent key is the ordinary state of a server nobody has written
   * to, and warning on it would make the warning meaningless on every deployment's first boot.
   */
  @Live(RESULT_SET_TTL)
  public Duration resultSetTtlNow() {
    Optional<String> found = live == null ? Optional.empty() : live.get(RESULT_SET_TTL);
    if (found.isEmpty()) {
      return resultSetTtl;
    }
    String value = found.get();
    try {
      return Duration.parse(value.trim());
    } catch (DateTimeParseException notADuration) {
      log.warn(
          "the runtime config map holds '{}' for {}, which is not an ISO-8601"
              + " duration, so this server is answering with {} — the value it started"
              + " with. Nothing checks a value on the way in, so this is what a mistyped"
              + " write looks like from the reading end",
          value,
          RESULT_SET_TTL,
          resultSetTtl);
      return resultSetTtl;
    }
  }

  /**
   * Splits a comma-separated bound or live value into a trimmed list with no blank entries — {@code
   * " searxng , , brave "} is {@code ["searxng", "brave"]}, and {@code ""} or {@code null} is
   * {@code List.of()} rather than a list holding one blank string.
   *
   * @param lowercase true for the ignore list, whose entries are hosts and patterns compared
   *     case-insensitively; false for the ladder, whose entries are provider keys with a case of
   *     their own
   */
  private static List<String> split(String raw, boolean lowercase) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    List<String> entries = new ArrayList<>();
    for (String part : raw.split(",")) {
      String trimmed = part.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      entries.add(lowercase ? trimmed.toLowerCase(Locale.ROOT) : trimmed);
    }
    return List.copyOf(entries);
  }

  /**
   * {@link #ladder}, as bound — for {@link SearchConfig}'s use and for Spring's relaxed binding,
   * which wants a getter beside {@link #setLadder(String)}. Deliberately not {@link
   * Live}-annotated: {@link #ladderNow()} is the accessor that reads the map, and one key naming
   * two accessors is exactly what {@code RuntimeConfigSeed.oneAccessorPerKey} refuses at boot.
   */
  public String getLadder() {
    return ladder;
  }

  public void setLadder(String ladder) {
    this.ladder = ladder;
  }

  /** {@link #getLadder()}'s counterpart for {@link #ignoredDomains}. */
  public String getIgnoredDomains() {
    return ignoredDomains;
  }

  public void setIgnoredDomains(String ignoredDomains) {
    this.ignoredDomains = ignoredDomains;
  }

  /**
   * {@link #failureThreshold}, as bound — the value {@link SearchConfig} checks at boot, which is
   * the operator's configured number rather than whatever this instant's map holds. See {@code
   * DocumentsProperties#getIngestBudget()} for why the boot check reads the bound value and not
   * {@link #failureThresholdNow()}: {@code RuntimeConfigSeed} writes the map after this class is
   * built, so a check against the live value would read the map at the one moment it has not yet
   * been reconciled with the environment.
   */
  public int getFailureThreshold() {
    return failureThreshold;
  }

  public void setFailureThreshold(int failureThreshold) {
    this.failureThreshold = failureThreshold;
  }

  /** {@link #getFailureThreshold()}'s counterpart for {@link #resultSetTtl}. */
  public Duration getResultSetTtl() {
    return resultSetTtl;
  }

  public void setResultSetTtl(Duration resultSetTtl) {
    this.resultSetTtl = resultSetTtl;
  }

  /**
   * {@link #timeout}, as bound. A plain getter, not {@code timeoutNow()}: this key is never read
   * through {@link RuntimeConfig}, so an accessor shaped like the four above — the shape {@link
   * Live}'s own javadoc warns a reader against trusting on sight — would claim a liveness this
   * value does not have. {@link SearchConfig} reads this once, to build {@code
   * RemoteSearchProvider}, and nothing reads it again afterwards the way {@link #ladderNow()} is
   * read on every search.
   */
  public Duration getTimeout() {
    return timeout;
  }

  public void setTimeout(Duration timeout) {
    this.timeout = timeout;
  }

  /**
   * Setter injection, and the whole of how a bound POJO reaches a Spring bean — {@code
   * DocumentsProperties#setLive}'s pattern, copied exactly.
   *
   * <p>{@code required = false} because a context can legitimately have no {@link RuntimeConfig} —
   * see {@link #live} — and because the alternative is a properties class that refuses to exist
   * without a database.
   *
   * <p>Not a proxy over the bean: a reader stepping into {@link #ladderNow()} finds the map read in
   * the one place the source says it is, rather than a getter returning something the source does
   * not mention.
   */
  @Autowired(required = false)
  public void setLive(RuntimeConfig live) {
    this.live = live;
  }
}
