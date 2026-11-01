package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.protocol.ToolContent;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.blobs.*;
import io.aeyer.plowshare.server.llm.*;
import io.aeyer.plowshare.server.llm.accounting.*;
import io.aeyer.plowshare.server.llm.counting.PromptCount;
import io.aeyer.plowshare.server.llm.dispatch.*;
import io.aeyer.plowshare.server.relay.tools.*;
import io.aeyer.plowshare.server.security.ChatFiltering;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deterministic provider -> loop -> next request acceptance; no database or actual model. */
class TypedPngObservationTest {
  static final String CONVERSATION = "cnv_0000000000000001";
  static final String IMAGE = "img_" + "a".repeat(32);
  static final byte[] PNG =
      Base64.getDecoder()
          .decode(
              "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=");
  static final ModelMediaCapabilities MEDIA =
      new ModelMediaCapabilities(
          Set.of("text/plain", "image/png"),
          Map.of("text", new ModelMediaCapabilities.OutputMode(Set.of("text/plain"))));
  static final UsageAttribution OWNER =
      UsageAttribution.project("owner", "1", UsageAttribution.Operation.AGENT_CHAT)
          .withExecution(
              UsageLineage.root(CONVERSATION),
              UsageLineage.root("job"),
              UsageLineage.NONE,
              "viewer",
              1L,
              1L);
  @TempDir Path directory;

  static ToolContent.Body body(ToolContent.Use use) {
    return new ToolContent.Body(
        "content/1",
        List.of(
            ToolContent.Part.text("Screen captured"),
            new ToolContent.Part(ToolContent.Kind.IMAGE, null, IMAGE, 0, use)));
  }

  static EntryRecord entry(int ordinal, LoggedEntry entry) {
    return new EntryRecord(
        CONVERSATION,
        ordinal,
        entry.kind(),
        entry.content(),
        entry.toolCallId(),
        entry.toolCalls(),
        null,
        null,
        1,
        Instant.now(),
        entry.tookMillis(),
        null,
        null,
        entry.observation());
  }

  static class History implements Transcript {
    final List<EntryRecord> rows = new ArrayList<>();

    public List<ChatMessage> before() {
      return List.of();
    }

    public UsageAttribution usage() {
      return OWNER;
    }

    public String conversationId() {
      return CONVERSATION;
    }

    public void recordDurably(LoggedEntry entry) {
      record(entry);
    }

    public void record(LoggedEntry entry) {
      rows.add(entry(rows.size() + 1, entry));
    }

    public List<Content.StoredImage> recordToolResult(LoggedEntry result) {
      record(result);
      return Projection.images(rows.getLast());
    }

    public void promptMeasured(int count) {}
  }

  static final class Backend implements LlmTransport {
    final List<List<ChatMessage>> calls = new ArrayList<>();
    long tokens = 200;
    boolean unknown;
    ModelMediaCapabilities media = MEDIA;

    public Set<Sampling.Parameter> carries() {
      return EnumSet.allOf(Sampling.Parameter.class);
    }

    public String poolName() {
      return "vision";
    }

    public ModelMediaCapabilities mediaCapabilities(String model) {
      return media;
    }

    public OptionalInt contextLength(String model) {
      return OptionalInt.of(1024);
    }

    public PromptCount countChat(String model, ChatRequest request) {
      assertTrue(request.messages().stream().anyMatch(ChatMessage::carriesAnImage));
      assertFalse(PromptImages.hasStored(request));
      return unknown
          ? PromptCount.unknown("vision", model, "unsupported_images")
          : new PromptCount(
              tokens,
              PromptCount.Basis.MEASURED,
              "fixture",
              "vision",
              model,
              "verified-v1",
              Instant.now(),
              0,
              false,
              List.of());
    }

    public Completion complete(
        String model, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      calls.add(messages);
      return calls.size() == 1 && !tools.isEmpty()
          ? new Completion(
              "",
              "tool_calls",
              TokenUsage.UNKNOWN,
              List.of(new ToolCall("capture", "screenshot", "{}")))
          : new Completion("I can see the screenshot", "stop", TokenUsage.UNKNOWN, List.of());
    }

    public Completion stream(
        String model,
        List<ChatMessage> messages,
        Sampling sampling,
        List<ToolSchema> tools,
        Deltas sink,
        BooleanSupplier abandoned) {
      return complete(model, messages, sampling, tools);
    }

    public Embeddings embed(String model, List<String> input) {
      throw new AssertionError();
    }

    public void close() {}
  }

  static LlmDispatcher dispatcher(
      Backend backend, PromptImages images, ModelMediaProfiles profiles) {
    var pool =
        new LlmPool(
            "vision",
            List.of("model"),
            Map.of("active", "model"),
            1,
            1,
            Duration.ofSeconds(1),
            backend,
            Set.of("model"));
    return new LlmDispatcher(
        List.of(pool),
        new NoOpTokenLedger(),
        type -> "model",
        InferenceAccounting.NONE,
        null,
        null,
        ChatFiltering.NONE,
        InferenceBoundary.FIXTURE,
        profiles,
        images);
  }

  static TrajectoryPromptImages resolver(TrajectoryArtifacts artifacts, BinaryBlobStorage blobs) {
    when(artifacts.read(anyList(), any()))
        .thenAnswer(
            call -> {
              List<Content.StoredImage> refs = call.getArgument(0);
              return refs.stream()
                  .map(
                      ref ->
                          new TrajectoryArtifacts.Image(
                              ref,
                              new BlobKey("projects/1", "a".repeat(64)),
                              new BlobClaim(
                                  "trajectory-entry",
                                  CONVERSATION + ":" + ref.entry(),
                                  ref.slot(),
                                  BlobClaim.Retention.RELEASABLE),
                              PNG.length,
                              "image/png"))
                  .toList();
            });
    when(blobs.read(any(BlobKey.class), any(BlobClaim.class))).thenReturn(PNG);
    return new TrajectoryPromptImages(artifacts, blobs);
  }

  static ChatRequest request() {
    var log =
        List.of(
            entry(1, LoggedEntry.utterance("Inspect", null)),
            entry(2, LoggedEntry.answer("", List.of(new ToolCall("capture", "screenshot", "{}")))),
            entry(
                3,
                LoggedEntry.toolResult("capture", "Screen captured")
                    .observing(
                        new ToolObservation(
                            UUID.randomUUID(),
                            body(ToolContent.Use.OBSERVATION),
                            body(ToolContent.Use.OBSERVATION)))));
    return ChatRequest.of("active", Projection.of(log, false))
        .withAttribution(OWNER)
        .withSampling(Sampling.NONE.withMaxTokens(128));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"image/jpeg", "image/webp"})
  void retained_actual_format_drives_every_dispatch_path(String mime) throws Exception {
    byte[] pixels;
    if (mime.equals("image/webp")) {
      try (var input = getClass().getResourceAsStream("/media/still-lossless.webp.base64")) {
        assertNotNull(input);
        pixels = java.util.Base64.getMimeDecoder().decode(input.readAllBytes());
      }
    } else {
      var output = new java.io.ByteArrayOutputStream();
      javax.imageio.ImageIO.write(
          new java.awt.image.BufferedImage(3, 2, java.awt.image.BufferedImage.TYPE_INT_RGB),
          "jpeg",
          output);
      pixels = output.toByteArray();
    }
    var artifacts = mock(TrajectoryArtifacts.class);
    var blobs = mock(BinaryBlobStorage.class);
    var resolver = resolver(artifacts, blobs);
    var ref = (Content.StoredImage) request().messages().getLast().parts().getLast();
    when(artifacts.read(anyList(), any()))
        .thenReturn(
            List.of(
                new TrajectoryArtifacts.Image(
                    ref,
                    new BlobKey("projects/1", "a".repeat(64)),
                    new BlobClaim(
                        "trajectory-entry", CONVERSATION + ":3", 0, BlobClaim.Retention.RELEASABLE),
                    pixels.length,
                    mime)));
    when(blobs.read(any(BlobKey.class), any(BlobClaim.class))).thenReturn(pixels);
    var backend = new Backend();
    backend.media = new ModelMediaCapabilities(Set.of("text/plain", mime), MEDIA.outputs());
    try (var models = dispatcher(backend, resolver, model -> Optional.of(backend.media))) {
      assertEquals(1, models.poolsServing(request()).size());
      models.complete(request());
      models.stream(request(), Deltas.DISCARDING);
      models.streamFold(request(), Deltas.DISCARDING);
      models.streamOn("vision", request(), Deltas.DISCARDING, () -> false);
      for (var call : backend.calls) {
        var image = (Content.Image) call.getLast().parts().getLast();
        assertEquals(
            "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(pixels),
            image.dataUri());
      }
      backend.unknown = true;
      assertThrows(IllegalArgumentException.class, () -> models.complete(request()));
      assertEquals(4, backend.calls.size());
    }
    try (var models = dispatcher(backend, resolver, model -> Optional.of(MEDIA))) {
      assertThrows(UnsupportedMediaException.class, () -> models.complete(request()));
      assertThrows(UnsupportedMediaException.class, () -> models.poolsServing(request()));
    }
  }

  @Test
  void screenshot_result_reaches_same_active_model_and_replay_never_recaptures() throws Exception {
    var invocations = mock(RelayToolInvocations.class);
    var id = UUID.randomUUID();
    var body = body(ToolContent.Use.OBSERVATION);
    when(invocations.invoke(any(), any(), any(), anyString(), any()))
        .thenReturn(
            new RelayToolInvocations.Outcome(
                id, RelayToolCodec.State.COMPLETED, body.text(), body));
    var binding =
        new RelayToolDefinition(
            "fixture",
            "capture",
            "producer",
            "screenshot",
            "Capture the screen",
            List.of(),
            30,
            null,
            "content/1");
    var tool = new RelayAgentTool(List.of(binding), invocations);
    var artifacts = mock(TrajectoryArtifacts.class);
    var blobs = mock(BinaryBlobStorage.class);
    var backend = new Backend();
    var history = new History();
    try (var models =
        dispatcher(backend, resolver(artifacts, blobs), model -> Optional.of(MEDIA))) {
      var runtime = new JobRuntime(models, List.of(tool));
      Files.writeString(
          directory.resolve("viewer.md"),
          "---\nname: viewer\ndescription: Inspect a screenshot\nmodel: active\nmax-turns: 4\nmax-model-calls: 6\ntools: [screenshot]\n---\nInspect the screen.");
      var definition =
          AgentRegistry.load(directory, runtime.knownTools())
              .get("viewer")
              .sampling(Sampling.NONE.withMaxTokens(128));
      var outcome =
          runtime.run(
              definition,
              "Inspect the screen",
              Home.of("fixture"),
              Budget.of(6),
              () -> false,
              "job",
              JobWatch.UNWATCHED,
              history,
              TurnCap.from(definition),
              List.of());
      assertEquals(Outcome.Ending.ANSWERED, outcome.ending(), outcome.detail());
      assertEquals(2, backend.calls.size());
      var next = backend.calls.get(1);
      var picture =
          next.stream()
              .flatMap(m -> m.parts().stream())
              .filter(Content.Image.class::isInstance)
              .map(Content.Image.class::cast)
              .findFirst()
              .orElseThrow();
      assertArrayEquals(PNG, Base64.getDecoder().decode(picture.dataUri().split(",", 2)[1]));
      assertEquals(IMAGE, picture.uid());
      int toolIndex =
          java.util.stream.IntStream.range(0, next.size())
              .filter(i -> next.get(i).role() == ChatMessage.Role.TOOL)
              .findFirst()
              .orElseThrow();
      assertEquals("capture", next.get(toolIndex).toolCallId());
      assertTrue(next.get(toolIndex + 1).carriesAnImage());
      var saved =
          history.rows.stream().filter(row -> row.observation() != null).findFirst().orElseThrow();
      assertEquals(body, saved.observation().original());
      assertFalse(saved.content().contains("base64"));
      var replay = Projection.of(List.copyOf(history.rows), false);
      models.complete(
          ChatRequest.of("active", replay)
              .withAttribution(OWNER)
              .withSampling(definition.sampling()));
      assertEquals(
          picture,
          backend.calls.getLast().stream()
              .flatMap(m -> m.parts().stream())
              .filter(Content.Image.class::isInstance)
              .findFirst()
              .orElseThrow());
      verify(invocations, times(1)).invoke(any(), any(), any(), anyString(), any());
    }
  }

  @Test
  void withheld_or_output_only_content_never_becomes_a_native_observation() {
    for (var use : ToolContent.Use.values()) {
      var body = body(use);
      var raw = new ToolObservation(UUID.randomUUID(), body, body);
      var original = entry(1, LoggedEntry.toolResult("capture", "provider result").observing(raw));
      assertEquals(use == ToolContent.Use.OUTPUT ? 0 : 1, Projection.images(original).size());
      var redacted =
          entry(2, LoggedEntry.toolResult("capture", "redacted").observing(raw.afterHook(false)));
      assertTrue(Projection.images(redacted).isEmpty());
      assertEquals(body, redacted.observation().original());
      assertEquals(
          raw,
          LoggedEntry.toolResult("capture", "result")
              .observing(raw)
              .told("ok")
              .took(Duration.ofMillis(1))
              .observation());
    }
  }

  @Test
  void images_follow_all_correlated_results_in_a_batch() {
    var body = body(ToolContent.Use.BOTH);
    var log =
        List.of(
            entry(
                1,
                LoggedEntry.answer(
                    "",
                    List.of(
                        new ToolCall("a", "screenshot", "{}"), new ToolCall("b", "other", "{}")))),
            entry(
                2,
                LoggedEntry.toolResult("a", "captured")
                    .observing(new ToolObservation(UUID.randomUUID(), body, body))),
            entry(3, LoggedEntry.toolResult("b", "text")));
    var projected = Projection.of(log, false);
    assertEquals(
        List.of(
            ChatMessage.Role.ASSISTANT,
            ChatMessage.Role.TOOL,
            ChatMessage.Role.TOOL,
            ChatMessage.Role.USER),
        projected.stream().map(ChatMessage::role).toList());
    assertTrue(projected.getLast().carriesAnImage());
  }

  @Test
  void unknown_or_over_budget_image_counts_refuse_every_inference_path() {
    var backend = new Backend();
    var artifacts = mock(TrajectoryArtifacts.class);
    var blobs = mock(BinaryBlobStorage.class);
    try (var models =
        dispatcher(backend, resolver(artifacts, blobs), model -> Optional.of(MEDIA))) {
      for (boolean unknown : List.of(true, false)) {
        backend.unknown = unknown;
        backend.tokens = 1000;
        assertThrows(IllegalArgumentException.class, () -> models.complete(request()));
        assertThrows(
            IllegalArgumentException.class, () -> models.stream(request(), Deltas.DISCARDING));
        assertThrows(
            IllegalArgumentException.class, () -> models.streamFold(request(), Deltas.DISCARDING));
        assertThrows(
            IllegalArgumentException.class,
            () -> models.streamOn("vision", request(), Deltas.DISCARDING, () -> false));
      }
      backend.tokens = 100;
      assertThrows(
          IllegalArgumentException.class,
          () -> models.complete(request().withSampling(Sampling.NONE)));
      assertTrue(backend.calls.isEmpty());
    }
  }

  @Test
  void no_profile_or_missing_bytes_refuses_instead_of_sending_only_text() {
    var artifacts = mock(TrajectoryArtifacts.class);
    var blobs = mock(BinaryBlobStorage.class);
    var resolver = resolver(artifacts, blobs);
    var backend = new Backend();
    try (var models = dispatcher(backend, resolver, ModelMediaProfiles.NONE)) {
      assertThrows(UnsupportedMediaException.class, () -> models.complete(request()));
      assertTrue(backend.calls.isEmpty());
    }
    reset(blobs);
    when(blobs.read(any(BlobKey.class), any(BlobClaim.class)))
        .thenThrow(new BlobUnavailableException());
    assertThrows(BlobUnavailableException.class, () -> resolver.resolve(request()));
    assertThrows(IllegalStateException.class, () -> PromptImages.NONE.resolve(request()));
  }

  @Test
  void oversized_prompts_are_refused_before_reading_any_bytes() {
    var artifacts = mock(TrajectoryArtifacts.class);
    var blobs = mock(BinaryBlobStorage.class);
    var ref = (Content.StoredImage) request().messages().getLast().parts().getLast();
    when(artifacts.read(anyList(), any()))
        .thenReturn(
            List.of(
                new TrajectoryArtifacts.Image(
                    ref,
                    new BlobKey("projects/1", "a".repeat(64)),
                    new BlobClaim(
                        "trajectory-entry", CONVERSATION + ":3", 0, BlobClaim.Retention.RELEASABLE),
                    21L * 1024 * 1024,
                    "image/png")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TrajectoryPromptImages(artifacts, blobs).resolve(request()));
    verifyNoInteractions(blobs);
  }

  static final class Producer implements AgentTool.ContentTool {
    final java.util.concurrent.atomic.AtomicInteger captures =
        new java.util.concurrent.atomic.AtomicInteger();

    public boolean typedResults(Home home) {
      return true;
    }

    public ToolSchema schema() {
      return ToolSchema.from(
          "screenshot", "Capture", Map.of("type", "object", "properties", Map.of()));
    }

    public String run(String arguments, Home home) {
      throw new AssertionError("typed tool was flattened");
    }

    public Result runResult(String arguments, Home home, UsageAttribution owner) {
      captures.incrementAndGet();
      var body = body(ToolContent.Use.OBSERVATION);
      return new Result("captured", new ToolObservation(UUID.randomUUID(), body, body));
    }
  }

  AgentDefinition definition(JobRuntime runtime) throws Exception {
    Files.writeString(
        directory.resolve("viewer.md"),
        "---\nname: viewer\ndescription: Inspect a screenshot\nmodel: active\nmax-turns: 4\nmax-model-calls: 6\ntools: [screenshot]\n---\nInspect the screen.");
    return AgentRegistry.load(directory, runtime.knownTools())
        .get("viewer")
        .sampling(Sampling.NONE.withMaxTokens(128));
  }

  enum Unrecordable {
    CALL,
    RESULT
  }

  @Test
  void durable_write_failure_stops_before_capture_or_the_next_model_request() throws Exception {
    for (var failure : Unrecordable.values()) {
      var producer = new Producer();
      var backend = new Backend();
      var history =
          new History() {
            public void recordDurably(LoggedEntry entry) {
              if (failure == Unrecordable.CALL)
                throw new IllegalStateException("storage unavailable");
              super.recordDurably(entry);
            }

            public List<Content.StoredImage> recordToolResult(LoggedEntry entry) {
              throw new IllegalStateException("storage unavailable");
            }
          };
      try (var models = dispatcher(backend, PromptImages.NONE, model -> Optional.of(MEDIA))) {
        var runtime = new JobRuntime(models, List.of(producer));
        var definition = definition(runtime);
        var outcome =
            runtime.run(
                definition,
                "Inspect",
                Home.of("fixture"),
                Budget.of(6),
                () -> false,
                "job",
                JobWatch.UNWATCHED,
                history,
                TurnCap.from(definition),
                List.of());
        assertEquals(Outcome.Ending.UNAVAILABLE, outcome.ending());
        assertEquals(1, backend.calls.size());
        assertEquals(1, outcome.modelCalls());
        assertEquals(failure == Unrecordable.CALL ? 0 : 1, producer.captures.get());
      }
    }
  }

  @Test
  void actual_post_hook_redaction_retains_original_but_excludes_pixels_from_next_request()
      throws Exception {
    var producer = new Producer();
    var backend = new Backend();
    var history = new History();
    try (var models = dispatcher(backend, PromptImages.NONE, model -> Optional.of(MEDIA))) {
      var runtime = new JobRuntime(models, List.of(producer));
      runtime.useHooks(
          new io.aeyer.plowshare.server.hooks.Hooks() {
            public io.aeyer.plowshare.server.hooks.ToolPost toolPost(
                io.aeyer.plowshare.server.hooks.HookContext context,
                String name,
                String arguments,
                String result) {
              return new io.aeyer.plowshare.server.hooks.ToolPost("redacted", List.of());
            }
          });
      var definition = definition(runtime);
      var outcome =
          runtime.run(
              definition,
              "Inspect",
              Home.of("fixture"),
              Budget.of(6),
              () -> false,
              "job",
              JobWatch.UNWATCHED,
              history,
              TurnCap.from(definition),
              List.of());
      assertEquals(Outcome.Ending.ANSWERED, outcome.ending(), outcome.detail());
      assertEquals(2, backend.calls.size());
      assertFalse(backend.calls.getLast().stream().anyMatch(ChatMessage::carriesAnImage));
      var retained =
          history.rows.stream().filter(row -> row.observation() != null).findFirst().orElseThrow();
      assertEquals(body(ToolContent.Use.OBSERVATION), retained.observation().original());
      assertNull(retained.observation().effective());
      assertEquals("redacted", retained.content());
      assertEquals(1, producer.captures.get());
    }
  }
}
