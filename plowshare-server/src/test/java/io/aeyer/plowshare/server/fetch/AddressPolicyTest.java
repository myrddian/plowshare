package io.aeyer.plowshare.server.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.fetch.AddressPolicy.Tier;
import io.aeyer.plowshare.server.fetch.AddressPolicy.Verdict;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * The table in spec §2.3, row by row and edge by edge. Every address here is a literal, so {@link
 * InetAddress#getByName} parses it and never touches DNS. Includes the three
 * metadata/task-credentials addresses added 2026-09-29 ({@code 169.254.170.2}, {@code
 * 100.100.100.200}, {@code fd20:ce::254}), each carved out of a wider private block whose other
 * addresses stay private.
 */
class AddressPolicyTest {

  private static InetAddress at(String literal) {
    try {
      return InetAddress.getByName(literal);
    } catch (UnknownHostException e) {
      throw new AssertionError(literal + " is not a literal", e);
    }
  }

  /**
   * {@code ::ffff:a.b.c.d} kept as an {@link Inet6Address}. {@code
   * InetAddress.getByName("::ffff:…")} hands back an {@code Inet4Address}, so it would never reach
   * the embedded-form branch this exists to test.
   */
  private static InetAddress mapped(int a, int b, int c, int d) {
    byte[] bytes = new byte[16];
    bytes[10] = (byte) 0xFF;
    bytes[11] = (byte) 0xFF;
    bytes[12] = (byte) a;
    bytes[13] = (byte) b;
    bytes[14] = (byte) c;
    bytes[15] = (byte) d;
    try {
      return Inet6Address.getByAddress(null, bytes, -1);
    } catch (UnknownHostException e) {
      throw new AssertionError(e);
    }
  }

  private static void all(Tier expected, String... literals) {
    for (String literal : literals) {
      assertEquals(expected, AddressPolicy.tierOf(at(literal)), literal);
    }
  }

  @Test
  void every_never_row_and_its_edges_are_never() {
    all(
        Tier.NEVER,
        "0.0.0.0",
        "0.255.255.255",
        "169.254.169.254",
        "169.254.170.2",
        "100.100.100.200",
        "224.0.0.0",
        "239.255.255.255",
        "240.0.0.0",
        "255.255.255.254",
        "255.255.255.255",
        "::",
        "ff00::",
        "ff02::1",
        "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        "fd00:ec2::254",
        "fd20:ce::254");
  }

  @Test
  void every_private_row_and_its_edges_are_private() {
    all(
        Tier.PRIVATE,
        "127.0.0.0",
        "127.0.0.1",
        "127.255.255.255",
        "10.0.0.0",
        "10.255.255.255",
        "172.16.0.0",
        "172.31.255.255",
        "192.168.0.0",
        "192.168.255.255",
        "169.254.0.0",
        "169.254.169.253",
        "169.254.170.1",
        "169.254.255.255",
        "100.64.0.0",
        "100.100.100.199",
        "100.127.255.255",
        "192.0.0.0",
        "192.0.0.255",
        "198.18.0.0",
        "198.19.255.255",
        "::1",
        "fc00::",
        "fd00:ec2::253",
        "fd20:ce::253",
        "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        "fe80::",
        "febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff");
  }

  @Test
  void just_outside_every_range_is_public() {
    all(
        Tier.PUBLIC,
        "1.0.0.0",
        "9.255.255.255",
        "11.0.0.0",
        "126.255.255.255",
        "128.0.0.0",
        "172.15.255.255",
        "172.32.0.0",
        "192.167.255.255",
        "192.169.0.0",
        "169.253.255.255",
        "169.255.0.0",
        "100.63.255.255",
        "100.128.0.0",
        "192.0.1.0",
        "198.17.255.255",
        "198.20.0.0",
        "223.255.255.255",
        "8.8.8.8",
        "93.184.215.14",
        "fbff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        "fe00::",
        "fec0::",
        "feff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:4860:4860::8888");
  }

  @Test
  void an_embedded_ipv4_is_judged_by_the_address_it_embeds() {
    assertEquals(Tier.PRIVATE, AddressPolicy.tierOf(mapped(127, 0, 0, 1)), "::ffff:127.0.0.1");
    assertEquals(
        Tier.PRIVATE,
        AddressPolicy.tierOf(at("::ffff:127.0.0.1")),
        "the same address as the JDK hands it back, already an Inet4Address");
    assertEquals(
        Tier.NEVER, AddressPolicy.tierOf(mapped(169, 254, 169, 254)), "::ffff:169.254.169.254");
    assertEquals(Tier.PUBLIC, AddressPolicy.tierOf(mapped(8, 8, 8, 8)), "::ffff:8.8.8.8");

    // 64:ff9b::/96 (NAT64): the last 32 bits.
    all(Tier.PRIVATE, "64:ff9b::a00:1");
    all(Tier.NEVER, "64:ff9b::a9fe:a9fe");
    all(Tier.PUBLIC, "64:ff9b::808:808");

    // 2002::/16 (6to4): bits 16..47.
    all(Tier.PRIVATE, "2002:7f00:1::", "2002:c0a8:1::");
    all(Tier.NEVER, "2002:a9fe:a9fe::", "2002:e000:1::");
    all(Tier.PUBLIC, "2002:808:808::");

    // ::/96, the deprecated IPv4-compatible form: every address whose first 96
    // bits are zero, other than :: (unspecified, never) and ::1 (loopback,
    // private) — judged by the embedded IPv4 address, like the other three forms.
    // ::2 embeds 0.0.0.2, itself inside the never row 0.0.0.0/8.
    all(Tier.PRIVATE, "::127.0.0.1", "::10.0.0.1");
    all(Tier.NEVER, "::169.254.169.254", "::2");
    all(Tier.PUBLIC, "::8.8.8.8");
    all(Tier.NEVER, "::");
    all(Tier.PRIVATE, "::1");
  }

  @Test
  void a_private_address_is_allowed_only_on_the_exempted_port() {
    InetAddress loopback = at("127.0.0.1");
    assertFalse(AddressPolicy.judge(loopback, 8080, OptionalInt.empty()).allowed());
    assertTrue(AddressPolicy.judge(loopback, 8080, OptionalInt.of(8080)).allowed());
    Verdict otherPort = AddressPolicy.judge(loopback, 8081, OptionalInt.of(8080));
    assertFalse(otherPort.allowed(), "an entry exempts its own port, not the host");
    assertEquals(Tier.PRIVATE, otherPort.tier());
  }

  @Test
  void the_never_tier_is_refused_even_on_an_exempted_port() {
    Verdict metadata = AddressPolicy.judge(at("169.254.169.254"), 80, OptionalInt.of(80));
    assertFalse(metadata.allowed());
    assertEquals(Tier.NEVER, metadata.tier());
    assertFalse(AddressPolicy.judge(mapped(169, 254, 169, 254), 80, OptionalInt.of(80)).allowed());
    assertFalse(AddressPolicy.judge(at("0.0.0.0"), 8080, OptionalInt.of(8080)).allowed());
  }

  @Test
  void a_public_address_is_allowed_on_any_port() {
    for (int port : new int[] {1, 22, 80, 443, 8080, 65535}) {
      Verdict verdict = AddressPolicy.judge(at("93.184.215.14"), port, OptionalInt.empty());
      assertTrue(verdict.allowed(), "port " + port);
      assertEquals(Tier.PUBLIC, verdict.tier());
    }
  }

  @Test
  void only_an_ip_literal_is_parsed_and_a_name_never_is() {
    assertEquals(at("127.0.0.1"), AddressPolicy.literal("127.0.0.1").orElseThrow());
    assertEquals(at("::1"), AddressPolicy.literal("::1").orElseThrow());
    assertTrue(AddressPolicy.literal("::ffff:7f00:1").isPresent());
    assertTrue(
        AddressPolicy.literal("localhost").isEmpty(),
        "a name is not a literal, and resolving it here would be a DNS lookup");
    assertTrue(AddressPolicy.literal("example.com").isEmpty());
    assertTrue(AddressPolicy.literal("256.0.0.1").isEmpty());
    assertTrue(
        AddressPolicy.literal("010.0.0.1").isEmpty(),
        "a leading zero is ambiguous, so it is left to the connect-time check");
    assertTrue(AddressPolicy.literal("").isEmpty());
    assertTrue(AddressPolicy.literal(null).isEmpty());
  }

  @Test
  void a_tier_names_itself_in_the_words_a_refusal_uses() {
    assertEquals("a private address", Tier.PRIVATE.phrase());
    assertEquals("an address this server never fetches", Tier.NEVER.phrase());
  }
}
