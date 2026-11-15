package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.files.RemoteProvider;
import io.aeyer.plowshare.server.files.RunProviders;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** A denied rule read blocks the requested effect while preserving the model's next step. */
class FileRuleDenialTest {
  @TempDir Path temporary;

  @ParameterizedTest
  @CsvSource({
    "file_read,false",
    "file_stat,false",
    "file_edit,false",
    "file_delete,false",
    "file_move,false",
    "file_move,true"
  })
  void hiddenRulesDenyOnlyTheirOperationAndTheModelCanContinue(
      String name, boolean hiddenDestination) throws Exception {
    Path root = temporary.toRealPath();
    Path hidden = root.resolve(".githooks");
    String refusedPath = hidden.resolve("AGENTS.md").toString();
    String reason =
        "path "
            + refusedPath
            + " is inside this session's workspace, which is "
            + root
            + ", but hidden: a name in it starts with '.', and no file tool reaches a hidden path";
    var files = new FakeFiles().withRoots(List.of(root.toString()));
    SessionChannel channel =
        (session, request) ->
            refusedPath.equals(request.path())
                ? FileReply.refused(request.id(), reason)
                : files.ask(session, request);
    var remote = mock(RemoteProvider.class);
    when(remote.roots()).thenReturn(List.of(root));
    var providers = mock(RunProviders.class);
    when(providers.forRun(any(), any(), any(), any())).thenReturn(List.of(remote));
    var rules =
        new ScopedFileRules(
            new AgentRules(DataLayout.NONE, channel, session -> true, (project, session) -> true),
            providers);
    var tool = mock(AgentTool.class);
    when(tool.schema()).thenReturn(ToolSchema.from(name, "fixture", Map.of("type", "object")));
    when(tool.run(any(), any())).thenReturn("Allowed operation completed");
    var sibling = mock(AgentTool.class);
    when(sibling.schema())
        .thenReturn(ToolSchema.from("probe", "fixture", Map.of("type", "object")));
    when(sibling.run(any(), any())).thenReturn("Independent operation completed");
    String deniedArguments =
        arguments(
            hiddenDestination ? root.resolve("source.txt") : hidden.resolve("source.txt"),
            hiddenDestination ? hidden.resolve("target.txt") : root.resolve("target.txt"));
    String allowedArguments = arguments(root.resolve("source.txt"), root.resolve("target.txt"));
    List<List<ChatMessage>> contexts = new ArrayList<>();
    var step = new AtomicInteger();
    var dispatcher = mock(LlmDispatcher.class);
    when(dispatcher.stream(any(), any(), any()))
        .thenAnswer(
            call -> {
              ChatRequest request = call.getArgument(0);
              contexts.add(request.messages());
              return switch (step.getAndIncrement()) {
                case 0 ->
                    new Completion(
                        "",
                        "tool_calls",
                        TokenUsage.UNKNOWN,
                        List.of(
                            new ToolCall("denied", name, deniedArguments),
                            new ToolCall("sibling", "probe", "{}")));
                case 1 ->
                    new Completion(
                        "",
                        "tool_calls",
                        TokenUsage.UNKNOWN,
                        List.of(
                            new ToolCall("denied-again", name, deniedArguments),
                            new ToolCall("allowed", name, allowedArguments)));
                default ->
                    new Completion("Continued after denial", "stop", TokenUsage.UNKNOWN, List.of());
              };
            });
    var transcript = mock(Transcript.class, CALLS_REAL_METHODS);
    List<LoggedEntry> recorded = new ArrayList<>();
    doAnswer(
            call -> {
              recorded.add(call.getArgument(0));
              return null;
            })
        .when(transcript)
        .record(any());
    var runtime = new JobRuntime(dispatcher, List.of(tool, sibling));
    runtime.useFileRules(rules);
    var definition =
        new AgentDefinition(
            "worker",
            "Worker",
            "model",
            List.of(name, "probe"),
            List.of(),
            List.of(),
            8,
            8,
            "Help");

    Outcome outcome =
        runtime.run(
            definition,
            "Inspect the workspace",
            Home.of("fixture"),
            Budget.of(8),
            () -> false,
            "session",
            JobWatch.UNWATCHED,
            transcript);

    assertEquals(Outcome.Ending.ANSWERED, outcome.ending());
    assertEquals("Continued after denial", outcome.text());
    verify(tool, never()).run(eq(deniedArguments), any());
    verify(tool).run(eq(allowedArguments), any());
    verify(sibling).run(eq("{}"), any());
    var denial =
        recorded.stream()
            .filter(entry -> "denied".equals(entry.toolCallId()))
            .findFirst()
            .orElseThrow();
    assertEquals(ToolLines.DENIED, denial.outcome());
    assertTrue(denial.content().startsWith("DENY:"), denial.content());
    assertTrue(denial.content().contains(reason), denial.content());
    assertTrue(denial.content().contains("Nothing ran"), denial.content());
    assertTrue(
        recorded.stream()
            .anyMatch(
                entry ->
                    "denied-again".equals(entry.toolCallId())
                        && ToolLines.DENIED.equals(entry.outcome())));
    assertTrue(
        contexts.get(1).stream()
            .anyMatch(
                message ->
                    message.role() == ChatMessage.Role.TOOL
                        && message.content().toString().contains(denial.content())));
  }

  private static String arguments(Path path, Path destination) {
    return new ObjectMapper()
        .createObjectNode()
        .put("path", path.toString())
        .put("to", destination.toString())
        .toString();
  }
}
