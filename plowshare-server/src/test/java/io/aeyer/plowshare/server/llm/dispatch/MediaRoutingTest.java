package io.aeyer.plowshare.server.llm.dispatch;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.llm.MediaRequirements;
import io.aeyer.plowshare.server.llm.ModelMediaCapabilities;
import io.aeyer.plowshare.server.llm.ModelMediaProfiles;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.counting.PromptCount;
import io.aeyer.plowshare.server.security.ChatFiltering;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * Native format eligibility, independently of artifact admission, real models or database state.
 */
class MediaRoutingTest {
  private static final ModelMediaCapabilities PNG = capabilities("image/png");

  @Test
  void legacy_text_routing_never_consults_profiles_and_retains_all_dispatch_paths() {
    Transport first = transport("first", ModelMediaCapabilities.TEXT_ONLY);
    Transport second = transport("second", PNG);
    try (var dispatcher =
        dispatcher(
            List.of(pool(first, false), pool(second, true)),
            model -> {
              throw new AssertionError("legacy text read a media profile");
            },
            ChatFiltering.NONE)) {
      ChatRequest request = ChatRequest.of("fast", null, "hello");
      assertEquals("first", dispatcher.complete(request).servedBy().pool());
      assertEquals("first", dispatcher.stream(request, Deltas.DISCARDING).servedBy().pool());
      assertEquals("first", dispatcher.streamFold(request, Deltas.DISCARDING).servedBy().pool());
      assertEquals(
          "second",
          dispatcher.streamOn("second", request, Deltas.DISCARDING, () -> false).servedBy().pool());
      assertEquals("first", dispatcher.count(request).pool());
      assertEquals(
          List.of("first", "second"),
          dispatcher.poolsServing(request).stream().map(LlmPool::name).toList());
    }
  }

  @Test
  void incompatible_idle_pool_is_removed_before_load_comparison() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    Transport idle = transport("idle", ModelMediaCapabilities.TEXT_ONLY);
    Transport busy = new Transport(new FakeTransport("busy", 1, release), PNG);
    LlmPool idlePool = pool(idle, true);
    LlmPool busyPool = pool(busy, true);
    try (var executor = Executors.newSingleThreadExecutor();
        var dispatcher =
            dispatcher(
                List.of(idlePool, busyPool), model -> Optional.of(PNG), ChatFiltering.NONE)) {
      var occupying =
          executor.submit(
              () ->
                  dispatcher.streamOn(
                      "busy",
                      ChatRequest.of("model", null, "occupy"),
                      Deltas.DISCARDING,
                      () -> false));
      try {
        assertTrue(busy.delegate.entered.await(5, TimeUnit.SECONDS));
        assertTrue(busyPool.load(Lane.CHAT) > idlePool.load(Lane.CHAT));
        assertEquals("busy", dispatcher.count(imageRequest("fast", png())).pool());
        assertEquals(List.of(busyPool), dispatcher.poolsServing(imageRequest("system", png())));
      } finally {
        release.countDown();
      }
      occupying.get(5, TimeUnit.SECONDS);
      var result = dispatcher.complete(imageRequest("model", png()));
      assertEquals(new Completion.Served("busy", "model"), result.servedBy());
      assertEquals(0, idle.delegate.calls.get());
    }
  }

  @Test
  void explicit_requirements_with_no_profile_fail_before_counting_or_inference() {
    Transport transport = transport("one", PNG);
    try (var dispatcher =
        dispatcher(List.of(pool(transport, true)), ModelMediaProfiles.NONE, ChatFiltering.NONE)) {
      ChatRequest request =
          imageRequest("model", png()).withMediaRequirements(MediaRequirements.TEXT);
      assertAllPathsRefuse(dispatcher, request);
      assertThrows(UnsupportedMediaException.class, () -> dispatcher.count(request));
      assertEquals(0, transport.delegate.calls.get());
      assertEquals(0, transport.counts.get());
      assertThrows(
          UnknownSpecifierException.class,
          () ->
              dispatcher.complete(
                  ChatRequest.of("missing", null, "hello")
                      .withMediaRequirements(MediaRequirements.TEXT)));
    }
  }

  @Test
  void pinned_pool_refuses_without_retargeting_and_all_stream_paths_share_the_matcher() {
    Transport bad = transport("bad", ModelMediaCapabilities.TEXT_ONLY);
    Transport good = transport("good", PNG);
    try (var dispatcher =
        dispatcher(
            List.of(pool(bad, true), pool(good, true)),
            model -> Optional.of(PNG),
            ChatFiltering.NONE)) {
      ChatRequest request = imageRequest("model", png());
      assertThrows(
          UnsupportedMediaException.class,
          () -> dispatcher.streamOn("bad", request, Deltas.DISCARDING, () -> false));
      assertEquals(0, good.delegate.calls.get());
      assertEquals(
          "good",
          dispatcher.streamOn("good", request, Deltas.DISCARDING, () -> false).servedBy().pool());
      assertEquals("good", dispatcher.stream(request, Deltas.DISCARDING).servedBy().pool());
      assertEquals("good", dispatcher.streamFold(request, Deltas.DISCARDING).servedBy().pool());
      assertEquals(0, bad.delegate.calls.get());
    }
  }

  @Test
  void full_history_and_actual_signature_cannot_be_omitted_by_the_caller() {
    Transport transport = transport("one", PNG);
    try (var dispatcher =
        dispatcher(List.of(pool(transport, true)), model -> Optional.of(PNG), ChatFiltering.NONE)) {
      ChatRequest history =
          ChatRequest.of(
                  "model",
                  List.of(
                      ChatMessage.user("older", List.of(jpeg())),
                      ChatMessage.assistant("noted", List.of()),
                      ChatMessage.user("latest", List.of(png()))))
              .withMediaRequirements(MediaRequirements.TEXT);
      assertThrows(UnsupportedMediaException.class, () -> dispatcher.complete(history));
      assertThrows(UnsupportedMediaException.class, () -> dispatcher.count(history));
      String jpegBytes = jpeg().dataUri().substring("data:image/jpeg;base64,".length());
      var forged = new Content.Image("forged", "data:image/png;base64," + jpegBytes);
      var error =
          assertThrows(
              IllegalArgumentException.class,
              () -> dispatcher.complete(imageRequest("model", forged)));
      assertFalse(error.getMessage().contains(jpegBytes));
      assertEquals(0, transport.delegate.calls.get());
    }
  }

  @Test
  void model_and_endpoint_must_both_permit_the_actual_image_format() {
    for (boolean vision : List.of(false, true)) {
      Transport transport = transport("one", vision ? ModelMediaCapabilities.TEXT_ONLY : PNG);
      try (var dispatcher =
          dispatcher(
              List.of(pool(transport, vision)), model -> Optional.of(PNG), ChatFiltering.NONE)) {
        assertThrows(
            UnsupportedMediaException.class,
            () -> dispatcher.complete(imageRequest("model", png())));
        assertThrows(UnsupportedMediaException.class, () -> dispatcher.requireSees("model"));
        assertEquals(0, transport.delegate.calls.get());
      }
    }
    Transport transport = transport("one", PNG);
    try (var dispatcher =
        dispatcher(
            List.of(pool(transport, true)),
            model -> Optional.of(capabilities("image/jpeg")),
            ChatFiltering.NONE)) {
      assertThrows(
          UnsupportedMediaException.class, () -> dispatcher.complete(imageRequest("model", png())));
    }
  }

  @Test
  void profiled_vision_boot_guard_requires_one_eligible_route_and_legacy_guard_is_unchanged() {
    try (var dispatcher =
        dispatcher(
            List.of(pool(transport("blind", PNG), false), pool(transport("good", PNG), true)),
            model -> Optional.of(PNG),
            ChatFiltering.NONE)) {
      assertDoesNotThrow(() -> dispatcher.requireSees("fast"));
      assertEquals("good", dispatcher.complete(imageRequest("fast", png())).servedBy().pool());
    }
    try (var dispatcher =
        dispatcher(
            List.of(pool(transport("blind", PNG), false), pool(transport("good", PNG), true)),
            ModelMediaProfiles.NONE,
            ChatFiltering.NONE)) {
      assertThrows(LlmException.class, () -> dispatcher.requireSees("fast"));
    }
  }

  @Test
  void image_only_models_and_missing_native_output_decoders_cannot_satisfy_explicit_text_calls() {
    var imageOnly =
        new ModelMediaCapabilities(
            Set.of("text/plain"),
            Map.of("image", new ModelMediaCapabilities.OutputMode(Set.of("image/png"))));
    Transport transport = transport("one", PNG);
    try (var dispatcher =
        dispatcher(
            List.of(pool(transport, true)), model -> Optional.of(imageOnly), ChatFiltering.NONE)) {
      ChatRequest text = ChatRequest.of("model", null, "hello");
      assertEquals("one", dispatcher.complete(text).servedBy().pool());
      assertThrows(
          UnsupportedMediaException.class,
          () -> dispatcher.complete(text.withMediaRequirements(MediaRequirements.TEXT)));
      var imageOutput =
          new MediaRequirements(Set.of("text/plain"), Set.of("image/png"), Set.of("image/png"));
      assertThrows(
          UnsupportedMediaException.class,
          () -> dispatcher.complete(text.withMediaRequirements(imageOutput)));
      assertEquals(1, transport.delegate.calls.get());
    }
  }

  @Test
  void format_filtering_runs_after_input_filtering_and_output_contract_cannot_be_dropped() {
    Transport transport = transport("one", PNG);
    ChatFiltering addJpeg =
        filter(
            request ->
                request.withMessages(List.of(ChatMessage.user("filtered", List.of(jpeg())))));
    try (var dispatcher =
        dispatcher(List.of(pool(transport, true)), model -> Optional.of(PNG), addJpeg)) {
      assertAllPathsRefuse(dispatcher, imageRequest("model", png()));
      assertEquals(0, transport.delegate.calls.get());
    }
    ChatFiltering dropContract =
        filter(request -> ChatRequest.of(request.specifier(), request.messages()));
    try (var dispatcher =
        dispatcher(
            List.of(pool(transport("one", PNG), true)), model -> Optional.of(PNG), dropContract)) {
      assertThrows(
          LlmException.class,
          () ->
              dispatcher.complete(
                  ChatRequest.of("model", null, "text")
                      .withMediaRequirements(MediaRequirements.TEXT)));
    }
  }

  @Test
  void request_copies_preserve_requirements_and_output_modes_are_not_combined() {
    ChatRequest request =
        ChatRequest.of("model", null, "text").withMediaRequirements(MediaRequirements.TEXT);
    assertEquals(
        MediaRequirements.TEXT,
        request
            .withBudget(Duration.ofSeconds(2))
            .withSampling(Sampling.NONE)
            .withTools(List.of())
            .withToolChoice(ToolChoice.AUTO)
            .withAttribution(UsageAttribution.LEGACY)
            .withAuthorization(CallAuthorization.DIRECT)
            .withMessages(request.messages())
            .mediaRequirements());
    var mixed =
        new MediaRequirements(
            Set.of("text/plain"),
            Set.of("text/plain", "image/png"),
            Set.of("text/plain", "image/png"));
    var separate =
        new ModelMediaCapabilities(
            Set.of("text/plain"),
            Map.of(
                "text", new ModelMediaCapabilities.OutputMode(Set.of("text/plain")),
                "image", new ModelMediaCapabilities.OutputMode(Set.of("image/png"))));
    var combined =
        new ModelMediaCapabilities(
            Set.of("text/plain"),
            Map.of(
                "mixed", new ModelMediaCapabilities.OutputMode(Set.of("text/plain", "image/png"))));
    assertFalse(mixed.matches(separate, combined));
    assertTrue(mixed.matches(combined, combined));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MediaRequirements(Set.of("text/plain"), Set.of("image/png"), Set.of("text/plain")));
  }

  @Test
  void audio_and_video_use_the_same_matcher_without_advertising_native_transports() {
    for (String type : List.of("audio/wav", "video/mp4")) {
      var requirement =
          new MediaRequirements(Set.of(type), Set.of("text/plain"), Set.of("text/plain"));
      var declaration = capabilities(type);
      assertTrue(requirement.matches(declaration, declaration));
      assertFalse(requirement.matches(declaration, PNG));
    }
  }

  @Test
  void legacy_images_still_route_without_profiles_and_shared_model_lookup_is_reused() {
    try (var dispatcher =
        dispatcher(
            List.of(pool(transport("one", ModelMediaCapabilities.TEXT_ONLY), true)),
            ModelMediaProfiles.NONE,
            ChatFiltering.NONE)) {
      assertEquals("one", dispatcher.complete(imageRequest("model", png())).servedBy().pool());
    }
    AtomicInteger lookups = new AtomicInteger();
    try (var dispatcher =
        dispatcher(
            List.of(pool(transport("one", PNG), true), pool(transport("two", PNG), true)),
            model -> {
              lookups.incrementAndGet();
              return Optional.of(PNG);
            },
            ChatFiltering.NONE)) {
      assertEquals(2, dispatcher.poolsServing(imageRequest("model", png())).size());
      assertEquals(1, lookups.get());
    }
  }

  private static void assertAllPathsRefuse(LlmDispatcher dispatcher, ChatRequest request) {
    assertThrows(UnsupportedMediaException.class, () -> dispatcher.complete(request));
    assertThrows(
        UnsupportedMediaException.class, () -> dispatcher.stream(request, Deltas.DISCARDING));
    assertThrows(
        UnsupportedMediaException.class, () -> dispatcher.streamFold(request, Deltas.DISCARDING));
    assertThrows(
        UnsupportedMediaException.class,
        () -> dispatcher.streamOn("one", request, Deltas.DISCARDING, () -> false));
    // Counting previews the request before filtering; use the effective prompt for that explicit
    // read.
  }

  private static ChatFiltering filter(java.util.function.UnaryOperator<ChatRequest> input) {
    return new ChatFiltering() {
      public ChatRequest input(ChatRequest request, BooleanSupplier abandoned) {
        return input.apply(request);
      }

      public Completion output(Completion completion) {
        return completion;
      }

      public boolean bufferOutput() {
        return false;
      }
    };
  }

  private static ModelMediaCapabilities capabilities(String type) {
    return new ModelMediaCapabilities(
        Set.of("text/plain", type),
        Map.of("text", new ModelMediaCapabilities.OutputMode(Set.of("text/plain"))));
  }

  private static Content.Image png() {
    return image("image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
  }

  private static Content.Image jpeg() {
    return image("image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff});
  }

  private static Content.Image image(String type, byte[] bytes) {
    return new Content.Image(
        "fixture", "data:" + type + ";base64," + Base64.getEncoder().encodeToString(bytes));
  }

  private static ChatRequest imageRequest(String specifier, Content.Image image) {
    return ChatRequest.of(specifier, List.of(ChatMessage.user("inspect", List.of(image))));
  }

  private static Transport transport(String name, ModelMediaCapabilities capabilities) {
    return new Transport(FakeTransport.free(name), capabilities);
  }

  private static LlmPool pool(Transport transport, boolean vision) {
    return new LlmPool(
        transport.poolName(),
        List.of("model"),
        Map.of("fast", "model"),
        1,
        1,
        Duration.ofSeconds(5),
        transport,
        vision ? Set.of("model") : Set.of());
  }

  private static LlmDispatcher dispatcher(
      List<LlmPool> pools, ModelMediaProfiles profiles, ChatFiltering filtering) {
    return new LlmDispatcher(
        pools,
        new NoOpTokenLedger(),
        type -> "fast",
        InferenceAccounting.NONE,
        null,
        null,
        filtering,
        InferenceBoundary.FIXTURE,
        profiles);
  }

  private static final class Transport implements LlmTransport {
    private final FakeTransport delegate;
    private final ModelMediaCapabilities capabilities;
    private final AtomicInteger counts = new AtomicInteger();

    Transport(FakeTransport delegate, ModelMediaCapabilities capabilities) {
      this.delegate = delegate;
      this.capabilities = capabilities;
    }

    public String poolName() {
      return delegate.poolName();
    }

    public ModelMediaCapabilities mediaCapabilities(String model) {
      return capabilities;
    }

    public PromptCount countChat(String model, ChatRequest request) {
      counts.incrementAndGet();
      return PromptCount.unknown(poolName(), model, "fixture");
    }

    public Completion complete(
        String model, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      return delegate.complete(model, messages, sampling, tools);
    }

    public Completion stream(
        String model,
        List<ChatMessage> messages,
        Sampling sampling,
        List<ToolSchema> tools,
        Deltas sink,
        BooleanSupplier abandoned) {
      return delegate.stream(model, messages, sampling, tools, sink, abandoned);
    }

    public Embeddings embed(String model, List<String> input) {
      return delegate.embed(model, input);
    }

    public void close() {
      delegate.close();
    }
  }
}
