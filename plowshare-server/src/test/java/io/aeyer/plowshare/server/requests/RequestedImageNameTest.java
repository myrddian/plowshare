package io.aeyer.plowshare.server.requests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.faults.CallerFault;
import org.junit.jupiter.api.Test;

class RequestedImageNameTest {

  @Test
  void an_absent_name_records_the_filename_the_bytes_arrived_as() {
    assertEquals("red.png", RequestedImageName.in(null, "red.png"));
  }

  @Test
  void a_name_the_caller_chose_wins_over_the_filename() {
    assertEquals("the schematic", RequestedImageName.in("the schematic", "IMG_4831.png"));
  }

  @Test
  void a_blank_name_is_refused_rather_than_falling_back_to_the_filename() {
    // A name nobody chose is one nobody can recognise in a directory of
    // img_ files, so a caller who sent the field empty is told.
    String said =
        assertThrows(CallerFault.class, () -> RequestedImageName.in("  ", "red.png")).getMessage();

    assertTrue(said.startsWith("the `name` field is blank."), said);
  }
}
