package io.aeyer.plowshare.server.fetch;

import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;

/**
 * {@code plowshare.fetch.allow-private}: the {@code host:port} entries an operator lets fetch reach
 * on a private address (spec §2.5). One example is an internal docs server.
 *
 * <p>It is bound at boot and fixed from then on. It has no route, no runtime config row and no
 * project file behind it, so nothing a token holder, an agent or a client can edit widens it. It is
 * server-wide.
 *
 * <p>An entry matches a hop whose {@link HttpUrl#host()} and effective {@link HttpUrl#port()} equal
 * its own. Such a hop is dialled through a client whose sockets exempt that port on the private
 * tier. The never tier is exempt from nothing. An entry that names a never-tier literal is refused
 * here, at boot. An entry that names a host is not resolved here: DNS at boot would prove nothing
 * about DNS at connect time, and the socket judges the address again there.
 */
public final class FetchAllowlist {

  /**
   * One allowlisted {@code host:port}, with the host spelled as {@link HttpUrl#host()} spells it.
   */
  public record Entry(String host, int port) {

    @Override
    public String toString() {
      return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }
  }

  private static final String KEY = "plowshare.fetch.allow-private";

  /**
   * A bracketed IPv6 literal or a bare host, then a colon and a port. No scheme, user, path or
   * query.
   */
  private static final Pattern SHAPE =
      Pattern.compile("(\\[[0-9A-Fa-f:.]+\\]|[^\\s/?#@\\[\\]:]+):(\\d{1,5})");

  private final List<Entry> entries;

  private FetchAllowlist(List<Entry> entries) {
    this.entries = List.copyOf(entries);
  }

  /** No entries. Every private address is refused. */
  public static FetchAllowlist empty() {
    return new FetchAllowlist(List.of());
  }

  /**
   * {@code raw} as bound from {@code plowshare.fetch.allow-private}. Blank items are skipped, and
   * repeats are kept once.
   *
   * @throws IllegalStateException naming the key and quoting the entry, for an entry that is not
   *     {@code host:port}, has a port outside 1-65535, has a host {@link HttpUrl} will not accept,
   *     or names a never-tier literal
   */
  public static FetchAllowlist parse(List<String> raw) {
    if (raw == null) {
      return empty();
    }
    Set<Entry> entries = new LinkedHashSet<>();
    for (String item : raw) {
      if (item == null || item.isBlank()) {
        continue;
      }
      String written = item.trim();
      Matcher shape = SHAPE.matcher(written);
      if (!shape.matches()) {
        throw refused(
            written,
            "it is not host:port, a host, a colon and a port, with no"
                + " scheme, user or path (an IPv6 host goes in brackets: [fd12::5]:8080)");
      }
      int port = Integer.parseInt(shape.group(2));
      if (port < 1 || port > 65535) {
        throw refused(written, "its port is not between 1 and 65535");
      }
      HttpUrl url = HttpUrl.parse("http://" + shape.group(1) + ":" + port + "/");
      if (url == null) {
        throw refused(written, "its host is not one a URL can name");
      }
      Optional<InetAddress> literal = AddressPolicy.literal(url.host());
      if (literal.isPresent() && AddressPolicy.tierOf(literal.get()) == AddressPolicy.Tier.NEVER) {
        throw refused(
            written,
            "it names "
                + AddressPolicy.Tier.NEVER.phrase()
                + ", and the never tier is refused even when allowlisted");
      }
      entries.add(new Entry(url.host(), port));
    }
    return new FetchAllowlist(List.copyOf(entries));
  }

  private static IllegalStateException refused(String entry, String why) {
    return new IllegalStateException(
        KEY
            + " holds '"
            + entry
            + "', and "
            + why
            + ". It is read once at boot, so correct it and restart.");
  }

  /** Every entry, in the order written, each once. */
  public List<Entry> entries() {
    return entries;
  }

  /** The entry that allowlists {@code hop}, if any: same host, same effective port. */
  public Optional<Entry> match(HttpUrl hop) {
    String host = hop.host().toLowerCase(Locale.ROOT);
    int port = hop.port();
    for (Entry entry : entries) {
      if (entry.host().equals(host) && entry.port() == port) {
        return Optional.of(entry);
      }
    }
    return Optional.empty();
  }
}
