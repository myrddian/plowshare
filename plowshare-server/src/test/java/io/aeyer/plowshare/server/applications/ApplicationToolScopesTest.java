package io.aeyer.plowshare.server.applications;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ApplicationToolScopesTest {
  @Test
  void wildcard_is_confined_to_its_provider_execution_account_and_assigned_agent()
      throws Exception {
    var policy =
        ApplicationToolScopes.parse(
            """
        {"executionAccount":"worker","toolScopes":[{"scope":"linear","provider":"linear","grants":["*"]}],
         "toolGrants":[{"toolScope":"linear","agent":"ticketer"}]}
        """);
    assertTrue(policy.permits("worker", "ticketer", "linear", "linear_new_tool"));
    assertFalse(policy.permits("user", "ticketer", "linear", "linear_new_tool"));
    assertFalse(policy.permits("worker", "reviewer", "linear", "linear_new_tool"));
    assertFalse(policy.permits("worker", "ticketer", "scanner", "linear_new_tool"));
  }

  @Test
  void malformed_or_ambiguous_assignments_fail_closed() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ApplicationToolScopes(
                null,
                List.of(),
                List.of(new ApplicationToolScopes.Assignment("missing", "agent"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ApplicationToolScopes.Scope("linear", "linear", List.of("*", "linear_read")));
    assertThrows(Exception.class, () -> ApplicationToolScopes.parse("{\"toolScopes\":null}"));
    assertThrows(
        Exception.class,
        () ->
            ApplicationToolScopes.parse(
                "{\"toolScopes\":[{\"scope\":\"linear\",\"provider\":\"linear\",\"grants\":[\"*\"],\"extra\":true}]}"));
    assertThrows(
        Exception.class,
        () ->
            ApplicationToolScopes.parse("{\"executionAccount\":\"a\",\"executionAccount\":\"b\"}"));
  }
}
