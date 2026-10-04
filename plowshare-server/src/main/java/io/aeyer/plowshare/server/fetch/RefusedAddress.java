package io.aeyer.plowshare.server.fetch;

import java.io.IOException;

/**
 * A {@link GuardedSockets} socket refused to connect, because {@link AddressPolicy} put the
 * endpoint off the public web.
 *
 * <p>It is an {@link IOException}, so it leaves OkHttp the way any failed connect does. OkHttp's
 * {@code RealConnection.connectSocket} re-wraps only {@code ConnectException}, so this arrives at
 * {@link BuiltinFetcher} as itself, possibly with the refusals of other routes suppressed onto it.
 * Its own type is what lets {@link BuiltinFetcher} answer {@link FetchFailure#REFUSED_ADDRESS}
 * rather than {@link FetchFailure#FETCH_FAILED}.
 *
 * <p>The message names the tier and never the address. {@link BuiltinFetcher} builds the sentence
 * an agent sees from the hop's host, not from this message. Even so, this message could reach a log
 * or a stack trace, and the resolved address of a private name is internal DNS.
 *
 * <p>spec 2026-09-28-fetch-stays-on-the-public-web-design.md §2.1.
 */
final class RefusedAddress extends IOException {

  private final AddressPolicy.Tier tier;

  /**
   * @param tier {@link AddressPolicy.Tier#PRIVATE} or {@link AddressPolicy.Tier#NEVER}. An endpoint
   *     the socket cannot judge at all (unresolved, or not an {@code InetSocketAddress}) is refused
   *     as {@code NEVER}, because no allowlist entry may exempt what was never judged.
   */
  RefusedAddress(AddressPolicy.Tier tier) {
    super("refused: the endpoint is " + tier.phrase());
    this.tier = tier;
  }

  AddressPolicy.Tier tier() {
    return tier;
  }
}
