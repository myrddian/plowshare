package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.ScopedPermissions;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.requests.RequestProjects;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Legacy omission is distinct from malformed explicit selectors and typed command defaults. */
class InformationScopesTest {
  final ScopedPermissions permissions = mock(ScopedPermissions.class);
  final RequestProjects defaults = mock(RequestProjects.class);
  final InformationAccess access = new InformationAccess(permissions, defaults);

  @Test
  void accountDestinationIsExplicitAndCannotChangeLegacyReceiptEncoding() {
    var global = io.aeyer.plowshare.server.access.BuiltInIdentities.GLOBAL.value();
    var selection =
        InformationScopes.from(
            Map.of("scope", Map.of("kind", "account", "account", global.toString())));
    assertEquals(InformationContext.Scope.ACCOUNT, selection.scope());
    assertEquals(global, selection.account());
    assertEquals("account:" + global, new InformationContext("actor", selection).namespace());
    assertFalse(InformationJson.json(InformationContext.Selection.personal()).contains("account"));
    for (var fields :
        List.of(
            Map.of("kind", "account"),
            Map.of("kind", "account", "account", UUID.randomUUID().toString()),
            Map.of("kind", "account", "account", global.toString(), "includeShared", false),
            Map.of("kind", "personal", "account", global.toString()),
            Map.of("kind", "account", "account", "GLOBAL")))
      assertThrows(CallerFault.class, () -> InformationScopes.from(Map.of("scope", fields)));
    assertThrows(
        CallerFault.class, () -> access.requireWork(new InformationContext("actor", selection)));
  }

  @Test
  void omittedAndLegacyNullSelectorsResolveFromTrustedContext() {
    var fields = new HashMap<String, Object>();
    fields.put("scope", null);
    fields.put("project", null);
    for (var payload : List.of(Map.<String, Object>of(), fields)) {
      assertNull(InformationScopes.from(payload));
      when(defaults.implicit("human")).thenReturn(Optional.empty());
      assertEquals(
          InformationContext.Selection.personal(),
          access.resolve("human", InformationScopes.from(payload)).selection());
      when(defaults.implicit("@service/application")).thenReturn(Optional.of("app"));
      when(permissions.allows(any(), any(), any())).thenReturn(true);
      var selected = access.resolve("@service/application", InformationScopes.from(payload));
      assertEquals(InformationContext.Scope.PROJECT, selected.selection().scope());
      assertEquals("app", selected.selection().project());
      assertFalse(selected.selection().includeShared());
      var command =
          InformationContentInputs.command(
              "types.list", Map.of("view", "content-types-v1"), selected.selection());
      assertEquals("app", command.selection().scope().project());
    }
  }

  @Test
  void explicitGeneralReadDoesNotNeedApplicationContextOrGrantExecution() {
    var selection = InformationScopes.from(Map.of("scope", Map.of("kind", "shared")));
    var context = access.resolve("@service/reader", selection);
    assertEquals(InformationContext.Selection.shared(), context.selection());
    assertThrows(CallerFault.class, () -> access.requireWork(context));
    assertThrows(CallerFault.class, () -> access.forRun("human", Home.global()));
    verifyNoInteractions(defaults, permissions);
  }

  @Test
  void invalidExplicitOrConflictingSelectorsCannotFallBack() {
    for (var payload :
        List.of(
            Map.<String, Object>of("project", ""),
            Map.<String, Object>of("project", " app "),
            Map.<String, Object>of("scope", Map.of("kind", "global")),
            Map.<String, Object>of(
                "scope", Map.of("kind", "personal", "account", UUID.randomUUID().toString())),
            Map.<String, Object>of("scope", Map.of("kind", "shared", "includeShared", true)),
            Map.<String, Object>of("scope", Map.of("kind", "project", "project", "")),
            Map.<String, Object>of("scope", Map.of("kind", "shared"), "project", "app"))) {
      assertThrows(CallerFault.class, () -> InformationScopes.from(payload));
    }
    verifyNoInteractions(defaults, permissions);
  }

  @Test
  void ordinaryProjectNamedGlobalUsesProjectReadAndNeverAnAccountIdentifier() {
    when(permissions.allows(any(), any(), any())).thenReturn(true);
    var selected = access.resolve("human", InformationScopes.from(Map.of("project", "global")));
    assertEquals(InformationContext.Selection.project("global"), selected.selection());
    verify(permissions)
        .allows(
            new ScopedPermissions.Principal("human"),
            new ScopedPermissions.ProjectTarget("global"),
            ScopedPermissions.Action.READ);
    verifyNoInteractions(defaults);
  }
}
