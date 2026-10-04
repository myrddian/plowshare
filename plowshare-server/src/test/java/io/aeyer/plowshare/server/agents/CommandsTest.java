package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.CommandRunner;
import io.aeyer.plowshare.protocol.EnvironmentFile;
import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.files.ProviderRouter;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.ToolPre;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class CommandsTest {

  private static EnvironmentFile.Side side(String mode, boolean shells) {
    EnvironmentFile.Side d = EnvironmentFile.Side.DEFAULT;
    return new EnvironmentFile.Side(
        mode, shells, d.inherit(), d.env(), d.timeout(), d.outputBytes(), d.isolation());
  }

  @Test
  void refuses_what_the_environment_forbids_and_nothing_else() {
    assertTrue(
        Commands.refusal(List.of("pytest"), "local", side("off", false), null)
            .contains("mode off"));
    assertEquals(
        "the reason",
        Commands.refusal(List.of("pytest"), "local", side("off", false), "the reason"));
    assertTrue(
        Commands.refusal(List.of("bash", "-c", "x"), "local", side("open", false), null)
            .contains("is a shell"));
    assertNull(
        Commands.refusal(List.of("pytest"), "local", side("ask", false), null),
        "ask and gated are the gate's business, not this one's");
  }

  @Test
  void the_tail_is_the_last_lines_of_both_streams() {
    String out =
        String.join(
            "\n", java.util.stream.IntStream.range(0, 60).mapToObj(i -> "line " + i).toList());
    CommandRunner.Outcome outcome =
        new CommandRunner.Outcome(1, false, false, out, 0, "boom", 0, 1200);

    String tail = Commands.tail(outcome, 40);

    assertTrue(tail.contains("line 59"));
    assertTrue(!tail.contains("line 10\n"), "only the last lines");
    assertTrue(tail.contains("boom"));
  }

  // --- a run's port: the run tool's hook chain, and the run's cancel -----------------------

  private static final HookContext CONTEXT =
      new HookContext(
          "code_implementation",
          false,
          java.util.Set.of(),
          "story",
          "cnv_conductor",
          HookContext.SERVER);

  private static Commands.Port port(
      BooleanSupplier cancelled, BiFunction<HookContext, String, ToolPre> chain) {
    return Commands.port(
        new ProviderRouter(home -> List.of()),
        new Environments(name -> null, id -> null, null),
        cancelled,
        CONTEXT,
        chain);
  }

  private static Commands.Placed placed(FileProvider provider) {
    return new Commands.Placed(
        List.of("pytest", "-q"),
        Path.of("/repo"),
        provider,
        EnvironmentFile.LOCAL,
        side("open", false),
        null);
  }

  /**
   * Final review F1: the check is judged by the {@code run} tool's own tool_pre chain, handed
   * exactly what a {@code run} call for that command would carry — its arguments, and the context
   * the gate adds for the side it would run on.
   */
  @Test
  void the_port_judges_a_command_as_the_run_tool_s_hooks_would_see_a_run_call() {
    List<String> seenArguments = new ArrayList<>();
    AtomicReference<HookContext> seenContext = new AtomicReference<>();
    Commands.Port port =
        port(
            () -> false,
            (context, arguments) -> {
              seenContext.set(context);
              seenArguments.add(arguments);
              return ToolPre.allowed(arguments);
            });

    Commands.Verdict verdict = port.judge(placed(null));

    assertNull(verdict.denied());
    assertNull(verdict.asked());
    assertEquals(List.of("{\"command\":[\"pytest\",\"-q\"],\"cwd\":\"/repo\"}"), seenArguments);
    assertEquals(
        new HookContext.RunEnvironment(EnvironmentFile.LOCAL, "open", false, "none"),
        seenContext.get().environment());
  }

  @Test
  void a_hook_that_denies_the_run_call_denies_the_check_with_its_reason() {
    Commands.Port port =
        port(
            () -> false,
            (context, arguments) -> new ToolPre(arguments, "no pytest on Fridays", List.of()));

    assertEquals("no pytest on Fridays", port.judge(placed(null)).denied());
  }

  @Test
  void a_hook_that_asks_about_the_run_call_asks_about_the_check() {
    Commands.Port port =
        port(
            () -> false,
            (context, arguments) ->
                new ToolPre(arguments, null, List.of(), false, "pytest touches the network"));

    Commands.Verdict verdict = port.judge(placed(null));

    assertNull(verdict.denied());
    assertEquals("pytest touches the network", verdict.asked());
  }

  /** A check runs exactly the command that was set: a hook's rewrite is not run in its place. */
  @Test
  void a_hook_that_rewrites_the_run_call_denies_the_check() {
    Commands.Port port =
        port(
            () -> false,
            (context, arguments) ->
                ToolPre.allowed("{\"command\":[\"sandbox\",\"pytest\",\"-q\"],\"cwd\":\"/repo\"}"));

    assertTrue(
        port.judge(placed(null)).denied().contains("rewrote"), port.judge(placed(null)).denied());
  }

  @Test
  void a_chain_that_throws_denies_the_check() {
    Commands.Port port =
        port(
            () -> false,
            (context, arguments) -> {
              throw new IllegalStateException("boom");
            });

    assertTrue(port.judge(placed(null)).denied() != null);
  }

  // --- a run's port reads spec.md for the acceptance gate (spec 2026-09-29 §1b) ------------

  private static Commands.Port reading(FileProvider provider) {
    return Commands.port(
        new ProviderRouter(home -> List.of(provider)),
        new Environments(name -> null, id -> null, null),
        () -> false,
        CONTEXT,
        (context, arguments) -> ToolPre.allowed(arguments));
  }

  @Test
  void the_port_reads_a_file_under_the_run_s_first_root() {
    FileProvider machine = mock(FileProvider.class);
    when(machine.roots()).thenReturn(List.of(Path.of("/repo")));
    when(machine.read(org.mockito.ArgumentMatchers.eq(Path.of("/repo/docs/o/spec.md")), any()))
        .thenReturn(
            new io.aeyer.plowshare.protocol.Span(
                List.of("# spec", "## Acceptance"),
                0,
                2,
                false,
                io.aeyer.plowshare.protocol.Span.END));

    assertEquals(
        "# spec\n## Acceptance",
        reading(machine).read(io.aeyer.plowshare.protocol.Home.of("story"), "docs/o/spec.md"));
  }

  /** A spec cut at one read's bound would read as one with no acceptance section: refused. */
  @Test
  void a_file_longer_than_one_read_is_refused_rather_than_cut() {
    FileProvider machine = mock(FileProvider.class);
    when(machine.roots()).thenReturn(List.of(Path.of("/repo")));
    when(machine.read(any(), any()))
        .thenReturn(
            new io.aeyer.plowshare.protocol.Span(
                List.of("# spec"), 0, 5000, true, io.aeyer.plowshare.protocol.Span.LINES));

    io.aeyer.plowshare.server.files.WorkspaceRefusedException refused =
        org.junit.jupiter.api.Assertions.assertThrows(
            io.aeyer.plowshare.server.files.WorkspaceRefusedException.class,
            () ->
                reading(machine)
                    .read(io.aeyer.plowshare.protocol.Home.of("story"), "docs/o/spec.md"));
    assertTrue(refused.getMessage().contains("cannot read it whole"), refused.getMessage());
  }

  /** Final review F4: a run cancelled while its check runs kills the command, as run's does. */
  @Test
  void the_port_hands_the_run_s_cancel_to_the_command() {
    AtomicBoolean cancel = new AtomicBoolean();
    AtomicReference<BooleanSupplier> handed = new AtomicReference<>();
    FileProvider provider = mock(FileProvider.class);
    when(provider.run(any(), any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              handed.set(call.getArgument(4));
              return new CommandRunner.Outcome(0, false, false, "", 0, "", 0, 1);
            });

    port(cancel::get, (context, arguments) -> ToolPre.allowed(arguments)).run(placed(provider));

    assertEquals(false, handed.get().getAsBoolean());
    cancel.set(true);
    assertEquals(true, handed.get().getAsBoolean(), "the job's own cancel, not a constant");
  }
}
