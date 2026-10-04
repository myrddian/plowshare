package io.aeyer.plowshare.server.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import okhttp3.OkHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link SearchProperties}, refuses to start on a number that could not be honoured, and
 * wires every bean this slice built but did not itself wire: {@link RemoteSearchProvider}, {@link
 * SearchLadder} and {@link SearchService}. {@link ResultSetStore} needs no {@code @Bean} here — it
 * carries {@code @Repository} of its own and is already found by component scanning, the same as
 * {@link ProviderStore}.
 *
 * <p><b>Every validation below runs in this class's constructor, not beside each {@code @Bean}.</b>
 * That used to be the shape only because nothing built here depended on a bound number — the
 * paragraph below on that history is the only part of this class's original comment still worth
 * keeping, now that {@link SearchLadder} does read {@link SearchProperties#failureThresholdNow()}
 * and {@link RemoteSearchProvider} does read {@link SearchProperties#getTimeout()}. The constructor
 * still runs first regardless of where the checks live, and moving them beside {@code
 * searchProvider} would split one set of refusals about one properties object across two places for
 * no reader's benefit — {@code LlmConfig.llmDispatcher} folds its checks into one {@code @Bean}
 * method precisely because that method is the only consumer of the values it checks, which is not
 * true here: {@code failure-threshold} is read by {@link SearchLadder} and {@code timeout} by
 * {@link RemoteSearchProvider}, two different beans built two different ways below.
 *
 * <p>{@link SearchProperties#ladderNow()} and {@link SearchProperties#ignoredDomainsNow()} are not
 * checked here. Any string binds: an operator naming a provider {@link ProviderStore} holds no row
 * for is accepted, on the cost {@link SearchProperties}' class comment already argues — the mistake
 * surfaces later, as a model's refusal, rather than at boot.
 *
 * <h2>One {@code OkHttpClient} bean, shared by the whole {@code search} package</h2>
 *
 * <p>This used to read the other way — {@code SearchRegistrar} built its own client, on {@code
 * OpenAiTransport}'s precedent that every class dialling out over HTTP owns its client, sized for
 * what it calls. That precedent is about clients with genuinely different shapes: {@code
 * OpenAiTransport} holds four, because a chat call, a streamed call, an embedding call and a socket
 * upgrade are four different timeout and connection-pool profiles against one vendor. {@link
 * #searchProvider} and {@code SearchRegistrar} are not that — both dial arbitrary registered
 * provider processes with no per-call shape to size a client for, and both already apply their own
 * budget per call through {@link okhttp3.Call#timeout()} rather than baking a timeout into the
 * client itself. A second client here would have been a second connection pool for the exact same
 * class of endpoint, which is the thing {@code InvariantsTest}'s {@code
 * an_http_client_is_held_by_exactly_three_files_in_main} exists to make an author justify before
 * adding — see that test's updated comment for the argument that a shared client for one package is
 * not what the invariant forbids. {@link #searchHttpClient} is therefore the one place in this
 * package that builds one, and {@code SearchRegistrar}'s constructor now takes it as an argument
 * instead of building its own.
 *
 * <h2>{@link Clock#systemUTC()}, not a {@code Clock} bean</h2>
 *
 * <p>On {@code DocumentsConfig} and {@code DeliberationConfig}'s own precedent: this server has no
 * {@code Clock} bean, and inventing one here for {@link SearchService} alone would make every
 * context that boots this class carry a bean the rest of the server does not use. {@link
 * SearchService} takes a {@link Clock} rather than reading {@link Clock#systemUTC()} itself so a
 * test can advance it past a TTL without waiting on a wall clock.
 */
@Configuration
@EnableConfigurationProperties(SearchProperties.class)
public class SearchConfig {

  /**
   * @throws IllegalStateException if {@code failure-threshold} is below one, {@code result-set-ttl}
   *     is not a positive duration, or {@code timeout} is not a positive duration, naming the key
   *     and the value that could not be honoured
   */
  public SearchConfig(SearchProperties props) {
    if (props.getFailureThreshold() < 1) {
      throw new IllegalStateException(
          "plowshare.search.failure-threshold is "
              + props.getFailureThreshold()
              + "; it must be at least 1. It is how many consecutive failures a"
              + " provider may have before the ladder skips it, and a threshold"
              + " below one would skip every provider before it ever ran a"
              + " search");
    }
    Duration ttl = props.getResultSetTtl();
    if (ttl == null || ttl.isZero() || ttl.isNegative()) {
      throw new IllegalStateException(
          "plowshare.search.result-set-ttl is "
              + ttl
              + "; it must be a positive"
              + " duration. It is how long one search's result set is held for a"
              + " follow-up question, and zero or negative holds nothing at all");
    }
    Duration timeout = props.getTimeout();
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      // Zero is refused rather than treated as OkHttp's "no timeout": a
      // call with no bound at all is not what plowshare.search.timeout
      // says it is, on LlmConfig.requireReadTimeout's own reasoning for
      // its three timeout keys.
      throw new IllegalStateException(
          "plowshare.search.timeout is "
              + timeout
              + "; it must be a positive"
              + " duration. It is the budget RemoteSearchProvider gives one call,"
              + " and zero is not \"no timeout\" here — it is handed to"
              + " Call#timeout(), which treats zero the same way");
    }
  }

  /**
   * The one {@code OkHttpClient} the {@code search} package shares — see this class's own comment
   * for why one client now serves both {@link #searchProvider} and {@code SearchRegistrar} rather
   * than each building its own. Built with no timeouts of its own for the same reason {@link
   * RemoteSearchProvider} and {@code SearchRegistrar} do not read one off it: each applies its own
   * budget per call through {@link okhttp3.Call#timeout()} — {@link SearchProperties#getTimeout()}
   * for a search, {@code SearchRegistrar}'s own ten-second probe budget for a registration — so a
   * timeout baked into the client here would be a second, unread number.
   */
  @Bean
  public OkHttpClient searchHttpClient() {
    return new OkHttpClient();
  }

  /** The one out-of-process port {@link SearchLadder} dials through. */
  @Bean
  public SearchProvider searchProvider(
      OkHttpClient http, ObjectMapper mapper, SearchProperties props) {
    return new RemoteSearchProvider(http, mapper, props.getTimeout());
  }

  /** Not built until this task, on {@link SearchLadder}'s own javadoc. */
  @Bean
  public SearchLadder searchLadder(
      ProviderStore store, SearchProperties props, SearchProvider provider) {
    return new SearchLadder(store, props, provider);
  }

  /**
   * {@code POST /v1/search}'s whole behaviour — see {@link SearchService}'s own javadoc for the
   * four steps and {@link Clock#systemUTC()}.
   */
  @Bean
  public SearchService searchService(
      SearchLadder ladder, ResultSetStore sets, SearchProperties props) {
    return new SearchService(ladder, sets, props, Clock.systemUTC());
  }
}
