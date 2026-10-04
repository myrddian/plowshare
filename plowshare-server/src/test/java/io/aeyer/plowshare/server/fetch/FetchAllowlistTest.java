package io.aeyer.plowshare.server.fetch;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.Test;

/** Spec §2.5: what an entry is, what it matches, and what refuses the boot. */
class FetchAllowlistTest {

  @Test
  void an_entry_matches_a_hops_host_and_effective_port() {
    FetchAllowlist allowlist =
        FetchAllowlist.parse(List.of("docs.internal:8080", "Wiki.Internal:80"));
    assertEquals(
        Optional.of(new FetchAllowlist.Entry("docs.internal", 8080)),
        allowlist.match(HttpUrl.get("http://docs.internal:8080/a")));
    assertEquals(
        Optional.of(new FetchAllowlist.Entry("wiki.internal", 80)),
        allowlist.match(HttpUrl.get("http://WIKI.internal/page")),
        "the host is lower-cased as HttpUrl canonicalises it, and no port is the"
            + " scheme's default");
    assertTrue(
        allowlist.match(HttpUrl.get("http://docs.internal:8081/a")).isEmpty(),
        "another port on an allowlisted host is not allowlisted");
    assertTrue(
        allowlist.match(HttpUrl.get("https://wiki.internal/page")).isEmpty(),
        "https's effective port is 443, not 80");
    assertTrue(
        allowlist.match(HttpUrl.get("http://sub.docs.internal:8080/a")).isEmpty(),
        "an entry names one host, not its subdomains");
  }

  @Test
  void blanks_and_duplicates_are_dropped_and_an_absent_list_is_empty() {
    assertEquals(List.of(), FetchAllowlist.parse(null).entries());
    assertEquals(List.of(), FetchAllowlist.parse(List.of()).entries());
    assertEquals(List.of(), FetchAllowlist.parse(List.of("", "  ")).entries());
    assertEquals(
        List.of(new FetchAllowlist.Entry("docs.internal", 8080)),
        FetchAllowlist.parse(List.of(" docs.internal:8080 ", "DOCS.internal:8080")).entries());
    assertEquals(List.of(), FetchAllowlist.empty().entries());
  }

  @Test
  void an_ip_literal_entry_is_spelled_the_way_a_hop_is() {
    FetchAllowlist allowlist = FetchAllowlist.parse(List.of("127.0.0.1:9000", "[::1]:9001"));
    assertTrue(allowlist.match(HttpUrl.get("http://127.0.0.1:9000/")).isPresent());
    assertTrue(allowlist.match(HttpUrl.get("http://[::1]:9001/")).isPresent());
    assertTrue(
        allowlist.match(HttpUrl.get("http://[0:0:0:0:0:0:0:1]:9001/")).isPresent(),
        "HttpUrl canonicalises an IPv6 host, and the entry is canonicalised the same way");
    assertEquals("[::1]:9001", allowlist.entries().get(1).toString());
  }

  @Test
  void a_malformed_entry_refuses_boot_and_quotes_itself() {
    for (String bad :
        List.of(
            "docs.internal",
            "http://docs.internal:8080",
            "docs.internal:8080/path",
            "docs.internal:0",
            "docs.internal:65536",
            "user@docs.internal:8080",
            "::1:8080",
            "[not-v6]:80")) {
      IllegalStateException refused =
          assertThrows(IllegalStateException.class, () -> FetchAllowlist.parse(List.of(bad)), bad);
      assertTrue(refused.getMessage().contains("'" + bad + "'"), refused.getMessage());
      assertTrue(
          refused.getMessage().contains("plowshare.fetch.allow-private"), refused.getMessage());
    }
  }

  @Test
  void an_entry_naming_a_never_tier_literal_refuses_boot() {
    for (String never :
        List.of(
            "169.254.169.254:80",
            "0.0.0.0:8080",
            "[fd00:ec2::254]:80",
            "[::ffff:169.254.169.254]:80",
            "224.0.0.1:80")) {
      IllegalStateException refused =
          assertThrows(
              IllegalStateException.class, () -> FetchAllowlist.parse(List.of(never)), never);
      assertTrue(refused.getMessage().contains("'" + never + "'"), refused.getMessage());
      assertTrue(refused.getMessage().contains("never"), refused.getMessage());
    }
  }

  @Test
  void a_private_literal_and_a_name_are_accepted() {
    // A name is not judged here, because that would be a DNS lookup at boot.
    // The never tier is judged again at connect, and no entry exempts it there.
    assertEquals(
        2, FetchAllowlist.parse(List.of("10.0.0.5:8080", "metadata.example:80")).entries().size());
  }

  @Test
  void the_fetch_config_refuses_to_start_on_an_allowlisted_metadata_address() {
    FetchProperties props = new FetchProperties();
    props.setTtl(Duration.ofHours(24));
    props.setLiveWindow(Duration.ofMinutes(10));
    props.setWindow(8_000);
    props.setTimeout(Duration.ofSeconds(30));
    props.setAllowPrivate(List.of("169.254.169.254:80"));
    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> new FetchConfig(props));
    assertTrue(refused.getMessage().contains("'169.254.169.254:80'"), refused.getMessage());

    props.setAllowPrivate(List.of("docs.internal:8080"));
    assertDoesNotThrow(() -> new FetchConfig(props));
  }
}
