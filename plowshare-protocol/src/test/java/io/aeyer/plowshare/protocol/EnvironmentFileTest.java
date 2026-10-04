package io.aeyer.plowshare.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** The shared case table, which the TUI's copy of this grammar runs too. */
class EnvironmentFileTest {

  @TestFactory
  List<DynamicTest> every_case_in_the_shared_table() throws Exception {
    JsonNode cases;
    try (InputStream in = EnvironmentFileTest.class.getResourceAsStream("environments.json")) {
      cases = new ObjectMapper().readTree(in);
    }
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode c : cases) {
      tests.add(
          DynamicTest.dynamicTest(
              c.get("name").asText(),
              () -> {
                String text = c.get("text").asText();
                if (c.has("refusedLine")) {
                  EnvironmentFile.Unreadable refused =
                      assertThrows(
                          EnvironmentFile.Unreadable.class, () -> EnvironmentFile.parse(text));
                  assertEquals(c.get("refusedLine").asInt(), refused.line(), refused.getMessage());
                  return;
                }
                EnvironmentFile.Parsed parsed = EnvironmentFile.parse(text);
                check(c.get("expect").get("local"), parsed.local());
                check(c.get("expect").get("server"), parsed.server());
                JsonNode caps = c.get("expect").get("caps");
                if (caps == null || caps.isNull()) {
                  assertNull(parsed.caps());
                } else {
                  assertEquals(
                      new EnvironmentFile.Caps(
                          caps.has("steps") ? caps.get("steps").asInt() : null,
                          caps.has("budget") ? caps.get("budget").asInt() : null,
                          caps.has("autoContinue") ? caps.get("autoContinue").asInt() : null,
                          caps.has("time") ? caps.get("time").asInt() : null,
                          caps.has("failedChecks") ? caps.get("failedChecks").asInt() : null,
                          caps.has("autoIncrease") ? caps.get("autoIncrease").asBoolean() : null),
                      parsed.caps());
                }
              }));
    }
    return tests;
  }

  private static void check(JsonNode expected, EnvironmentFile.Section actual) {
    if (expected == null || expected.isNull()) {
      assertNull(actual);
      return;
    }
    assertEquals(text(expected, "mode"), actual.mode());
    assertEquals(
        expected.has("shells") ? expected.get("shells").asBoolean() : null, actual.shells());
    if (expected.has("inherit")) {
      List<String> names = new ArrayList<>();
      expected.get("inherit").forEach(n -> names.add(n.asText()));
      assertEquals(names, actual.inherit());
    } else {
      assertNull(actual.inherit());
    }
    if (expected.has("env")) {
      Map<String, String> env = new LinkedHashMap<>();
      expected
          .get("env")
          .fields()
          .forEachRemaining(e -> env.put(e.getKey(), e.getValue().asText()));
      assertEquals(env, actual.env());
    } else {
      assertNull(actual.env());
    }
    assertEquals(
        expected.has("timeoutMillis")
            ? Duration.ofMillis(expected.get("timeoutMillis").asLong())
            : null,
        actual.timeout());
    assertEquals(
        expected.has("outputBytes") ? expected.get("outputBytes").asLong() : null,
        actual.outputBytes());
    assertEquals(text(expected, "isolation"), actual.isolation());
  }

  private static String text(JsonNode node, String field) {
    return node.has(field) ? node.get(field).asText() : null;
  }

  @Test
  void a_section_replaces_only_the_keys_it_sets() {
    EnvironmentFile.Side server =
        EnvironmentFile.Side.DEFAULT.with(
            EnvironmentFile.parse("local:\n  mode: gated\n  timeout: 10m\n").local());
    EnvironmentFile.Side client =
        server.with(EnvironmentFile.parse("local:\n  mode: open\n").local());

    assertEquals(EnvironmentFile.OPEN, client.mode());
    assertEquals(Duration.ofMinutes(10), client.timeout());
    assertEquals(EnvironmentFile.DEFAULT_INHERIT, client.inherit());
  }

  @Test
  void the_default_is_off_with_no_shells() {
    assertTrue(EnvironmentFile.Side.DEFAULT.isOff());
    assertFalse(EnvironmentFile.Side.DEFAULT.shells());
  }

  @Test
  void a_shell_is_known_by_its_basename_on_any_platform() {
    assertTrue(EnvironmentFile.isShell("bash"));
    assertTrue(EnvironmentFile.isShell("/bin/sh"));
    assertTrue(EnvironmentFile.isShell("C:\\Windows\\System32\\cmd.exe"));
    assertTrue(EnvironmentFile.isShell("PowerShell.EXE"));
    assertFalse(EnvironmentFile.isShell("./gradlew"));
    assertFalse(EnvironmentFile.isShell("shellcheck"));
    assertFalse(EnvironmentFile.isShell(null));
  }
}
