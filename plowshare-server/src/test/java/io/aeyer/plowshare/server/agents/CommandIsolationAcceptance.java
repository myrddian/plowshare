package io.aeyer.plowshare.server.agents;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Bubblewrap;
import io.aeyer.plowshare.protocol.CommandIsolation;
import io.aeyer.plowshare.protocol.EnvironmentFile;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.approvals.RunApprovalStore;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.files.LocalProvider;
import io.aeyer.plowshare.server.files.Mode;
import io.aeyer.plowshare.server.files.ProviderRouter;
import io.aeyer.plowshare.server.files.Scope;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.ToolPre;
import io.aeyer.plowshare.server.images.ImageStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Explicit Linux acceptance through the server gate/provider; only persistence is mocked. */
public final class CommandIsolationAcceptance {
  private CommandIsolationAcceptance() {}

  /** Arguments are the installed bubblewrap path, launcher path and runtime directories. */
  public static void main(String[] args) throws Exception {
    Path temporary = Files.createTempDirectory("server-isolation-acceptance-");
    Path root = Files.createDirectory(temporary.resolve("workspace"));
    Path output = Files.createDirectory(root.resolve("output"));
    Files.createDirectory(root.resolve(".plowshare"));
    Files.writeString(root.resolve("plowshare.json"), "{\"version\":1,\"name\":\"test\"}");
    Path scratch =
        Files.createDirectory(
            temporary.resolve("scratch"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    Path environment = temporary.resolve("environment.yml");
    Files.writeString(
        environment, "server:\n  mode: gated\n  shells: true\n  isolation: bubblewrap\n");
    Home home = Home.of("test");
    ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
    ProjectRecord project =
        new ProjectRecord("test", root, List.of(), List.of(), "DISJOINT", List.of("output"));
    when(projects.find("test")).thenReturn(Optional.of(project));
    when(projects.effectiveExclusions(project)).thenReturn(List.of());
    var real =
        new Bubblewrap(
            new Bubblewrap.Configuration(
                Path.of(args[0]),
                Path.of(args[1]),
                Arrays.stream(args).skip(2).map(Path::of).toList(),
                scratch));
    AtomicInteger starts = new AtomicInteger();
    CommandIsolation counted =
        (command, reads, writes, host, cancelled) -> {
          starts.incrementAndGet();
          return real.run(command, reads, writes, host, cancelled);
        };
    var write =
        new LocalProvider(
            projects,
            home,
            List.of(new Grant(Scope.WORKSPACE, Mode.WRITE)),
            ImageStore.NONE,
            counted);
    var read =
        new LocalProvider(
            projects,
            home,
            List.of(new Grant(Scope.WORKSPACE, Mode.READ)),
            ImageStore.NONE,
            counted);
    Environments environments = new Environments(name -> 7L, id -> environment, null);
    RunApprovalStore approvals = mock(RunApprovalStore.class);
    when(approvals.consume(anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(Optional.empty());
    var tool =
        new RunTool(
            new ProviderRouter(ignored -> List.of(write)),
            () -> environments,
            () -> false,
            () -> approvals,
            "conversation",
            "session",
            "coder",
            new TurnEnd());
    var context =
        new HookContext("coder", false, Set.of(RunTool.NAME), "test", null, HookContext.SERVER);
    List<String> argv =
        List.of("/bin/sh", "-c", "echo allowed > output/result; ! echo bad > forbidden");
    var json = new ObjectMapper().createObjectNode();
    argv.forEach(json.putArray("command")::add);
    json.put("cwd", root.toString());
    String call = json.toString();
    ToolPre denied = tool.gate(call, home, context, (ctx, input) -> ToolPre.allowed(input));
    require(
        denied.isDenied() && starts.get() == 0 && !Files.exists(output.resolve("result")),
        "missing approval started a command");
    // Hook denial wins even over an otherwise valid approval.
    RunApproval approval =
        new RunApproval(
            "approval",
            7L,
            "conversation",
            "conversation",
            "coder",
            "server",
            argv,
            root.toString(),
            null,
            RunApproval.ALLOWED,
            RunApproval.ONCE,
            null,
            "person",
            Instant.now(),
            Instant.now());
    when(approvals.consume(7L, "conversation", "server", argv, root.toString(), null))
        .thenReturn(Optional.of(approval));
    denied =
        tool.gate(
            call, home, context, (ctx, input) -> new ToolPre(input, "denied by policy", List.of()));
    require(denied.isDenied() && starts.get() == 0, "policy denial started a command");
    ToolPre accepted = tool.gate(call, home, context, (ctx, input) -> ToolPre.allowed(input));
    require(
        !accepted.isDenied(), "consumed approval did not admit the command: " + accepted.denied());
    String result = tool.run(accepted.arguments(), home);
    require(
        result.startsWith("exit 0")
            && starts.get() == 1
            && Files.exists(output.resolve("result"))
            && !Files.exists(root.resolve("forbidden")),
        "approved command failed isolation: " + result);
    verify(approvals, times(2)).consume(7L, "conversation", "server", argv, root.toString(), null);
    var side =
        EnvironmentFile.Side.DEFAULT.with(
            EnvironmentFile.parse("server:\n  mode: open\n  isolation: bubblewrap\n").server());
    try {
      read.run(root, List.of("/bin/true"), side, Duration.ofSeconds(2), () -> false);
      throw new AssertionError("missing WRITE grant admitted a command");
    } catch (WorkspaceRefusedException expected) {
      require(starts.get() == 1, "grant refusal started the backend");
    }
    try {
      write.run(temporary, List.of("/bin/true"), side, Duration.ofSeconds(2), () -> false);
      throw new AssertionError("outside workspace admitted a command");
    } catch (WorkspaceRefusedException expected) {
      require(starts.get() == 1, "workspace refusal started the backend");
    }
    System.out.println(
        "Server command isolation acceptance passed: approval consumption, missing approval, hook denial, WRITE grant, workspace fence, actual confined process");
  }

  private static void require(boolean condition, String reason) {
    if (!condition) throw new AssertionError(reason);
  }
}
