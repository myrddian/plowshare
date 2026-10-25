package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.ActingContext;
import io.aeyer.plowshare.protocol.frames.Envelope;
import io.aeyer.plowshare.protocol.transport.PacketBudget;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the operation inventory against both schedulers, rather than only checking a set. */
class DispatcherReadAuditTest {
  static Stream<String> reads() {
    return AUDITED_READS.lines().flatMap(line -> Stream.of(line.trim().split(" +")));
  }

  // Explicit acceptance inventory: a new operation must be audited before joining this fixture.
  private static final String AUDITED_READS =
      """
      admin.accounts admin.sessions admin.audit
      admin.service.accounts admin.service.tokens admin.pricing.list
      project.list project.access conversation.list
      conversation.latest agent.list job.list
      job.status board.messages swarm.types
      proc.list proc.inspect proc.calls
      proc.queues proc.tools usage.conversation
      usage.project usage.agent usage.run
      usage.orchestration usage.models usage.pools
      usage.calls document.chunk document.citations
      approval.list orchestration.status orchestration.receipt
      orchestration.definitions orchestration.record memory.index
      proposal.list provider.list trigger.list
      firing.list union.conflict.list message.instances
      message.instance message.deliveries message.delivery
      incoming.catalog incoming.status outgoing.status
      outgoing.peers application.files application.file.read
      application.identity.status application.token.receipt application.deployment.status
      application.deployment.receipt filestore.list provider.connection.list
      provider.connection.status provider.connection.receipt tool.scope.list
      tool.scope.host.list tool.scope.host.status tool.scope.host.receipt
      tool.scope.worker.status information.inventory information.acquisitions
      information.outline information.symbols information.events
      information.evidence.read information.migration.list information.types.capabilities
      information.types.list information.types.get information.types.validate
      information.content.get information.policy.get information.views.list
      information.views.get information.interchange.capabilities information.interchange.export
      information.interchange.readable information.interchange.origin
      """;

  private static Envelope request(String id, String type) {
    return new Envelope(id, type, Envelope.CURRENT_VERSION, Map.of());
  }

  @ParameterizedTest
  @MethodSource("reads")
  void slow_audited_read_does_not_block_an_unrelated_app_read(String type) throws Exception {
    var budget = new PacketBudget(1024 * 1024);
    var telemetry = new DispatchTelemetry();
    var entered = new CompletableFuture<Void>();
    var fast = new CompletableFuture<Void>();
    var release = new CompletableFuture<Void>();
    try (var dispatcher = new ConcurrentRequests(budget, 2, 4, telemetry)) {
      var connection =
          dispatcher.connect(
              "audit",
              (frame, delivered) -> {
                if (frame.equals("slow")) {
                  entered.complete(null);
                  return release.thenRun(delivered);
                }
                fast.complete(null);
                delivered.run();
                return CompletableFuture.completedFuture(null);
              },
              () -> fail("Unexpected failure"),
              4);
      try {
        connection.offer("slow", request("slow", type), "alice");
        entered.get(5, TimeUnit.SECONDS);
        connection.offer("fast", request("fast", "information.read"), "alice");
        fast.get(5, TimeUnit.SECONDS);
        assertFalse(release.isDone());
      } finally {
        release.complete(null);
      }
      dispatcher.stopAdmission();
      dispatcher.drained().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(0, budget.heldBytes());
      assertEquals(0, telemetry.snapshot().resourceKeys());
    }
  }

  @ParameterizedTest
  @MethodSource("reads")
  void shared_read_inventory_never_bypasses_acting_context_order(String type) {
    assertTrue(EventReadPolicy.independent(EventReadPolicyTest.frame("read", type)), type);
    var context = UUID.randomUUID();
    var acting =
        new Envelope(
            "read", type, Envelope.CURRENT_VERSION, Map.of(), new ActingContext.Selection(context));
    var policy = DispatchPolicy.decode(acting, "alice", "socket");
    assertEquals(DispatchPolicy.Kind.RESOURCE, policy.kind());
    assertEquals(
        List.of(new DispatchPolicy.Resource("acting", "socket", context.toString())),
        policy.resources());
    assertFalse(EventReadPolicy.independent(FrameJson.answering().valueToTree(acting).toString()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "memory.read",
        "memory.recall",
        "memory.navigate",
        "document.retrieve",
        "document.rank",
        "document.stance",
        "conversation.search",
        "provider.credential.status",
        "orchestration.caps",
        "information.status",
        "information.migration.inspect",
        "union.status",
        "union.hidden",
        "tool.scope.worker.read",
        "tool.scope.worker.credential",
        "job.limits",
        "job.stream",
        "conversation.follow",
        "information.views.save",
        "information.policy.set",
        "information.interchange.import",
        "application.file.save",
        "provider.connection.change",
        "project.member.remove",
        "approval.answer",
        "inbox.read",
        "outgoing.claim",
        "future.read"
      })
  void effectful_and_unknown_operations_keep_barriers(String type) {
    assertEquals(
        DispatchPolicy.Kind.BARRIER,
        DispatchPolicy.decode(request("effect", type), "alice", "socket").kind(),
        type);
    assertFalse(EventReadPolicy.independent(EventReadPolicyTest.frame("effect", type)), type);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"conversation.context.count", "conversation.context.snapshot", "todos.read"})
  void conversation_inspection_keeps_the_mutation_key(String type) {
    var payload = Map.<String, Object>of("conversation", "conversation-one");
    assertEquals(
        DispatchPolicy.decode(
            new Envelope("write", "conversation.resume", Envelope.CURRENT_VERSION, payload),
            "alice",
            "one"),
        DispatchPolicy.decode(
            new Envelope("read", type, Envelope.CURRENT_VERSION, payload), "bob", "two"));
    assertEquals(
        DispatchPolicy.Kind.BARRIER,
        DispatchPolicy.decode(request("missing", type), "alice", "socket").kind());
    assertFalse(EventReadPolicy.independent(EventReadPolicyTest.frame("read", type)));
  }
}
