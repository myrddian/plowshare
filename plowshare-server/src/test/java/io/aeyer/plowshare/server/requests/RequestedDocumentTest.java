package io.aeyer.plowshare.server.requests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestedDocumentTest {

  private static final String REAL = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";

  @Test
  void reads_an_id_and_ignores_the_whitespace_around_it() {
    // Every one of the three strips, because an id pasted out of a search hit
    // arrives with whatever the paste brought.
    assertEquals(UUID.fromString(REAL), RequestedDocument.askedAbout("  " + REAL + " "));
    assertEquals(UUID.fromString(REAL), RequestedDocument.retrievedFrom(REAL + "\n"));
    assertEquals(UUID.fromString(REAL), RequestedDocument.documentId(" " + REAL));
  }

  @Test
  void each_refusal_says_something_the_others_do_not() {
    // The three exist because they answer different callers. A shared parser
    // is fine; a shared message would be wrong on two endpoints out of three.
    String asked =
        assertThrows(CallerFault.class, () -> RequestedDocument.askedAbout("nope")).getMessage();
    String retrieved =
        assertThrows(CallerFault.class, () -> RequestedDocument.retrievedFrom("nope")).getMessage();
    String named =
        assertThrows(CallerFault.class, () -> RequestedDocument.documentId("nope")).getMessage();

    assertTrue(asked.contains("citation"), asked);
    assertTrue(retrieved.contains("Leave the field out"), retrieved);
    assertTrue(named.contains("search hit"), named);
    assertEquals(3, java.util.Set.of(asked, retrieved, named).size());
  }

  @Test
  void every_refusal_keeps_the_cause_so_a_stack_trace_survives() {
    // documentId dropped it before this moved; the other two always chained.
    assertNotNull(
        assertThrows(CallerFault.class, () -> RequestedDocument.askedAbout("nope")).getCause());
    assertNotNull(
        assertThrows(CallerFault.class, () -> RequestedDocument.retrievedFrom("nope")).getCause());
    assertNotNull(
        assertThrows(CallerFault.class, () -> RequestedDocument.documentId("nope")).getCause());
  }

  @Test
  void a_null_is_a_callers_fault_and_not_a_null_pointer_exception() {
    // RequestedHome.in treats a null project as the global home; a document
    // id has no such default, so a null here is the same caller mistake as
    // an unparseable string, and gets the same one of the three messages —
    // not a fourth message, and not the NullPointerException that parse's
    // own raw.strip() would otherwise throw straight past this type.
    assertThrows(CallerFault.class, () -> RequestedDocument.askedAbout(null));
    assertThrows(CallerFault.class, () -> RequestedDocument.retrievedFrom(null));
    assertThrows(CallerFault.class, () -> RequestedDocument.documentId(null));
  }
}
