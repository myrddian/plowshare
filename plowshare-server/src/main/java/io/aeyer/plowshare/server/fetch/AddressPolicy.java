package io.aeyer.plowshare.server.fetch;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which addresses {@code fetch} may connect to: the table in {@code implementation rationale} §2.3,
 * written out.
 *
 * <p>It does not use {@link InetAddress#isSiteLocalAddress()} and its siblings. They miss
 * carrier-grade NAT ({@code 100.64.0.0/10}), IPv6 unique-local addresses ({@code fc00::/7}) and
 * IPv4 embedded in IPv6. A table in one place is also a table a test can read row by row.
 *
 * <p>Two tiers. <b>Never</b> is refused even when {@code plowshare.fetch.allow-private} names it:
 * the cloud metadata and task-credentials addresses — AWS's {@code 169.254.169.254} and its IPv6
 * form {@code fd00:ec2::254}, AWS ECS's task-credentials address {@code 169.254.170.2}, GCP's IPv6
 * metadata address {@code fd20:ce::254}, and Alibaba Cloud's metadata address {@code
 * 100.100.100.200} (added 2026-09-29) — plus the unspecified network, multicast and reserved space.
 * Each metadata address is a single address carved out of a wider <b>private</b> block it would
 * otherwise fall in; its neighbours stay private. <b>Private</b> is refused unless an allowlist
 * entry exempts the port. An IPv6 address that embeds an IPv4 one ({@code ::ffff:0:0/96}, {@code
 * 64:ff9b::/96}, {@code 2002::/16}, and the deprecated IPv4-compatible {@code ::/96} other than
 * {@code ::} and {@code ::1}) is judged by the IPv4 address it carries. Everything else is public,
 * on any port.
 */
public final class AddressPolicy {

  /** How far an address is from the public web. */
  public enum Tier {
    /** Refused even when allowlisted. */
    NEVER("an address this server never fetches"),
    /** Refused unless an allowlist entry exempts the port. */
    PRIVATE("a private address"),
    /** Allowed, on any port. */
    PUBLIC("a public address");

    private final String phrase;

    Tier(String phrase) {
      this.phrase = phrase;
    }

    /** How a refusal names this tier: {@code "refused: " + host + " is " + phrase()}. */
    public String phrase() {
      return phrase;
    }
  }

  /** Whether one connect may go ahead, and the tier that decided it. */
  public record Verdict(boolean allowed, Tier tier) {}

  /** A CIDR block over raw address bytes: 4 for IPv4, 16 for IPv6. */
  private record Range(byte[] network, int prefix) {

    boolean contains(byte[] address) {
      if (address.length != network.length) {
        return false;
      }
      int whole = prefix / 8;
      for (int i = 0; i < whole; i++) {
        if (address[i] != network[i]) {
          return false;
        }
      }
      int rest = prefix % 8;
      if (rest == 0) {
        return true;
      }
      int mask = (0xFF << (8 - rest)) & 0xFF;
      return (address[whole] & mask) == (network[whole] & mask);
    }
  }

  private static final List<Range> NEVER_V4 =
      List.of(
          range("0.0.0.0", 8),
          range("169.254.169.254", 32),
          range("169.254.170.2", 32),
          range("100.100.100.200", 32),
          range("224.0.0.0", 4),
          range("240.0.0.0", 4),
          range("255.255.255.255", 32));

  private static final List<Range> PRIVATE_V4 =
      List.of(
          range("127.0.0.0", 8),
          range("10.0.0.0", 8),
          range("172.16.0.0", 12),
          range("192.168.0.0", 16),
          range("169.254.0.0", 16),
          range("100.64.0.0", 10),
          range("192.0.0.0", 24),
          range("198.18.0.0", 15));

  private static final List<Range> NEVER_V6 =
      List.of(
          range("::", 128),
          range("ff00::", 8),
          range("fd00:ec2::254", 128),
          range("fd20:ce::254", 128));

  private static final List<Range> PRIVATE_V6 =
      List.of(range("::1", 128), range("fc00::", 7), range("fe80::", 10));

  /** A dotted quad with no leading zeros. {@code 010.0.0.1} is ambiguous and left to the socket. */
  private static final Pattern DOTTED_QUAD =
      Pattern.compile(
          "(0|[1-9]\\d{0,2})\\.(0|[1-9]\\d{0,2})\\.(0|[1-9]\\d{0,2})\\.(0|[1-9]\\d{0,2})");

  /**
   * Hex digits, colons and dots, with at least one colon. That is how {@code HttpUrl#host()} spells
   * an IPv6 literal, without brackets. A string that starts with a hex digit or a colon and
   * contains a colon is parsed by {@link InetAddress#getByName} as a literal or refused. It is
   * never looked up in DNS.
   */
  private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*");

  private AddressPolicy() {}

  /** The tier {@code address} falls in, judging an embedded IPv4 address by itself. */
  public static Tier tierOf(InetAddress address) {
    byte[] bytes = address.getAddress();
    byte[] embedded = embeddedV4(bytes);
    if (embedded != null) {
      bytes = embedded;
    }
    return bytes.length == 4
        ? classify(bytes, NEVER_V4, PRIVATE_V4)
        : classify(bytes, NEVER_V6, PRIVATE_V6);
  }

  /**
   * Whether a socket may connect to {@code address} on {@code port}. A private address is allowed
   * only when {@code exemptPort} is that same port. A never address is never allowed.
   */
  public static Verdict judge(InetAddress address, int port, OptionalInt exemptPort) {
    Tier tier = tierOf(address);
    boolean allowed =
        switch (tier) {
          case PUBLIC -> true;
          case PRIVATE -> exemptPort.isPresent() && exemptPort.getAsInt() == port;
          case NEVER -> false;
        };
    return new Verdict(allowed, tier);
  }

  /**
   * {@code host} as an address when it is an IP literal, and empty for a name. The early check and
   * the allowlist's boot check use this. Both run before any socket exists, and neither may resolve
   * a name, because a name is judged at connect time against the address it actually resolves to.
   */
  public static Optional<InetAddress> literal(String host) {
    if (host == null || host.isEmpty()) {
      return Optional.empty();
    }
    Matcher quad = DOTTED_QUAD.matcher(host);
    if (quad.matches()) {
      for (int i = 1; i <= 4; i++) {
        if (Integer.parseInt(quad.group(i)) > 255) {
          return Optional.empty();
        }
      }
    } else if (!IPV6.matcher(host).matches()) {
      return Optional.empty();
    }
    try {
      return Optional.of(InetAddress.getByName(host));
    } catch (UnknownHostException | IllegalArgumentException notALiteral) {
      return Optional.empty();
    }
  }

  /** The four IPv4 bytes a 16-byte address embeds, or {@code null}. */
  private static byte[] embeddedV4(byte[] a) {
    if (a.length != 16) {
      return null;
    }
    // ::ffff:0:0/96, IPv4-mapped
    if (zero(a, 0, 10) && (a[10] & 0xFF) == 0xFF && (a[11] & 0xFF) == 0xFF) {
      return Arrays.copyOfRange(a, 12, 16);
    }
    // 64:ff9b::/96, NAT64
    if (a[0] == 0x00
        && a[1] == 0x64
        && (a[2] & 0xFF) == 0xFF
        && (a[3] & 0xFF) == 0x9B
        && zero(a, 4, 12)) {
      return Arrays.copyOfRange(a, 12, 16);
    }
    // 2002::/16, 6to4
    if (a[0] == 0x20 && a[1] == 0x02) {
      return Arrays.copyOfRange(a, 2, 6);
    }
    // ::/96, the deprecated IPv4-compatible form: first 96 bits zero, other
    // than :: (unspecified) and ::1 (loopback), which keep their own tiers via
    // NEVER_V6/PRIVATE_V6 below.
    if (zero(a, 0, 12) && !(zero(a, 12, 15) && (a[15] == 0 || a[15] == 1))) {
      return Arrays.copyOfRange(a, 12, 16);
    }
    return null;
  }

  private static boolean zero(byte[] a, int from, int to) {
    for (int i = from; i < to; i++) {
      if (a[i] != 0) {
        return false;
      }
    }
    return true;
  }

  private static Tier classify(byte[] bytes, List<Range> never, List<Range> privateRanges) {
    for (Range range : never) {
      if (range.contains(bytes)) {
        return Tier.NEVER;
      }
    }
    for (Range range : privateRanges) {
      if (range.contains(bytes)) {
        return Tier.PRIVATE;
      }
    }
    return Tier.PUBLIC;
  }

  /** {@code literal} is always an IP literal here, so this never resolves a name. */
  private static Range range(String literal, int prefix) {
    try {
      return new Range(InetAddress.getByName(literal).getAddress(), prefix);
    } catch (UnknownHostException e) {
      throw new ExceptionInInitializerError(e);
    }
  }
}
