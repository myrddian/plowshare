package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.applications.ContainedApplications;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.lang.reflect.Modifier;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * The contained-Application frames at the boundary: registered, strict about their payload, and
 * finished converting before the service that writes anything is reached.
 *
 * <p>The service is a mock, so "no durable write" is read off its interactions: a refused payload
 * must leave {@code ContainedApplications} untouched. That is the whole of the ordering contract —
 * these frames hold no repository, so the only way they could write early is by calling the service
 * before they have finished validating.
 */
class ContainedApplicationFramesTest {
  static final String CHILD = "11111111-1111-1111-1111-111111111111";
  static final String REQUEST = "22222222-2222-2222-2222-222222222222";

  final ContainedApplications children = mock(ContainedApplications.class);
  final Map<String, FrameHandler> frames = new ContainedApplicationFrames(children).frames();
  final Asking asking = new Asking("session", "owner");

  /** Every frame the capability declares, found by name so a sixth constant is caught too. */
  static List<String> declared() throws IllegalAccessException {
    List<String> types = new ArrayList<>();
    for (var field : FrameTypes.class.getDeclaredFields())
      if (Modifier.isStatic(field.getModifiers())
          && field.getType() == String.class
          && ((String) field.get(null)).startsWith("application.child."))
        types.add((String) field.get(null));
    return types;
  }

  static Map<String, Object> valid(String type) {
    return switch (type) {
      case FrameTypes.APPLICATION_CHILD_CREATE -> {
        // The field is part of the operation's shape and so is always sent; the only value a
        // container may send is the one that names nothing, because the server allocates the
        // child's source area itself.
        var create =
            new HashMap<String, Object>(
                Map.of(
                    "conversation",
                    "c",
                    "requestId",
                    REQUEST,
                    "ceiling",
                    3,
                    "writableAreas",
                    List.of(),
                    "files",
                    List.of(Map.of("path", "plowshare.json", "text", "{}"))));
        create.put("destination", null);
        yield create;
      }
      case FrameTypes.APPLICATION_CHILD_CEILING ->
          new HashMap<>(Map.of("conversation", "c", "child", CHILD, "calls", 1));
      case FrameTypes.APPLICATION_CHILD_DESTROY,
              FrameTypes.APPLICATION_CHILD_TRAJECTORY,
              FrameTypes.APPLICATION_CHILD_RESOURCES,
              FrameTypes.APPLICATION_CHILD_CANCEL ->
          new HashMap<>(Map.of("conversation", "c", "child", CHILD));
      case FrameTypes.APPLICATION_CHILD_LIST -> new HashMap<>(Map.of("conversation", "c"));
      case FrameTypes.APPLICATION_CHILD_RUN -> {
        // maxTurns is part of the operation's shape and so is always sent; null is the run that
        // decides nothing and leaves the agent's own cap, exactly as agent.run reads an absent one.
        var run =
            new HashMap<String, Object>(
                Map.of("conversation", "c", "child", CHILD, "agent", "worker", "task", "Do it"));
        run.put("maxTurns", null);
        yield run;
      }
      default -> throw new AssertionError("no fixture for " + type);
    };
  }

  @Test
  void everyContainedFrameIsRegistered() throws Exception {
    var declared = declared();
    assertEquals(8, declared.size(), "a new application.child.* constant needs a contract audit");
    assertEquals(new TreeSet<>(declared), new TreeSet<>(frames.keySet()));
    // And exactly once across the whole table, as the router would assemble it.
    var all =
        FrameAreas.mocked().stream()
            .flatMap(area -> area.frames().keySet().stream())
            .filter(type -> type.startsWith("application.child."))
            .toList();
    assertEquals(new TreeSet<>(declared), new TreeSet<>(all));
    assertEquals(declared.size(), all.size());
  }

  @Test
  void aPayloadWithUnknownFieldsIsRefused() throws Exception {
    for (String type : declared()) {
      var payload = valid(type);
      payload.put("scope", CHILD);
      assertThrows(CallerFault.class, () -> frames.get(type).handle(payload, asking), type);
    }
    verifyNoInteractions(children);
  }

  @Test
  void aPayloadMissingRequiredFieldsIsRefused() throws Exception {
    for (String type : declared()) {
      for (String field : valid(type).keySet()) {
        var payload = valid(type);
        payload.remove(field);
        assertThrows(
            CallerFault.class,
            () -> frames.get(type).handle(payload, asking),
            type + " without " + field);
      }
    }
    verifyNoInteractions(children);
  }

  @Test
  void conversionCompletesBeforeAnyDurableWrite() {
    var create = frames.get(FrameTypes.APPLICATION_CHILD_CREATE);
    List<Map<String, Object>> malformed = new ArrayList<>();
    for (var change :
        List.<Map.Entry<String, Object>>of(
            Map.entry("ceiling", -1),
            Map.entry("ceiling", "3"),
            Map.entry("ceiling", 3.5),
            Map.entry("requestId", "not-a-uuid"),
            Map.entry("writableAreas", "all"),
            Map.entry("destination", Map.of("store", "applications")),
            Map.entry("files", List.of()),
            Map.entry("files", List.of(Map.of("path", "../escape", "text", "x"))),
            Map.entry("files", List.of(Map.of("path", "a", "text", "x", "mode", "0777"))))) {
      var payload = valid(FrameTypes.APPLICATION_CHILD_CREATE);
      payload.put(change.getKey(), change.getValue());
      malformed.add(payload);
    }
    var nullCeiling = valid(FrameTypes.APPLICATION_CHILD_CREATE);
    nullCeiling.put("ceiling", null);
    malformed.add(nullCeiling);
    for (var payload : malformed)
      assertThrows(CallerFault.class, () -> create.handle(payload, asking), payload.toString());
    for (String type :
        List.of(
            FrameTypes.APPLICATION_CHILD_CEILING,
            FrameTypes.APPLICATION_CHILD_DESTROY,
            FrameTypes.APPLICATION_CHILD_TRAJECTORY,
            FrameTypes.APPLICATION_CHILD_CANCEL)) {
      var payload = valid(type);
      payload.put("child", "not-a-uuid");
      assertThrows(CallerFault.class, () -> frames.get(type).handle(payload, asking), type);
    }
    var negative = valid(FrameTypes.APPLICATION_CHILD_CEILING);
    negative.put("calls", -1);
    assertThrows(
        CallerFault.class,
        () -> frames.get(FrameTypes.APPLICATION_CHILD_CEILING).handle(negative, asking));
    verifyNoInteractions(children);
  }

  /**
   * A caller-supplied destination is refused, not accepted and discarded. The server allocates a
   * child's source area under its own containment root, so there is no destination a caller could
   * name that would mean anything — and a boundary whose contract is that a raw map never travels
   * past it must not validate a field into a value nothing reads.
   */
  @Test
  void aCreateNamingItsOwnDestinationIsRefused() {
    var create = frames.get(FrameTypes.APPLICATION_CHILD_CREATE);
    var named = valid(FrameTypes.APPLICATION_CHILD_CREATE);
    named.put("destination", Map.of("store", "applications", "path", "x"));
    assertThrows(CallerFault.class, () -> create.handle(named, asking));
    // An explicit null is the field being declined, which stays acceptable: the operation's shape
    // keeps the nullable field for request-shape compatibility with application.deploy, so every
    // SDK sends the key.
    var declined = valid(FrameTypes.APPLICATION_CHILD_CREATE);
    when(children.create(eq("owner"), any()))
        .thenReturn(
            new ContainedApplications.Child(
                UUID.fromString(CHILD),
                UUID.fromString(REQUEST),
                "contained:" + CHILD,
                UUID.fromString(REQUEST)));
    assertDoesNotThrow(() -> create.handle(declined, asking));
    verify(children, times(1)).create(eq("owner"), any(ContainedApplications.Create.class));
  }

  @Test
  void aWellFormedCreateReachesTheServiceOnceWithTheTypedRequest() {
    when(children.create(eq("owner"), any()))
        .thenReturn(
            new ContainedApplications.Child(
                UUID.fromString(CHILD),
                UUID.fromString(REQUEST),
                "contained:" + CHILD,
                UUID.fromString(REQUEST)));
    frames
        .get(FrameTypes.APPLICATION_CHILD_CREATE)
        .handle(valid(FrameTypes.APPLICATION_CHILD_CREATE), asking);
    verify(children, times(1)).create(eq("owner"), any(ContainedApplications.Create.class));
    verifyNoMoreInteractions(children);
  }

  /**
   * A run is minutes, so it answers {@link io.aeyer.plowshare.protocol.frames.Code#ACCEPTED} with a
   * handle, for {@code AgentRunHandler}'s own reason: an {@code OK} would tell a client its work
   * had finished while it holds a job that has not started.
   */
  @Test
  void aWellFormedRunIsAcceptedAndCarriesTheJobHandle() {
    when(children.run(eq("owner"), any()))
        .thenReturn(
            new ContainedApplications.Running(
                UUID.fromString(CHILD), "job-1", "child-conv", "worker"));

    var outcome =
        frames
            .get(FrameTypes.APPLICATION_CHILD_RUN)
            .handle(valid(FrameTypes.APPLICATION_CHILD_RUN), asking);

    assertEquals(io.aeyer.plowshare.protocol.frames.Code.ACCEPTED, outcome.code());
    assertEquals(
        Map.of("child", CHILD, "job", "job-1", "conversation", "child-conv", "agent", "worker"),
        outcome.payload());
    var captured = org.mockito.ArgumentCaptor.forClass(ContainedApplications.Run.class);
    verify(children, times(1)).run(eq("owner"), captured.capture());
    assertEquals("c", captured.getValue().conversation());
    assertEquals(UUID.fromString(CHILD), captured.getValue().child());
    assertEquals("worker", captured.getValue().agent());
    assertEquals("Do it", captured.getValue().task());
    assertNull(captured.getValue().maxTurns());
    verifyNoMoreInteractions(children);
  }

  /**
   * The answer must be about the child that was asked about. A {@code Running} naming another one
   * is a server-side inconsistency, not a caller error, so it is refused rather than published: a
   * container that acted on it would be acting on a run inside an Application it never named.
   */
  @Test
  void aReplyAboutADifferentChildIsRefused() {
    when(children.run(eq("owner"), any()))
        .thenReturn(
            new ContainedApplications.Running(
                UUID.fromString(REQUEST), "job-1", "child-conv", "worker"));

    assertThrows(
        IllegalStateException.class,
        () ->
            frames
                .get(FrameTypes.APPLICATION_CHILD_RUN)
                .handle(valid(FrameTypes.APPLICATION_CHILD_RUN), asking));
  }

  @Test
  void aMalformedRunIsRefusedBeforeTheService() {
    var run = frames.get(FrameTypes.APPLICATION_CHILD_RUN);
    for (var change :
        List.<Map.Entry<String, Object>>of(
            Map.entry("child", "not-a-uuid"),
            Map.entry("agent", ""),
            Map.entry("agent", 7),
            Map.entry("task", 7),
            Map.entry("maxTurns", 0),
            Map.entry("maxTurns", -1),
            Map.entry("maxTurns", "3"),
            Map.entry("conversation", ""))) {
      var payload = valid(FrameTypes.APPLICATION_CHILD_RUN);
      payload.put(change.getKey(), change.getValue());
      assertThrows(CallerFault.class, () -> run.handle(payload, asking), payload.toString());
    }
    var nullTask = valid(FrameTypes.APPLICATION_CHILD_RUN);
    nullTask.put("task", null);
    assertThrows(CallerFault.class, () -> run.handle(nullTask, asking));
    verifyNoInteractions(children);
  }

  /**
   * A cancellation answers {@code OK}, unlike a run: there is no handle to poll, and what has
   * completed when it answers is the asking rather than the work. The answer names the child the
   * caller asked about, read from the payload, because the operation returns nothing there could be
   * a server-side answer to correlate against.
   */
  @Test
  void aWellFormedCancelIsOkAndNamesTheChildAsked() {
    var outcome =
        frames
            .get(FrameTypes.APPLICATION_CHILD_CANCEL)
            .handle(valid(FrameTypes.APPLICATION_CHILD_CANCEL), asking);

    assertEquals(io.aeyer.plowshare.protocol.frames.Code.OK, outcome.code());
    assertEquals(Map.of("child", CHILD), outcome.payload());
    verify(children, times(1)).cancel("owner", "c", UUID.fromString(CHILD));
    verifyNoMoreInteractions(children);
  }

  /**
   * Cancelling a child that holds no live run is a success and not a refusal, which is the
   * service's documented contract rather than this boundary's leniency: the mock above returns
   * normally for every call, so a frame that invented a refusal of its own would fail here.
   */
  @Test
  void aCancelOfAChildWithNothingRunningIsStillOk() {
    doNothing().when(children).cancel(any(), any(), any());

    var outcome =
        frames
            .get(FrameTypes.APPLICATION_CHILD_CANCEL)
            .handle(valid(FrameTypes.APPLICATION_CHILD_CANCEL), asking);

    assertEquals(io.aeyer.plowshare.protocol.frames.Code.OK, outcome.code());
  }

  @Test
  void aSocketWithoutAnAccountIsRefusedBeforeTheService() throws Exception {
    var anonymous = new Asking("session", null);
    for (String type : declared())
      assertThrows(CallerFault.class, () -> frames.get(type).handle(valid(type), anonymous), type);
    verifyNoInteractions(children);
  }
}
