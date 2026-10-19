package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;
import org.junit.jupiter.api.Test;

class InformationInterchangeInputsTest {
  @Test
  void exact_revisions_and_explicit_scope_are_validated_before_service_calls() {
    var id = UUID.randomUUID();
    var scope = Map.of("kind", "project", "project", "knowledge", "includeGlobal", false);
    var command =
        assertInstanceOf(
            InformationRequest.InterchangeExport.class,
            InformationInterchangeInputs.command(
                "interchange.export", Map.of("scope", scope, "revisions", List.of(id.toString()))));
    assertEquals(List.of(id), command.revisions());
    assertThrows(
        CallerFault.class,
        () ->
            InformationInterchangeInputs.command(
                "interchange.export", Map.of("revisions", List.of(id.toString()))));
    assertThrows(
        CallerFault.class,
        () ->
            InformationInterchangeInputs.command(
                "interchange.export", Map.of("scope", scope, "revisions", List.of("1-1-1-1-1"))));
    assertThrows(
        CallerFault.class,
        () ->
            InformationInterchangeInputs.command(
                "interchange.export",
                Map.of(
                    "scope",
                    scope,
                    "revisions",
                    List.of(id.toString()),
                    "selection",
                    Map.of("scope", Map.of("kind", "personal")))));
    assertThrows(
        CallerFault.class,
        () ->
            InformationInterchangeInputs.command(
                "interchange.export",
                Map.of(
                    "scope",
                    scope,
                    "revisions",
                    List.of(id.toString()),
                    "permissions",
                    List.of("admin"))));
  }
}
