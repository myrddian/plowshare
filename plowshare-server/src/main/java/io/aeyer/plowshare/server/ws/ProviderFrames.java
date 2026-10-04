package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.search.ProviderStore;
import io.aeyer.plowshare.server.search.SearchRegistrar;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * {@code SearchProviderController}'s area of the frame surface — the {@code provider.*} types this
 * server answers, which is two of that controller's three endpoints.
 *
 * <h2>The third is operational, and its absence is a ruling</h2>
 *
 * <p>{@code POST /v1/search/providers} gets no frame. It is the curl-shaped target of {@code
 * bin/plowshare-searxng}; it makes this server open an HTTP connection to a URL the caller supplied
 * and report what came back, which is a probe for anything this process can route to; and §4.1 of
 * the socket design narrows HTTP to "health, auth, and what ops genuinely needs" rather than to
 * nothing. {@code client.Capabilities} carries the decision in its own words, and {@code
 * ProviderFramesTest} asserts that this map does not quietly grow it back.
 *
 * <h2>Two services, and they are the controller's own two</h2>
 *
 * <p>{@link SearchRegistrar} and {@link ProviderStore}, in the controller's own order and for the
 * controller's own reason: the listing reads the store directly, because giving the registrar a
 * third method that existed only to satisfy a {@code GET} would make it a proxy for a store it
 * already holds.
 *
 * <p><b>Nothing here is runtime state.</b> The registrar holds the store, an {@code OkHttpClient}
 * and an {@code ObjectMapper}; the store holds a {@code JdbcTemplate}. No session table, so a
 * handler over these two cannot act behind a live client's back — the check {@code
 * ProjectController}'s {@code PresenceRegistry} made compulsory.
 */
@Component
public class ProviderFrames implements FrameArea {

  private final SearchRegistrar registrar;
  private final ProviderStore store;

  /**
   * @param registrar what decides that deregistering an unknown key is a refusal — the same bean
   *     the controller is handed
   * @param store where the listing is read from, directly, as the endpoint reads it
   */
  public ProviderFrames(SearchRegistrar registrar, ProviderStore store) {
    this.registrar = Objects.requireNonNull(registrar, "registrar");
    this.store = Objects.requireNonNull(store, "store");
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(
        FrameTypes.PROVIDER_LIST, new ProviderListHandler(store),
        FrameTypes.PROVIDER_DEREGISTER, new ProviderDeregisterHandler(registrar));
  }
}
