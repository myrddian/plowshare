package io.aeyer.plowshare.server.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The grant vocabulary: a scope, a mode, and how the two are written down.
 *
 * <h2>Why this is its own file now</h2>
 *
 * <p>These seven tests were the {@code vocabulary} section of {@code FileAccessTest} while {@link
 * Grant} and {@code FileAccess} lived in one package. Task 7 moved {@code FileAccess} into {@code
 * plowshare-protocol}, so that the client module can run the same containment check rather than a
 * second implementation of it, and {@link Grant} did not go with it: a grant is what an agent's
 * {@code scopes:} line declares, read by {@code AgentRegistry} and spent by {@code LocalProvider},
 * and the client module has no agents and no grants — it enforces paths against the workspace its
 * human set, and nothing about modes.
 *
 * <p>Nothing here changed in the move. The split is where the file already had a section rule, and
 * the count is preserved: seven here, twenty-nine left in {@code FileAccessTest}, thirty-six as
 * before.
 */
class GrantTest {

  @Test
  void write_implies_read_and_read_does_not_imply_write() {
    assertTrue(
        new Grant(Scope.WORKSPACE, Mode.WRITE).allows(Mode.READ),
        "a write grant covers reading, or every writer would need both");
    assertTrue(new Grant(Scope.WORKSPACE, Mode.WRITE).allows(Mode.WRITE));
    assertTrue(new Grant(Scope.WORKSPACE, Mode.READ).allows(Mode.READ));
    assertFalse(
        new Grant(Scope.WORKSPACE, Mode.READ).allows(Mode.WRITE),
        "a read grant must not carry write, which is the whole of the distinction");
  }

  @Test
  void the_scope_catalogue_names_only_the_workspace() {
    // Excalibur's second scope, ARCHIVE, is `data/archive/` — its memory
    // files as files. Plowshare's archive is Postgres, so that scope would
    // name a directory that does not exist: a grant nothing can satisfy,
    // which is worse than no grant because it reads as access in a
    // definition and resolves to nothing at run time.
    assertEquals(
        List.of(Scope.WORKSPACE),
        List.of(Scope.values()),
        "a scope with no filesystem behind it is a grant that can never be satisfied");
  }

  /**
   * The frontmatter spelling, pinned by literals on both sides.
   *
   * <p>{@link Grant#parse} and {@link Grant#declaration} derive the two tokens from the enum
   * constants' own names, so renaming {@code Scope.WORKSPACE} would silently change the language
   * every {@code scopes:} line in every deployed agent file is written in. Nothing in the compiler
   * notices that; this test is what does, which is why both sides are written out as literals
   * rather than round-tripped through {@code Scope.values()}.
   */
  @Test
  void a_grant_is_written_scope_colon_mode() {
    assertEquals(new Grant(Scope.WORKSPACE, Mode.READ), Grant.parse("workspace:read"));
    assertEquals(new Grant(Scope.WORKSPACE, Mode.WRITE), Grant.parse("workspace:write"));
    assertEquals("workspace:read", new Grant(Scope.WORKSPACE, Mode.READ).declaration());
    assertEquals("workspace:write", new Grant(Scope.WORKSPACE, Mode.WRITE).declaration());
  }

  /**
   * {@code scopes: [workspace]} is Excalibur's spelling, where a bare name in a list means read. It
   * is refused here rather than read as {@code read}: a grant nobody wrote is a grant nobody can be
   * held to, and the refusal carries the spelling that would have meant what they meant.
   */
  @Test
  void a_declaration_that_is_not_a_scope_and_a_mode_is_refused() {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> Grant.parse("workspace"));
    assertTrue(e.getMessage().contains("not a scope and a mode"), e.getMessage());
    // The correction, and not merely the complaint. All three of these
    // messages end in the same catalogue, which is why each assertion here
    // and below also names the clause that belongs to its own guard alone.
    assertTrue(e.getMessage().contains("workspace:read"), e.getMessage());
  }

  /**
   * The two halves are refused separately, because they are two different mistakes: a scope that
   * does not exist is an agent asking for a place this server has none of — Excalibur's {@code
   * archive} is exactly that, and is the port somebody will try — while a mode that does not exist
   * is a typo in a word with two legal values.
   */
  @Test
  void a_scope_or_a_mode_that_does_not_exist_is_refused() {
    IllegalArgumentException scope =
        assertThrows(IllegalArgumentException.class, () -> Grant.parse("archive:read"));
    assertTrue(scope.getMessage().contains("names the scope 'archive'"), scope.getMessage());

    IllegalArgumentException mode =
        assertThrows(IllegalArgumentException.class, () -> Grant.parse("workspace:execute"));
    assertTrue(mode.getMessage().contains("names the mode 'execute'"), mode.getMessage());

    // The first colon separates, so a third token is a bad mode rather than
    // a bad scope: the half somebody wrote deliberately is the half that is
    // believed. Splitting on the last colon instead reports 'workspace:read'
    // as the scope, which is a message about the wrong word.
    IllegalArgumentException extra =
        assertThrows(IllegalArgumentException.class, () -> Grant.parse("workspace:read:write"));
    assertTrue(extra.getMessage().contains("names the mode 'read:write'"), extra.getMessage());
  }

  /**
   * A list is parsed entry by entry, and an unreadable entry is reported in {@code parse}'s own
   * words rather than in a sentence about the list.
   */
  @Test
  void a_list_of_grants_is_parsed_entry_by_entry() {
    assertEquals(List.of(), Grant.parseAll(List.of()));
    assertEquals(
        List.of(new Grant(Scope.WORKSPACE, Mode.WRITE)),
        Grant.parseAll(List.of("workspace:write")));
    assertTrue(
        assertThrows(IllegalArgumentException.class, () -> Grant.parseAll(List.of("archive:read")))
            .getMessage()
            .contains("names the scope 'archive'"));
  }

  /**
   * Two grants over one scope are not additive, and the rule lives here because it is a fact about
   * a list of grants rather than about a file.
   *
   * <p>Its twin is {@code LocalProvider}'s constructor, which asks whether <em>any</em> grant in
   * the list allows a write: {@code [workspace:read, workspace:write]} is therefore a write grant,
   * and a list that reads as mostly-read carries one. The loader wraps this in a sentence naming
   * the file — {@code a_scope_granted_twice_is_refused} holds that half — but a caller assembling a
   * list in Java gets the same answer here.
   */
  @Test
  void a_list_of_grants_may_not_name_one_scope_twice() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> Grant.parseAll(List.of("workspace:read", "workspace:write")));
    assertTrue(e.getMessage().contains("two grants over one scope"), e.getMessage());
    assertTrue(e.getMessage().contains("workspace:read"), e.getMessage());
    assertTrue(e.getMessage().contains("workspace:write"), e.getMessage());
  }
}
