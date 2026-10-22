package io.aeyer.plowshare.protocol.frames;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CodeTest {

  @Test
  void capacity_exhausted_is_a_transport_origin_code() {
    assertEquals(429, Code.CAPACITY_EXHAUSTED.httpStatus());
    assertEquals("capacity_exhausted", Code.CAPACITY_EXHAUSTED.slug());
  }
}
