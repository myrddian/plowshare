package io.aeyer.plowshare.a2a.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.a2a.Ingress;
import io.aeyer.plowshare.a2a.IngressRefused;
import io.aeyer.plowshare.a2a.IngressResponseTooLarge;
import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Protocol/journal tests; the owning server's independent tests establish actual authorization. */
class RelayIngressBridgeTest {
  @TempDir Path directory;
  static final Instant TIME = Instant.parse("2026-11-13T00:00:00Z");
  static final String GENERATION = "11111111-1111-1111-1111-111111111111";
  static final String TOPIC_GENERATION = "22222222-2222-2222-2222-222222222222";
  static final String GROUP_GENERATION = "33333333-3333-3333-3333-333333333333";
  static final RelayIngressBridge.Binding BINDING =
      new RelayIngressBridge.Binding(
          "project",
          "reviewer",
          "a2a.commands",
          "a2a.tasks",
          "a2a-views",
          GENERATION,
          "messaging:ingress",
          200);

  private RelayIngressBridge bridge(IngressJournal journal, Messages gateway) {
    return new RelayIngressBridge(
        BINDING,
        gateway,
        journal,
        () -> new Ingress.Catalog("reviewer", "Review", true, List.of()),
        () -> TIME);
  }

  static Ingress.Receive request(UUID id, String client, UUID context, String body) {
    return new Ingress.Receive(
        "project",
        client,
        "reviewer",
        id,
        context,
        body,
        null,
        new Ingress.Source(
            id.toString(),
            "ROLE_USER",
            null,
            context,
            List.of(new Ingress.TextPart(body)),
            null,
            null,
            null));
  }

  @Test
  void lost_receive_ack_recovers_by_original_request_without_another_mutation() throws Exception {
    var gateway = new Messages();
    gateway.loseReceiveAck = true;
    var input = request(UUID.randomUUID(), "alice", null, "Question");
    try (var journal = new FileIngressJournal(directory, "a".repeat(64), "b".repeat(64))) {
      assertThrows(IOException.class, () -> bridge(journal, gateway).receive(input));
      assertEquals(1, gateway.receives);
    }
    try (var journal = new FileIngressJournal(directory, "a".repeat(64), "b".repeat(64))) {
      var task = bridge(journal, gateway).receive(input);
      assertEquals(input.requestId(), task.id());
      assertEquals(input.source(), task.source());
      assertEquals(1, gateway.receives);
      assertEquals(1, gateway.inspects);
      assertTrue(gateway.methods.stream().allMatch(s -> s.startsWith("plowshare-message-")));
    }
  }

  @Test
  void expired_initial_response_and_changed_duplicate_do_not_repeat_receive() throws Exception {
    var gateway = new Messages();
    gateway.dropReceiveReply = true;
    var input = request(UUID.randomUUID(), "alice", null, "Question");
    try (var journal = new FileIngressJournal(directory, "a".repeat(64), "b".repeat(64))) {
      var bridge = bridge(journal, gateway);
      assertThrows(IOException.class, () -> bridge.receive(input));
      var current = bridge.receive(input);
      assertEquals(input.requestId(), current.id());
      assertEquals(1, gateway.receives);
      assertThrows(
          IngressRefused.class,
          () -> bridge.receive(request(input.requestId(), "alice", null, "Changed")));
      assertEquals(1, gateway.receives);
    }
  }

  @Test
  void unknown_receive_not_found_is_explicit_and_never_authorizes_republication() throws Exception {
    var gateway = new Messages();
    gateway.noAdmission = true;
    var input = request(UUID.randomUUID(), "alice", null, "Question");
    try (var journal = new FileIngressJournal(directory, "a".repeat(64), "b".repeat(64))) {
      var bridge = bridge(journal, gateway);
      assertThrows(IOException.class, () -> bridge.receive(input));
      assertThrows(IngressRefused.class, () -> bridge.receive(input));
      assertEquals(1, gateway.receives);
      assertEquals(1, gateway.inspects);
    }
  }

  @Test
  void stale_snapshot_cannot_regress_authoritative_revision_or_adopt_another_clients_task()
      throws Exception {
    var gateway = new Messages();
    var input = request(UUID.randomUUID(), "alice", null, "Question");
    try (var journal = new FileIngressJournal(directory, "a".repeat(64), "b".repeat(64))) {
      var bridge = bridge(journal, gateway);
      var accepted = bridge.receive(input);
      gateway.complete(accepted.id());
      var completed = bridge.status(new Ingress.Id("project", "alice", accepted.id()));
      assertEquals("COMPLETED", completed.state());
      gateway.stale = true;
      assertEquals(
          "COMPLETED", bridge.status(new Ingress.Id("project", "alice", accepted.id())).state());
      assertThrows(
          IngressRefused.class,
          () -> bridge.status(new Ingress.Id("project", "bob", accepted.id())));
      assertEquals(2, journal.projection(accepted.id()).orElseThrow().revision());
    }
  }

  @Test
  void cancellation_lost_ack_is_inspected_and_never_repeated() throws Exception {
    var gateway = new Messages();
    var input = request(UUID.randomUUID(), "alice", null, "Question");
    try (var journal = new FileIngressJournal(directory, "a".repeat(64), "b".repeat(64))) {
      var bridge = bridge(journal, gateway);
      var task = bridge.receive(input);
      gateway.loseCancelAck = true;
      assertThrows(
          IOException.class, () -> bridge.cancel(new Ingress.Id("project", "alice", task.id())));
      assertEquals(
          "CANCELED", bridge.cancel(new Ingress.Id("project", "alice", task.id())).state());
      assertEquals(1, gateway.cancels);
    }
  }

  @Test
  void lost_view_ack_and_retention_gap_replay_only_projections_and_fresh_inspection()
      throws Exception {
    var gateway = new Messages();
    gateway.loseViewAck = true;
    var input = request(UUID.randomUUID(), "alice", null, "Question");
    try (var journal = new FileIngressJournal(directory, "a".repeat(64), "b".repeat(64))) {
      var bridge = bridge(journal, gateway);
      assertThrows(IOException.class, () -> bridge.receive(input));
      assertEquals(input.requestId(), bridge.receive(input).id());
      assertEquals(1, gateway.receives);
      gateway.gap = true;
      assertEquals(
          input.requestId(),
          bridge.status(new Ingress.Id("project", "alice", input.requestId())).id());
      assertTrue(gateway.gapAcked);
    }
  }

  @Test
  void foreign_snapshot_is_skipped_with_one_bounded_aggregate_diagnostic() throws Exception {
    var gateway = new Messages();
    gateway.returns.add(
        new RelayLog.Event(
            "1",
            UUID.randomUUID().toString(),
            "unconfigured",
            TIME,
            TIME,
            null,
            null,
            new RelayLog.Payload("TEXT", "secret malformed data", null, null, null),
            null));
    gateway.position = 1;
    try (var journal = new FileIngressJournal(directory, "a".repeat(64), "b".repeat(64))) {
      var bridge = bridge(journal, gateway);
      bridge.receive(request(UUID.randomUUID(), "alice", null, "Question"));
      assertEquals(1, bridge.diagnostics().foreignSnapshots());
      assertEquals(0, bridge.diagnostics().unownedSnapshots());
      assertEquals(1, gateway.receives);
    }
  }

  private RelayIngressBridge largeHistoryBridge(IngressJournal journal, Messages gateway) {
    var b = BINDING;
    return new RelayIngressBridge(
        new RelayIngressBridge.Binding(
            b.project(),
            b.agent(),
            b.requests(),
            b.replies(),
            b.group(),
            b.consumer(),
            b.publisher(),
            30000),
        gateway,
        journal,
        () -> new Ingress.Catalog("reviewer", "Review", true, List.of()),
        () -> TIME);
  }

  @Test
  void unicode_chunks_assemble_exact_bodies_and_history_progresses_beyond_200_replies()
      throws Exception {
    var gateway = new Messages();
    var input = request(UUID.randomUUID(), "alice", null, "Question");
    try (var journal = new FileIngressJournal(directory, "a".repeat(64), "b".repeat(64))) {
      var bridge = largeHistoryBridge(journal, gateway);
      var task = bridge.receive(input);
      gateway.complete(task.id());
      String body = "🙂".repeat(40000) + "tail";
      gateway.history.put(task.id(), chunks("large", body));
      assertEquals(
          body,
          bridge.status(new Ingress.Id("project", "alice", task.id())).replies().getFirst().body());
      var many = new ArrayList<IngressWire.Chunk>();
      many.addAll(chunks("large", body));
      for (int index = 0; index < 250; index++)
        many.add(
            new IngressWire.Chunk(
                "reply-" + index, "Body" + index, "0", true, false, false, null, TIME));
      gateway.history.put(task.id(), many);
      var view = bridge.status(new Ingress.Id("project", "alice", task.id()));
      assertEquals(200, view.replies().size());
      assertEquals("reply-50", view.replies().getFirst().id());
      assertEquals(251, journal.projection(task.id()).orElseThrow().repliesThrough());
    }
    try (var journal = new FileIngressJournal(directory, "a".repeat(64), "b".repeat(64))) {
      assertEquals(
          200,
          largeHistoryBridge(journal, gateway)
              .status(new Ingress.Id("project", "alice", input.requestId()))
              .replies()
              .size());
      assertEquals(251, journal.projection(input.requestId()).orElseThrow().repliesThrough());
    }
  }

  @Test
  void oversized_requested_response_is_explicit_after_lossless_body_assembly() throws Exception {
    var gateway = new Messages();
    var input = request(UUID.randomUUID(), "alice", null, "Question");
    try (var journal = new FileIngressJournal(directory, "a".repeat(64), "b".repeat(64))) {
      var bridge = largeHistoryBridge(journal, gateway);
      var task = bridge.receive(input);
      gateway.complete(task.id());
      gateway.history.put(task.id(), chunks("large", "🙂".repeat(300000)));
      assertThrows(
          IngressResponseTooLarge.class,
          () -> bridge.status(new Ingress.Id("project", "alice", task.id())));
      assertEquals(1, journal.projection(task.id()).orElseThrow().repliesThrough());
      assertEquals(1, gateway.receives);
    }
  }

  @Test
  void higher_revisions_cannot_replace_immutable_native_message_or_creation_identity()
      throws Exception {
    var gateway = new Messages();
    var input = request(UUID.randomUUID(), "alice", null, "Question");
    try (var journal = new FileIngressJournal(directory, "a".repeat(64), "b".repeat(64))) {
      var accepted = bridge(journal, gateway).receive(input);
      var old = gateway.tasks.get(accepted.id());
      for (var changed :
          List.of(
              new IngressWire.Task(
                  old.id(),
                  old.context(),
                  old.requestId(),
                  old.agent(),
                  "bdm_Changed",
                  old.state(),
                  old.ending(),
                  List.of(),
                  old.createdAt()),
              new IngressWire.Task(
                  old.id(),
                  old.context(),
                  old.requestId(),
                  old.agent(),
                  old.message(),
                  old.state(),
                  old.ending(),
                  List.of(),
                  old.createdAt().plusSeconds(1)))) {
        assertThrows(IOException.class, () -> journal.project("alice", 2, changed));
      }
      assertEquals(old.message(), journal.projection(accepted.id()).orElseThrow().task().message());
    }
  }

  @Test
  void actual_return_incarnation_is_pinned_before_intake_and_replacement_stops_new_send()
      throws Exception {
    var gateway = new Messages();
    gateway.failLog = true;
    var input = request(UUID.randomUUID(), "alice", null, "Question");
    try (var journal = new FileIngressJournal(directory, "a".repeat(64), "b".repeat(64))) {
      var bridge = bridge(journal, gateway);
      assertThrows(IOException.class, () -> bridge.receive(input));
      assertEquals(0, gateway.receives);
      assertEquals(
          IngressJournal.Phase.READY, journal.submission(input.requestId()).orElseThrow().phase());
      gateway.failLog = false;
      bridge.receive(input);
      assertTrue(gateway.logReads > 0);
      gateway.returnGeneration = UUID.randomUUID().toString();
      assertThrows(
          IOException.class,
          () -> bridge.receive(request(UUID.randomUUID(), "alice", null, "Next")));
      assertEquals(1, gateway.receives);
    }
  }

  @Test
  void independent_topic_and_group_pins_survive_restart_and_each_recreation_is_refused()
      throws Exception {
    var gateway = new Messages();
    var input = request(UUID.randomUUID(), "alice", null, "Question");
    assertNotEquals(gateway.returnGeneration, gateway.groupGeneration);
    try (var journal = new FileIngressJournal(directory, "a".repeat(64), "b".repeat(64))) {
      assertEquals(input.requestId(), bridge(journal, gateway).receive(input).id());
    }
    try (var journal = new FileIngressJournal(directory, "a".repeat(64), "b".repeat(64))) {
      var bridge = bridge(journal, gateway);
      assertEquals(
          input.requestId(),
          bridge.status(new Ingress.Id("project", "alice", input.requestId())).id());
      gateway.groupGeneration = UUID.randomUUID().toString();
      assertThrows(
          IOException.class,
          () -> bridge.status(new Ingress.Id("project", "alice", input.requestId())));
      assertEquals(1, gateway.receives);
    }
    // Neither failed check may silently replace its own pin or modify the other identity.
    try (var journal = new FileIngressJournal(directory, "a".repeat(64), "b".repeat(64))) {
      journal.generation(GROUP_GENERATION);
      journal.returnBoundary(TOPIC_GENERATION, Long.toString(gateway.position));
      assertThrows(IOException.class, () -> journal.generation(UUID.randomUUID().toString()));
      assertThrows(
          IOException.class,
          () ->
              journal.returnBoundary(
                  UUID.randomUUID().toString(), Long.toString(gateway.position)));
      journal.generation(GROUP_GENERATION);
      journal.returnBoundary(TOPIC_GENERATION, Long.toString(gateway.position));
    }
  }

  @Test
  void interrupted_paging_restarts_at_retained_next_without_rescanning_the_prefix()
      throws Exception {
    var gateway = new Messages();
    var input = request(UUID.randomUUID(), "alice", null, "Question");
    try (var journal = new FileIngressJournal(directory, "a".repeat(64), "b".repeat(64))) {
      var bridge = bridge(journal, gateway);
      bridge.receive(input);
      var history = new ArrayList<IngressWire.Chunk>();
      for (int index = 0; index < 6; index++)
        history.add(
            new IngressWire.Chunk(
                "reply-" + index, "Body" + index, "0", true, false, false, null, TIME));
      gateway.history.put(input.requestId(), history);
      gateway.failCursor = "2";
      assertThrows(
          IOException.class,
          () -> bridge.status(new Ingress.Id("project", "alice", input.requestId())));
      var retained = journal.projection(input.requestId()).orElseThrow();
      assertEquals("2", retained.next());
      assertEquals("resume:0", retained.resume());
    }
    gateway.failCursor = null;
    gateway.inspectCursors.clear();
    try (var journal = new FileIngressJournal(directory, "a".repeat(64), "b".repeat(64))) {
      var result =
          bridge(journal, gateway).status(new Ingress.Id("project", "alice", input.requestId()));
      assertEquals(6, result.replies().size());
      assertEquals(List.of("2", "4"), gateway.inspectCursors);
      assertEquals(null, journal.projection(input.requestId()).orElseThrow().next());
      assertEquals("resume:6", journal.projection(input.requestId()).orElseThrow().resume());
      assertEquals(1, gateway.receives);
    }
  }

  @Test
  void terminal_header_captures_a_fresh_follow_head_before_returning_final_artifacts()
      throws Exception {
    var gateway = new Messages();
    var input = request(UUID.randomUUID(), "alice", null, "Question");
    try (var journal = new FileIngressJournal(directory, "a".repeat(64), "b".repeat(64))) {
      var bridge = largeHistoryBridge(journal, gateway);
      var accepted = bridge.receive(input);
      gateway.complete(accepted.id());
      String body = "🌻".repeat(40000);
      gateway.history.put(accepted.id(), chunks("earlier", body));
      gateway.appendOnCursor.put(
          "resume:3",
          List.of(
              new IngressWire.Chunk(
                  "final", "Final result", "0", true, true, true, "ANSWERED", TIME)));
      var result = bridge.status(new Ingress.Id("project", "alice", accepted.id()));
      assertEquals("COMPLETED", result.state());
      assertEquals(
          List.of(body, "Final result"),
          result.replies().stream().map(Ingress.Reply::body).toList());
      assertTrue(gateway.inspectCursors.contains("resume:3"));
      assertTrue(journal.projection(accepted.id()).orElseThrow().terminalFollowed());
      assertEquals("resume:4", journal.projection(accepted.id()).orElseThrow().resume());
    }
  }

  private static List<IngressWire.Chunk> chunks(String id, String body) {
    var result = new ArrayList<IngressWire.Chunk>();
    int offset = 0;
    while (offset < body.codePointCount(0, body.length())) {
      int end = Math.min(body.codePointCount(0, body.length()), offset + 16384);
      String text =
          body.substring(body.offsetByCodePoints(0, offset), body.offsetByCodePoints(0, end));
      result.add(
          new IngressWire.Chunk(
              id,
              text,
              Integer.toString(offset),
              end == body.codePointCount(0, body.length()),
              true,
              false,
              "ANSWERED",
              TIME));
      offset = end;
    }
    return result;
  }

  static final class Messages implements RelayGateway {
    final ObjectMapper json = new ObjectMapper();
    final ArrayDeque<RelayLog.Event> returns = new ArrayDeque<>();
    final Map<UUID, IngressWire.Task> tasks = new HashMap<>();
    final Map<UUID, String> clients = new HashMap<>();
    final Map<UUID, List<IngressWire.Chunk>> history = new HashMap<>();
    final Map<String, List<IngressWire.Chunk>> appendOnCursor = new HashMap<>();
    final List<String> methods = new ArrayList<>();
    final List<String> inspectCursors = new ArrayList<>();
    int receives, inspects, cancels, logReads;
    String failCursor;
    String returnGeneration = TOPIC_GENERATION;
    String groupGeneration = GROUP_GENERATION;
    boolean failLog;
    long position;
    boolean loseReceiveAck,
        loseCancelAck,
        dropReceiveReply,
        noAdmission,
        stale,
        loseViewAck,
        gap,
        gapAcked;

    public RelayPort.Published publish(RelayPort.Publish request) throws IOException {
      JsonNode command = json.readTree(request.text());
      String schema = command.get("schema").asText();
      methods.add(schema);
      if (schema.equals("plowshare-message-inspect/1")) {
        String cursor = command.get("cursor").isNull() ? null : command.get("cursor").asText();
        inspectCursors.add(cursor);
        if (failCursor != null && failCursor.equals(cursor))
          throw new IOException("Interrupted page inspection");
      }
      UUID operation = UUID.fromString(command.get("requestId").asText());
      String client = command.get("client").asText();
      IngressWire.Task task = null;
      String refusal = null;
      if (schema.equals("plowshare-message-request/1")) {
        receives++;
        if (noAdmission) return receipt(request);
        UUID context =
            command.get("context").isNull()
                ? UUID.randomUUID()
                : UUID.fromString(command.get("context").asText());
        task =
            new IngressWire.Task(
                operation.toString(),
                context.toString(),
                operation.toString(),
                "reviewer",
                "bdm_Fixture",
                "SUBMITTED",
                null,
                List.of(),
                TIME);
        tasks.put(operation, task);
        clients.put(operation, client);
      } else {
        UUID selector =
            command.get("task").isNull()
                ? UUID.fromString(command.get("originalRequest").asText())
                : UUID.fromString(command.get("task").asText());
        String suppliedCursor =
            command.has("cursor") && !command.get("cursor").isNull()
                ? command.get("cursor").asText()
                : null;
        var later = appendOnCursor.remove(suppliedCursor);
        if (later != null) {
          var all = new ArrayList<>(history.getOrDefault(selector, List.of()));
          all.addAll(later);
          history.put(selector, all);
        }
        task = tasks.get(selector);
        if (task == null || !client.equals(clients.get(selector))) {
          task = null;
          refusal = "NOT_ACCESSIBLE";
        }
        if (schema.equals("plowshare-message-inspect/1")) {
          inspects++;
          if (stale && task != null)
            task =
                new IngressWire.Task(
                    task.id(),
                    task.context(),
                    task.requestId(),
                    task.agent(),
                    task.message(),
                    "SUBMITTED",
                    null,
                    task.replies(),
                    task.createdAt());
        }
        if (schema.equals("plowshare-message-cancel/1")) {
          cancels++;
          if (task != null) {
            task =
                new IngressWire.Task(
                    task.id(),
                    task.context(),
                    task.requestId(),
                    task.agent(),
                    task.message(),
                    "CANCELED",
                    "CANCELLED",
                    List.of(),
                    task.createdAt());
            tasks.put(selector, task);
          }
        }
      }
      if (!(schema.equals("plowshare-message-request/1") && dropReceiveReply)) {
        String next = null, resume = null;
        if (task != null) {
          var chunks = history.getOrDefault(UUID.fromString(task.id()), task.replies());
          String supplied =
              command.has("cursor") && !command.get("cursor").isNull()
                  ? command.get("cursor").asText()
                  : null;
          int cursor = supplied == null ? 0 : Integer.parseInt(supplied.replace("resume:", ""));
          int end = Math.min(chunks.size(), cursor + 2);
          if (end < chunks.size()) next = Integer.toString(end);
          else resume = "resume:" + end;
          task =
              new IngressWire.Task(
                  task.id(),
                  task.context(),
                  task.requestId(),
                  task.agent(),
                  task.message(),
                  task.state(),
                  task.ending(),
                  chunks.subList(cursor, end),
                  task.createdAt());
        }
        var snapshot =
            new IngressWire.Snapshot(
                "plowshare-message-task/1",
                operation.toString(),
                request.requestId(),
                client,
                task == null ? null : task.state().equals("SUBMITTED") ? "1" : "2",
                task,
                refusal,
                next,
                resume);
        String event = UUID.randomUUID().toString();
        returns.add(
            new RelayLog.Event(
                Long.toString(++position),
                event,
                "messaging:ingress",
                TIME,
                TIME,
                operation.toString(),
                request.requestId(),
                new RelayLog.Payload("TEXT", IngressCodec.write(snapshot), null, null, null),
                null));
      }
      if (schema.equals("plowshare-message-request/1") && loseReceiveAck)
        throw new IOException("Lost receive publication acknowledgement");
      if (schema.equals("plowshare-message-cancel/1") && loseCancelAck)
        throw new IOException("Lost cancel publication acknowledgement");
      return receipt(request);
    }

    private RelayPort.Published receipt(RelayPort.Publish request) {
      return new RelayPort.Published(
          request.requestId(),
          request.project(),
          request.topic(),
          Long.toString(Math.max(1, position)),
          TIME);
    }

    public RelayPort.Batch consume(RelayPort.Consume request) {
      boolean leased = gap || !returns.isEmpty();
      return new RelayPort.Batch(
          request.project(),
          request.topic(),
          request.group(),
          request.consumerId(),
          gap
              ? RelayPort.Status.GAP
              : returns.isEmpty() ? RelayPort.Status.EMPTY : RelayPort.Status.DATA,
          leased ? GENERATION : null,
          leased ? "1" : null,
          Long.toString(Math.max(1, position)),
          leased ? TIME.plusSeconds(30) : null,
          gap ? "1" : null,
          gap ? List.of() : List.copyOf(returns),
          groupGeneration);
    }

    public RelayPort.Acknowledged acknowledge(RelayPort.Ack request) throws IOException {
      if (loseViewAck) {
        loseViewAck = false;
        throw new IOException("Lost view acknowledgement");
      }
      if (gap) {
        gap = false;
        gapAcked = true;
      } else returns.clear();
      return new RelayPort.Acknowledged(
          request.project(),
          request.topic(),
          request.group(),
          request.batchId(),
          Long.toString(Math.max(1, position)),
          request.expiredThrough() != null);
    }

    public RelayLog.Page log(RelayLog.Query request) throws IOException {
      logReads++;
      if (failLog) throw new IOException("Return topic unavailable");
      return new RelayLog.Page(
          new RelayLog.Scope(request.project(), false),
          new RelayLog.Topic(
              request.topic(),
              "TEXT",
              "3600",
              null,
              Long.toString(position),
              "0",
              returnGeneration),
          "0",
          "0",
          null,
          List.of(),
          List.of(),
          List.of());
    }

    void complete(UUID id) {
      var old = tasks.get(id);
      tasks.put(
          id,
          new IngressWire.Task(
              old.id(),
              old.context(),
              old.requestId(),
              old.agent(),
              old.message(),
              "COMPLETED",
              "ANSWERED",
              List.of(
                  new IngressWire.Chunk(
                      "answer", "Result", "0", true, true, false, "ANSWERED", TIME)),
              old.createdAt()));
    }
  }
}
