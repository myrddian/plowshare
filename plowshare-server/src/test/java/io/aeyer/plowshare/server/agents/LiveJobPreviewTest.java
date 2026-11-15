package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.JobDelta;
import io.aeyer.plowshare.protocol.JobEvent;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.protocol.VerdictKind;
import io.aeyer.plowshare.protocol.WriteResult;
import io.aeyer.plowshare.server.access.AuthorityAction;
import io.aeyer.plowshare.server.access.AuthorityIds.AccountId;
import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.access.GrantRecipient;
import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.access.OperationInference;
import io.aeyer.plowshare.server.access.RetainedInferenceBoundary;
import io.aeyer.plowshare.server.agents.scribe.Scribe;
import io.aeyer.plowshare.server.archive.Archive;
import io.aeyer.plowshare.server.archive.MemoryWrites;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.images.ImageStore;
import io.aeyer.plowshare.server.information.InformationJobs;
import io.aeyer.plowshare.server.llm.ModelMediaProfiles;
import io.aeyer.plowshare.server.llm.dispatch.CallAuthorization;
import io.aeyer.plowshare.server.llm.dispatch.CallAuthorization.Disclosure;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.Embeddings;
import io.aeyer.plowshare.server.llm.dispatch.InferenceAccounting;
import io.aeyer.plowshare.server.llm.dispatch.InferenceAdmissions;
import io.aeyer.plowshare.server.llm.dispatch.InferenceBinding;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmException;
import io.aeyer.plowshare.server.llm.dispatch.LlmPool;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransport;
import io.aeyer.plowshare.server.llm.dispatch.ModelResponseGuards;
import io.aeyer.plowshare.server.llm.dispatch.ModelResponseRejected;
import io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger;
import io.aeyer.plowshare.server.llm.dispatch.PromptImages;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import io.aeyer.plowshare.server.llm.tokens.RatioTokenizer;
import io.aeyer.plowshare.server.security.ChatFiltering;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Production-shaped retained admission with real lane/dispatcher/runtime, without a database. */
class LiveJobPreviewTest {
  private static final Home HOME = Home.of("fixture");
  private final OperationAuthority operations = mock(OperationAuthority.class);
  private final InferenceAdmissions records = mock(InferenceAdmissions.class);
  private final OperationInference inference = new OperationInference(operations, records);

  private OperationAuthority.Admission admission() {
    AccountId account = new AccountId(UUID.randomUUID());
    return new OperationAuthority.Admission(
        UUID.randomUUID(),
        "alice",
        account,
        new ScopeId(UUID.randomUUID()),
        HOME,
        GrantRecipient.account(account),
        List.of(
            new OperationAuthority.Support(AuthorityAction.READ, UUID.randomUUID()),
            new OperationAuthority.Support(AuthorityAction.WRITE, UUID.randomUUID()),
            new OperationAuthority.Support(AuthorityAction.RUN_MODEL, UUID.randomUUID())));
  }

  private LlmDispatcher dispatcher(
      Transport transport, ChatFiltering filtering, ModelResponseGuards guards) {
    return new LlmDispatcher(
        List.of(
            new LlmPool("one", List.of("model"), Map.of(), 1, 1, Duration.ofSeconds(5), transport)),
        new NoOpTokenLedger(),
        value -> value,
        InferenceAccounting.NONE,
        null,
        null,
        filtering,
        new RetainedInferenceBoundary(records),
        ModelMediaProfiles.NONE,
        PromptImages.NONE,
        new RatioTokenizer(RatioTokenizer.DEFAULT_CHARACTERS_PER_TOKEN),
        guards);
  }

  private JobRuntime runtime(LlmDispatcher dispatcher) {
    return runtime(dispatcher, List.of());
  }

  private JobRuntime runtime(LlmDispatcher dispatcher, List<AgentTool> tools) {
    return new JobRuntime(
        dispatcher,
        tools,
        null,
        null,
        Instant::now,
        Reminding.NONE,
        ImageStore.NONE,
        RefusalDetector.NEVER,
        System::nanoTime,
        mock(AgentDelegates.class),
        operations,
        inference);
  }

  @Test
  void disclosure_is_explicit_and_survives_claim_and_check_only_wrappers() {
    CallAuthorization unknown = () -> {};
    assertEquals(Disclosure.BUFFERED, unknown.disclosure());
    assertEquals(Disclosure.BUFFERED, unknown.and(CallAuthorization.DIRECT).disclosure());
    assertEquals(Disclosure.LIVE, CallAuthorization.DIRECT.disclosure());
    var ordinary = inference.claimed(admission(), Budget.of(1).tryClaim().orElseThrow());
    assertEquals(Disclosure.LIVE, ordinary.disclosure());
    assertEquals(Disclosure.LIVE, ordinary.and(unknown).disclosure());
    assertEquals(ordinary.binding(), ordinary.and(unknown).binding());
    var system =
        inference.claimed(UUID.randomUUID(), Budget.of(1).tryClaim().orElseThrow(), unknown);
    assertEquals(Disclosure.BUFFERED, system.disclosure());
    var explicit =
        inference.claimed(
            UUID.randomUUID(), Budget.of(1).tryClaim().orElseThrow(), CallAuthorization.DIRECT);
    assertEquals(Disclosure.LIVE, explicit.disclosure());
  }

  @Test
  void ordinary_claims_stream_both_parts_before_completion_on_routed_and_pinned_paths() {
    for (boolean pinned : List.of(false, true)) {
      Transport transport = new Transport();
      List<String> deltas = new CopyOnWriteArrayList<>();
      transport.beforeCompletion = () -> assertEquals(List.of("thinking", "answer"), deltas);
      try (LlmDispatcher dispatcher =
          dispatcher(transport, ChatFiltering.NONE, ModelResponseGuards.NONE)) {
        var authorization = inference.claimed(admission(), Budget.of(1).tryClaim().orElseThrow());
        dispatch(dispatcher, pinned, authorization.and(() -> {}), collecting(deltas));
        assertEquals(List.of("thinking", "answer"), deltas);
        verify(records).retain(authorization.binding());
        verify(records).submitted(authorization.binding());
        verify(records, atLeastOnce()).require(authorization.binding());
      }
    }
  }

  @Test
  void internal_claims_withhold_previews_and_revoked_final_authority_never_releases_them() {
    for (boolean revoke : List.of(false, true)) {
      for (boolean pinned : List.of(false, true)) {
        Transport transport = new Transport();
        List<String> deltas = new CopyOnWriteArrayList<>();
        AtomicBoolean live = new AtomicBoolean(true);
        transport.beforeCompletion =
            () -> {
              assertTrue(deltas.isEmpty());
              if (revoke) live.set(false);
            };
        CallAuthorization authority =
            () -> {
              if (!live.get()) throw new CallerFault("revoked");
            };
        var authorization =
            inference.claimed(UUID.randomUUID(), Budget.of(1).tryClaim().orElseThrow(), authority);
        try (LlmDispatcher dispatcher =
            dispatcher(transport, ChatFiltering.NONE, ModelResponseGuards.NONE)) {
          if (revoke)
            assertThrows(
                CallerFault.class,
                () -> dispatch(dispatcher, pinned, authorization, collecting(deltas)));
          else dispatch(dispatcher, pinned, authorization, collecting(deltas));
          assertEquals(revoke ? List.of() : List.of("answer"), deltas);
        }
      }
    }
  }

  @Test
  void filters_and_response_guards_still_withhold_ordinary_live_previews() {
    for (boolean filter : List.of(false, true)) {
      for (boolean pinned : List.of(false, true)) {
        Transport transport = new Transport();
        List<String> deltas = new CopyOnWriteArrayList<>();
        transport.beforeCompletion = () -> assertTrue(deltas.isEmpty());
        ChatFiltering filtering = filter ? rejectingFilter() : ChatFiltering.NONE;
        ModelResponseGuards guards =
            filter
                ? ModelResponseGuards.NONE
                : model -> Optional.of(new ModelResponseGuards.Guard("fixture", text -> true));
        try (LlmDispatcher dispatcher = dispatcher(transport, filtering, guards)) {
          var authorization = inference.claimed(admission(), Budget.of(1).tryClaim().orElseThrow());
          if (filter)
            assertThrows(
                LlmException.class,
                () -> dispatch(dispatcher, pinned, authorization, collecting(deltas)));
          else
            assertThrows(
                ModelResponseRejected.class,
                () -> dispatch(dispatcher, pinned, authorization, collecting(deltas)));
          assertTrue(deltas.isEmpty());
        }
      }
    }
  }

  @Test
  void an_accepted_response_guard_releases_thinking_only_after_the_completion_is_checked() {
    for (boolean pinned : List.of(false, true)) {
      Transport transport = new Transport();
      List<String> deltas = new CopyOnWriteArrayList<>();
      transport.beforeCompletion = () -> assertTrue(deltas.isEmpty());
      ModelResponseGuards guards =
          model -> Optional.of(new ModelResponseGuards.Guard("fixture", text -> false));
      try (LlmDispatcher dispatcher = dispatcher(transport, ChatFiltering.NONE, guards)) {
        var authorization = inference.claimed(admission(), Budget.of(1).tryClaim().orElseThrow());
        dispatch(dispatcher, pinned, authorization, collecting(deltas));
        assertEquals(List.of("thinking", "answer"), deltas);
      }
    }
  }

  @Test
  void ordinary_runtime_watch_receives_thinking_and_answer_while_transport_is_active() {
    Transport transport = new Transport();
    Watch watch = new Watch();
    Recording transcript = new Recording();
    transport.beforeCompletion =
        () -> assertEquals(List.of(JobDelta.Part.THINKING, JobDelta.Part.ANSWER), watch.parts());
    try (LlmDispatcher dispatcher =
        dispatcher(transport, ChatFiltering.NONE, ModelResponseGuards.NONE)) {
      Outcome outcome = run(runtime(dispatcher), watch, transcript, false);
      assertEquals(Outcome.Ending.ANSWERED, outcome.ending());
      assertEquals("answer", outcome.text());
      assertTrue(transcript.entries.stream().anyMatch(entry -> entry.kind() == EntryKind.THINKING));
      ArgumentCaptor<InferenceBinding> binding = ArgumentCaptor.forClass(InferenceBinding.class);
      verify(records).retain(binding.capture());
      verify(records).submitted(binding.getValue());
      verify(records, atLeastOnce()).require(binding.getValue());
    }
  }

  @Test
  void an_unsubscribed_watch_receives_no_previews() {
    Transport transport = new Transport();
    Watch watch = new Watch();
    watch.subscribed = false;
    transport.beforeCompletion = () -> assertTrue(watch.parts().isEmpty());
    try (LlmDispatcher dispatcher =
        dispatcher(transport, ChatFiltering.NONE, ModelResponseGuards.NONE)) {
      assertEquals(
          Outcome.Ending.ANSWERED,
          run(runtime(dispatcher), watch, new Recording(), false).ending());
      assertTrue(watch.parts().isEmpty());
    }
  }

  @Test
  void a_regenerated_answer_never_reopens_the_watching_sink() {
    Transport transport = new Transport();
    Watch watch = new Watch();
    AtomicInteger inspected = new AtomicInteger();
    ModelResponseGuards guards =
        model ->
            Optional.of(
                new ModelResponseGuards.Guard("fixture", text -> inspected.getAndIncrement() == 0));
    transport.beforeCompletion = () -> assertTrue(watch.parts().isEmpty());
    try (LlmDispatcher dispatcher = dispatcher(transport, ChatFiltering.NONE, guards)) {
      assertEquals(
          Outcome.Ending.ANSWERED,
          run(runtime(dispatcher), watch, new Recording(), false, 2).ending());
      assertEquals(2, inspected.get());
      assertTrue(watch.parts().isEmpty());
    }
  }

  @Test
  void information_dependencies_and_confined_runs_keep_their_preview_suppression() {
    for (boolean confined : List.of(false, true)) {
      Transport transport = new Transport();
      Watch watch = new Watch();
      transport.beforeCompletion = () -> assertTrue(watch.parts().isEmpty());
      try (LlmDispatcher dispatcher =
          dispatcher(transport, ChatFiltering.NONE, ModelResponseGuards.NONE)) {
        JobRuntime runtime = runtime(dispatcher);
        if (!confined) {
          InformationJobs inputs = mock(InformationJobs.class);
          when(inputs.hasInputs("cnv_fixture")).thenReturn(true);
          when(inputs.logAllowed("cnv_fixture", "alice")).thenReturn(true);
          runtime.useInformationInputs(inputs);
        }
        assertEquals(
            Outcome.Ending.ANSWERED, run(runtime, watch, new Recording(), confined).ending());
        assertTrue(watch.parts().isEmpty());
      }
    }
  }

  @Test
  void a_document_read_does_not_make_the_harness_reject_an_operational_memory() {
    var archive = mock(Archive.class);
    when(archive.maxBodyChars()).thenReturn(1000);
    when(archive.writing()).thenReturn(true);
    when(archive.write(any()))
        .thenAnswer(
            call -> {
              MemoryWrites.Request request = call.getArgument(0);
              request.origin().authority().run();
              assertEquals("cnv_fixture", request.origin().sourceLog());
              assertEquals("alice", request.acting());
              return new WriteResult(VerdictKind.NEW, "mem_lesson", "filed", null, List.of());
            });
    var inputs = mock(InformationJobs.class);
    when(inputs.hasInputs("cnv_fixture")).thenReturn(true);
    when(inputs.logAllowed("cnv_fixture", "alice")).thenReturn(true);
    Transport transport = new Transport();
    transport.first =
        new Completion(
            "",
            "tool_calls",
            TokenUsage.UNKNOWN,
            List.of(
                new ToolCall(
                    "memory-call",
                    MemoryTools.WRITE_NAME,
                    "{\"summary\":\"Retry transient reads\",\"scope\":\"tooling\",\"body\":\"A plain retry succeeds\"}")));
    try (LlmDispatcher dispatcher =
        dispatcher(transport, ChatFiltering.NONE, ModelResponseGuards.NONE)) {
      JobRuntime runtime =
          runtime(dispatcher, List.of(new MemoryTools.Write(archive, () -> mock(Scribe.class))));
      runtime.useInformationInputs(inputs);
      var definition =
          new AgentDefinition(
              "fixture",
              "Fixture",
              "model",
              List.of(MemoryTools.WRITE_NAME),
              List.of(),
              List.of(),
              2,
              2,
              "Save the operational lesson");
      var outcome =
          runtime.runOperation(
              definition,
              "Remember the retry",
              HOME,
              Budget.of(2),
              () -> false,
              "session",
              new JobWatch("job_fixture", "session", new Watch()),
              new Recording(),
              TurnCap.of(2),
              List.of(),
              "alice",
              false,
              admission());
      assertEquals(Outcome.Ending.ANSWERED, outcome.ending());
      verify(archive).write(any());
    }
  }

  @Test
  void final_authority_failure_still_blocks_an_ordinary_runs_final_answer() {
    Transport transport = new Transport();
    AtomicBoolean live = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              if (!live.get()) throw new CallerFault("revoked");
              return null;
            })
        .when(operations)
        .require(any());
    Watch watch = new Watch();
    transport.beforeCompletion = () -> live.set(false);
    try (LlmDispatcher dispatcher =
        dispatcher(transport, ChatFiltering.NONE, ModelResponseGuards.NONE)) {
      Recording transcript = new Recording();
      assertThrows(CallerFault.class, () -> run(runtime(dispatcher), watch, transcript, false));
      assertFalse(transcript.entries.stream().anyMatch(entry -> entry.kind() == EntryKind.ANSWER));
      // Ordinary droppable previews are irretractable; this is not a no-preview revocation claim.
      assertEquals(List.of(JobDelta.Part.THINKING, JobDelta.Part.ANSWER), watch.parts());
    }
  }

  private Outcome run(JobRuntime runtime, Watch watch, Recording transcript, boolean confined) {
    return run(runtime, watch, transcript, confined, 1);
  }

  private Outcome run(
      JobRuntime runtime, Watch watch, Recording transcript, boolean confined, int modelCalls) {
    if (confined)
      runtime =
          runtime.forAdmittedTurn(
              new RunAdmission(() -> {}, job -> {}, () -> {}, "alice", (home, resources) -> {}));
    AgentDefinition definition =
        new AgentDefinition(
            "fixture",
            "Fixture",
            "model",
            List.of(),
            List.of(),
            List.of(),
            modelCalls,
            modelCalls,
            "Answer briefly");
    return runtime.runOperation(
        definition,
        "hello",
        HOME,
        Budget.of(modelCalls),
        () -> false,
        confined ? null : "session",
        new JobWatch("job_fixture", "session", watch),
        transcript,
        TurnCap.of(modelCalls),
        List.of(),
        "alice",
        false,
        admission());
  }

  private static Completion dispatch(
      LlmDispatcher dispatcher, boolean pinned, CallAuthorization authorization, Deltas sink) {
    ChatRequest request = ChatRequest.of("model", null, "hello").withAuthorization(authorization);
    return pinned
        ? dispatcher.streamOn("one", request, sink, () -> false)
        : dispatcher.stream(request, sink);
  }

  private static Deltas collecting(List<String> values) {
    return new Deltas() {
      @Override
      public void answered(String delta) {
        values.add(delta);
      }

      @Override
      public void thought(String delta) {
        values.add(delta);
      }
    };
  }

  private static ChatFiltering rejectingFilter() {
    return new ChatFiltering() {
      public ChatRequest input(ChatRequest request, BooleanSupplier cancelled) {
        return request;
      }

      public boolean bufferOutput() {
        return true;
      }

      public Completion output(Completion completion) {
        throw new LlmException("filtered");
      }
    };
  }

  private static final class Watch implements JobEvents {
    final List<JobDelta> deltas = new CopyOnWriteArrayList<>();
    boolean subscribed = true;

    public void publish(String session, JobEvent event) {}

    public boolean streaming(String session) {
      return subscribed;
    }

    public void stream(String session, JobDelta delta) {
      deltas.add(delta);
    }

    List<JobDelta.Part> parts() {
      return deltas.stream().map(JobDelta::part).toList();
    }
  }

  private static final class Recording implements Transcript {
    final List<LoggedEntry> entries = new ArrayList<>();

    public List<ChatMessage> before() {
      return List.of();
    }

    public void promptMeasured(int tokens) {}

    public String conversationId() {
      return "cnv_fixture";
    }

    public void record(LoggedEntry entry) {
      entries.add(entry);
    }
  }

  private static final class Transport implements LlmTransport {
    Runnable beforeCompletion = () -> {};
    Completion first;

    public String poolName() {
      return "one";
    }

    public Completion complete(
        String model, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      throw new AssertionError("stream only");
    }

    public Completion stream(
        String model,
        List<ChatMessage> messages,
        Sampling sampling,
        List<ToolSchema> tools,
        Deltas sink,
        BooleanSupplier cancelled) {
      if (first != null) {
        var result = first;
        first = null;
        return result;
      }
      sink.thought("thinking");
      sink.answered("answer");
      beforeCompletion.run();
      return new Completion("answer", "stop", TokenUsage.of(2, 2, 4), List.of());
    }

    public Embeddings embed(String model, List<String> values) {
      throw new AssertionError("no embeddings");
    }

    public void close() {}
  }
}
