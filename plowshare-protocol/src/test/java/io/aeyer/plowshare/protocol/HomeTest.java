package io.aeyer.plowshare.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Home preserves valid names and rejects ambiguous aliases before any project lookup. */
class HomeTest {

  @Test
  void a_valid_project_name_is_kept_exactly_as_given() {
    assertEquals("payments api", Home.of("payments api").project());
  }

  @ParameterizedTest
  @ValueSource(strings = {" payments ", "payments\n", "payments\tother"})
  void an_ambiguous_or_control_containing_name_is_rejected(String name) {
    assertThrows(IllegalArgumentException.class, () -> Home.of(name));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "\t"})
  void a_blank_project_name_is_refused_rather_than_folded_into_global(String blank) {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Home.of(blank));
    assertTrue(
        e.getMessage().contains("global"),
        "the refusal must point the caller at Home.global(), which is what they meant");
  }

  /** Global is the absence of a project, so no project can be named into it. */
  @Test
  void global_is_not_a_project_called_global() {
    assertTrue(Home.global().isGlobal());
    assertNull(Home.global().project());

    assertFalse(
        Home.of("global").isGlobal(),
        "a project that happens to be called global is an ordinary project");
    assertEquals("global", Home.of("global").project());
  }
}
