package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.InformationContent;
import io.aeyer.plowshare.protocol.InformationRequest;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;
import org.junit.jupiter.api.Test;

/** The public command and multipart boundaries preserve absence versus explicit JSON null. */
class InformationContentInputsTest {
  private Map<String, Object> revision(Map<String, Object> typing) {
    return new HashMap<>(
        Map.of(
            "view",
            InformationContent.VIEW,
            "requestId",
            UUID.randomUUID().toString(),
            "expectedRevision",
            UUID.randomUUID().toString(),
            "typing",
            typing));
  }

  @Test
  void inherit_rejects_null_members_but_preserves_null_inside_the_instance() {
    var absent =
        (InformationRequest.ContentRevise)
            InformationContentInputs.command("content.revise", revision(Map.of("mode", "inherit")));
    assertNull(absent.typing().metadata());
    var metadata = new HashMap<String, Object>();
    metadata.put("severity", null);
    var retained =
        (InformationRequest.ContentRevise)
            InformationContentInputs.command(
                "content.revise", revision(Map.of("mode", "inherit", "metadata", metadata)));
    assertNotNull(retained.typing().metadata());
    var nullMember = new HashMap<String, Object>(Map.of("mode", "inherit"));
    nullMember.put("metadata", null);
    assertThrows(
        CallerFault.class,
        () -> InformationContentInputs.command("content.revise", revision(nullMember)));
    assertThrows(
        CallerFault.class,
        () -> InformationContentInputs.multipart("{\"mode\":\"inherit\",\"metadata\":null}"));
  }

  @Test
  void command_identity_and_shape_are_strict_before_application_logic() {
    var fields = revision(Map.of("mode", "clear"));
    fields.put("expectedRevision", "1-1-1-1-1");
    assertThrows(
        CallerFault.class, () -> InformationContentInputs.command("content.revise", fields));
    fields.put("expectedRevision", UUID.randomUUID().toString());
    fields.put("unexpected", true);
    assertThrows(
        CallerFault.class, () -> InformationContentInputs.command("content.revise", fields));
    fields.remove("unexpected");
    fields.put("corpus", null);
    assertThrows(
        CallerFault.class, () -> InformationContentInputs.command("content.revise", fields));
  }

  @Test
  void multipart_rejects_duplicate_decoded_keys_and_unknown_typing_fields() {
    assertThrows(
        CallerFault.class,
        () ->
            InformationContentInputs.multipart("{\"mode\":\"clear\",\"mo\\u0064e\":\"inherit\"}"));
    assertThrows(
        CallerFault.class,
        () -> InformationContentInputs.multipart("{\"mode\":\"clear\",\"grant\":\"*\"}"));
  }
}
