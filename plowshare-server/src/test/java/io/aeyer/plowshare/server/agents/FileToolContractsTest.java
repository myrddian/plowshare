package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Window;
import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.files.ProviderRouter;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** File-tool contracts that must hold before any provider or database is consulted. */
class FileToolContractsTest {
  private static final Home PAYMENTS = Home.of("payments");
  private final FileProvider provider = mock(FileProvider.class);
  private final ProviderRouter router = new ProviderRouter(home -> List.of(provider));

  @AfterEach
  void no_provider_work() {
    verifyNoInteractions(provider);
  }

  // Construct each implementation explicitly so a factory mistake cannot hide a schema error.
  private List<AgentTool> tools() {
    return List.of(
        new FileTools.Read(router),
        new FileTools.Stat(router),
        new FileTools.Glob(router),
        new FileTools.Grep(router),
        new FileTools.Edit(router),
        new FileTools.Delete(router),
        new FileTools.Move(router),
        new FileTools.Roots(router),
        FileTools.of(FileTools.CODE_MAP_NAME, router));
  }

  @Test
  void every_tool_names_itself_and_no_two_share_a_name() {
    List<String> names = tools().stream().map(tool -> tool.schema().name()).toList();

    assertEquals(
        List.of(
            "file_read",
            "file_stat",
            "file_glob",
            "file_grep",
            "file_edit",
            "file_delete",
            "file_move",
            "file_roots",
            "code_map"),
        names);
    assertEquals(
        names.size(),
        Set.copyOf(names).size(),
        "JobRuntime refuses two tools under" + " one name, and knownTools is derived from these");
    assertEquals(
        FileTools.NAMES,
        Set.copyOf(names),
        "the set a boot serves and the tools this class builds are the same tools");
  }

  @Test
  void no_file_tool_lets_an_agent_name_its_own_tier() {
    // AgentTool's rule and the whole of the enforcement: home is a parameter
    // of run and never an argument in the schema. A tool that offered one
    // would let an agent widen its own reach to another project's workspace
    // by typing a word — the file-tool version of guessing a URL.
    for (AgentTool tool : tools()) {
      String schema = tool.schema().parameters().toString();
      assertFalse(
          schema.contains("project") || schema.contains("home"),
          tool.schema().name() + " offers a tier argument: " + schema);
    }
  }

  @Test
  void a_null_argument_is_the_runtimes_bug_and_not_the_models() {
    // Outside the try in every run, as MemoryTools does it and for its
    // reason: the never-throw rule is a rule about a CALLER's mistakes, and
    // a null here is nobody's turn to correct. Pinned in every one of them,
    // because a rule stated once and implemented once per tool is the one
    // the newest implementation gets wrong.
    for (AgentTool tool : tools()) {
      assertThrows(NullPointerException.class, () -> tool.run(null, PAYMENTS));
      assertThrows(NullPointerException.class, () -> tool.run("{}", null));
    }
  }

  @Test
  void a_tool_built_without_a_router_says_so_at_construction() {
    assertThrows(NullPointerException.class, () -> new FileTools.Read(null));
    assertThrows(NullPointerException.class, () -> new FileTools.Stat(null));
    assertThrows(NullPointerException.class, () -> new FileTools.Glob(null));
    assertThrows(NullPointerException.class, () -> new FileTools.Grep(null));
    assertThrows(NullPointerException.class, () -> new FileTools.Edit(null));
    assertThrows(NullPointerException.class, () -> new FileTools.Delete(null));
    assertThrows(NullPointerException.class, () -> new FileTools.Move(null));
    assertThrows(NullPointerException.class, () -> new FileTools.Roots(null));
  }

  @Test
  void the_cap_on_a_turn_is_below_the_cap_on_the_wire() {
    // Two bounds with two jobs, and this is the relation that keeps them
    // from collapsing back into one. Ordered rather than merely different:
    // a turn cap above the transport's would be silently clamped again by
    // Window.of, and the note file_read writes would then quote a number
    // that was not the one that won — the same silent shortening this whole
    // change exists to end, with the tool's own hand on it.
    //
    // It is also what lets Asked.reduced() and the javadoc around it say
    // that Window.of's clamp is unreachable from this tool.
    assertTrue(
        FileTools.MAX_READ_LINES < Window.MAX_WINDOW_LINES,
        "a read's cap must sit under the wire's — "
            + FileTools.MAX_READ_LINES
            + " against "
            + Window.MAX_WINDOW_LINES);
  }
}
