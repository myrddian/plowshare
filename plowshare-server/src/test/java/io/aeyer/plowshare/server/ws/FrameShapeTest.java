package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/**
 * The shape itself, tested — the things a breadth task can get wrong that no parity test of its own
 * would notice.
 *
 * <p>Every assertion here is about the surface rather than about a frame type, which is why they
 * are not in either pilot's file: a pilot proves its own answer, and these prove that the fiftieth
 * handler written by an agent who read only its own controller still lands in a working table,
 * decodes tolerantly, and is actually reachable.
 */
class FrameShapeTest {

  /**
   * This package's own sources.
   *
   * <p>JUnit runs with the working directory set to the module directory — measured, and the same
   * fact {@code InvariantsTest} and {@code MigrationsAreImmutableTest} already rely on.
   */
  private static final Path WS = Path.of("src/main/java/io/aeyer/plowshare/server/ws");

  private static final Path API = Path.of("src/main/java/io/aeyer/plowshare/server/api");

  /**
   * How a controller spells a success, and which status it means.
   *
   * <p>Hand-written rather than derived, and the limit is worth stating: a controller reaching for
   * a status by some spelling not listed here — a {@code
   * ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)}, say — is not caught. What this does catch
   * is the failure that actually happened, which is the one worth a test.
   */
  private static final Map<String, Integer> SUCCESS_SPELLINGS =
      Map.of(
          "ResponseEntity.ok", 200,
          "ResponseEntity.accepted(", 202,
          "ResponseEntity.noContent(", 204,
          "HttpStatus.CREATED", 201);

  /**
   * The two channel handlers, which are not frame handlers and do own mappers: {@code
   * EventChannelHandler} reads a frame's top level a second time for its correlation id, and {@code
   * FileChannelHandler} predates the envelope entirely.
   */
  private static final List<String> NOT_FRAME_HANDLERS =
      List.of("EventChannelHandler.java", "FileChannelHandler.java");

  // -- an area nobody can reach is worse than one nobody wrote --------------

  /**
   * Every {@link FrameArea} carries a stereotype annotation, so Spring actually collects it.
   *
   * <p><b>The failure this prevents is silent.</b> An area with no {@code @Component} compiles, its
   * handlers are correct, its own parity test — which builds the area directly — passes, and the
   * types it claims simply do not route in production: a client gets the well-formed {@code
   * NOT_FOUND} an unregistered type gets, which is indistinguishable from a frame type nobody has
   * written yet. {@code FrameAreas} scans by type rather than by annotation precisely so that this
   * assertion has something to fail on.
   */
  @Test
  void every_frame_area_is_annotated_as_a_bean() {
    for (Class<?> area : FrameAreas.declared()) {
      assertTrue(
          area.isAnnotationPresent(Component.class)
              || area.isAnnotationPresent(Configuration.class)
              || area.isAnnotationPresent(Service.class),
          area.getName()
              + " implements FrameArea but carries no stereotype"
              + " annotation, so Spring will not collect it into"
              + " FrameRoutingConfig and every type it claims answers NOT_FOUND");
    }
  }

  /** The production table builds, which is the merge itself working. */
  @Test
  void the_routing_table_is_the_union_of_every_area() {
    FrameRouter routing = FrameAreas.router();

    List<String> claimed = new ArrayList<>();
    for (FrameArea area : FrameAreas.mocked()) {
      claimed.addAll(area.frames().keySet());
    }

    assertEquals(
        claimed.size(),
        routing.types().size(),
        "every type an area claims is in the table exactly once: " + claimed);
    assertTrue(routing.types().containsAll(claimed));
  }

  /**
   * A container really does collect the areas into the router bean.
   *
   * <p><b>The half of the wiring that was argued rather than measured.</b> That
   * {@code @SpringBootApplication}'s own scan reaches this package is still only measured by the
   * end-to-end suites, which need a database — but the step the breadth plan actually changes is
   * the new one: {@link FrameRoutingConfig} asks for {@code List<FrameArea>}, and a container with
   * no candidates, or with an area it cannot construct, fails here rather than in a deployment. The
   * areas are registered from {@link FrameAreas#declared()}, so a task that adds one is covered
   * without touching this test.
   */
  @Test
  void a_container_builds_the_router_from_every_area() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      FrameAreas.servicesNeeded().forEach(service -> mockBean(context, service));
      mockBean(context, io.aeyer.plowshare.server.access.ProjectAuthorization.class);
      FrameAreas.declared().forEach(context::register);
      context.register(FrameRoutingConfig.class);
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new org.springframework.core.env.MapPropertySource(
                  "fixture",
                  Map.of(
                      "plowshare.projects.workspace-directory",
                      java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))
                          .toAbsolutePath()
                          .toString())));
      context.refresh();

      FrameRouter router = context.getBean(FrameRouter.class);

      assertTrue(
          router.types().contains(FrameTypes.PROJECT_DEFINE),
          "the container's own table claims what the areas name: " + router.types());
      assertTrue(
          router.types().contains(FrameTypes.CONVERSATION_TURNS),
          "and the other pilot's too: " + router.types());
    }
  }

  /** One mocked service in the context, under its own type. */
  private static <T> void mockBean(AnnotationConfigApplicationContext context, Class<T> type) {
    context.registerBean(type, () -> mock(type));
  }

  /**
   * Two areas claiming one frame type fails the boot, naming the type.
   *
   * <p>The failure two parallel breadth tasks are most likely to produce — one endpoint given a
   * frame by two different tasks, each green on its own branch — and the one a merge would
   * otherwise resolve into a table where which handler answers depends on bean ordering.
   */
  @Test
  void two_areas_claiming_one_type_fails_the_boot() {
    FrameHandler mine = (payload, asking) -> Outcome.ok();
    FrameHandler yours = (payload, asking) -> Outcome.ok();
    FrameArea one = () -> Map.of("document.search", mine);
    FrameArea other = () -> Map.of("document.search", yours);

    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> new FrameRoutingConfig().frameRouter(List.of(one, other)));

    assertTrue(
        refused.getMessage().contains("document.search"),
        "and names the type both claimed: " + refused.getMessage());
  }

  // -- 3.2's tolerance is the surface's, not each author's -------------------

  /**
   * No frame handler decodes its own payload.
   *
   * <p><b>This is the generic half of the §3.2 guard.</b> {@code
   * FrameParity.assertUnknownFieldsAreIgnored} proves tolerance for a type whose author called it;
   * this proves that an author who called nothing still cannot be strict, because the only decoder
   * in reach is {@link Payloads} and {@link Payloads} disables {@code FAIL_ON_UNKNOWN_PROPERTIES}.
   * A handler that built a mapper of its own — which is exactly what the write pilot did before
   * {@link Payloads} existed — would fail here rather than in a client's hands.
   */
  @Test
  void no_frame_handler_decodes_its_own_payload() throws IOException {
    List<String> offenders = new ArrayList<>();
    List<String> scanned = new ArrayList<>();
    try (Stream<Path> files = Files.list(WS)) {
      for (Path file : files.toList()) {
        String name = file.getFileName().toString();
        if (!name.endsWith("Handler.java") || NOT_FRAME_HANDLERS.contains(name)) {
          continue;
        }
        scanned.add(name);
        String source = Files.readString(file, StandardCharsets.UTF_8);
        if (source.contains("ObjectMapper")
            || source.contains("convertValue")
            || source.contains("FAIL_ON_UNKNOWN_PROPERTIES")) {
          offenders.add(name);
        }
      }
    }

    assertTrue(
        scanned.contains("ProjectDefineHandler.java"),
        "the scan can see the files it asserts over -- it found " + scanned);
    assertEquals(
        List.of(),
        offenders,
        "a frame handler that decodes for itself is one omitted .disable(...) away"
            + " from turning a newer client's extra field into a 400 for its type"
            + " alone. Read the payload through ws.Payloads, which is where spec"
            + " 3.2's tolerance lives.");
  }

  /**
   * Every status the HTTP surface answers on success has a {@link Code} that can say it.
   *
   * <p><b>This exists because it did not hold.</b> {@code Code} was derived from {@code
   * ApiExceptionHandler}'s table, which enumerates failures -- so 202, 204 and 201 had no constant
   * at all, while six endpoints across four controllers answer 202 and two answer 201. A breadth
   * task reaching one of those would have had nothing correct to return, and the reachable mistake
   * is {@link Code#OK}: a frame telling a client its job is done when the answer it was handed is a
   * handle to poll.
   *
   * <p>Failure statuses are not checked here. They come from {@code Faults}, which is one mapping
   * over one exception set and has its own tests; this is about the half of the vocabulary no
   * exception ever produces.
   */
  @Test
  void every_success_a_controller_can_answer_is_one_a_frame_can_say() throws IOException {
    List<String> scanned = new ArrayList<>();
    List<String> unsayable = new ArrayList<>();
    List<Integer> known = Stream.of(Code.values()).map(Code::httpStatus).toList();
    try (Stream<Path> files = Files.list(API)) {
      for (Path file : files.toList()) {
        String name = file.getFileName().toString();
        if (!name.endsWith("Controller.java")) {
          continue;
        }
        scanned.add(name);
        String source = Files.readString(file, StandardCharsets.UTF_8);
        SUCCESS_SPELLINGS.forEach(
            (spelling, status) -> {
              if (source.contains(spelling) && !known.contains(status)) {
                unsayable.add(name + " answers " + status);
              }
            });
      }
    }

    assertTrue(
        scanned.contains("AgentController.java"),
        "the scan can see the files it asserts over -- it found " + scanned);
    assertEquals(
        List.of(),
        unsayable,
        "a controller answers a success this frame surface has no Code for, so the"
            + " handler beside it has nothing true to return. Add the constant to"
            + " protocol.frames.Code rather than reaching for OK -- OK tells a"
            + " client the work is finished.");
  }
}
