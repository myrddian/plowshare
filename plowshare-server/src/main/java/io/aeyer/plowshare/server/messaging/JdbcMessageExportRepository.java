package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.relay.RelayCausationCodec;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Messaging-owned source proof and export/return receipts; no transport DTO or SQL escapes. */
public final class JdbcMessageExportRepository implements MessageExportRepository {
  private final JdbcTemplate jdbc;

  public JdbcMessageExportRepository(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  @Override
  public NativeMessageExports.Source source(UsageAttribution owner) {
    Objects.requireNonNull(owner);
    if (owner.status() != UsageAttribution.Status.ATTRIBUTED
        || owner.scope() != UsageAttribution.Scope.PROJECT
        || owner.conversations().id() == null
        || owner.runs().id() == null
        || owner.agentName() == null
        || owner.turnOrdinal() == null
        || owner.turnOrdinal() < 1)
      throw new MessageRefused("An exact native export source is unavailable");
    // Turn ordinals are reserved before entries. The immutable run owner plus that turn's
    // server-authored message speaker identifies the exact recipient even when definitions share
    // one history. Approval/delegate sources without this evidence are refused, never guessed.
    var sources =
        jdbc.query(
            """
        SELECT p.name,c.origin,e.ordinal AS utterance_ordinal,e.speaker,e.speaker_name,r.message AS native_route,
          i.id,i.account,i.project,i.agent,i.conversation,i.lifetime,i.active
        FROM inference_run_ownership o JOIN conversations c ON c.id=o.conversation_id
        JOIN projects p ON p.id=c.project_id
        LEFT JOIN entries e ON e.conversation_id=c.id AND e.turn_ordinal=o.turn_ordinal AND e.kind='utterance'
        LEFT JOIN message_routes r ON e.speaker='harness' AND e.speaker_name LIKE 'message bdm_%'
          AND r.message=substring(e.speaker_name FROM 9)
        LEFT JOIN message_instances i ON i.conversation=c.id AND i.account=? AND i.agent=?
          AND i.project=p.name AND ((r.message IS NOT NULL AND i.id=r.recipient)
            OR (c.origin='turn' AND r.message IS NULL AND i.lifetime='caller'))
        WHERE o.conversation_id=? AND o.turn_ordinal=? AND c.project_id=?
          AND c.owner_handle=? AND c.lifecycle='active'
          AND o.attribution->>'accountHandle'=? AND o.attribution->>'projectId'=?
          AND o.attribution->'runs'->>'id'=? AND o.attribution->>'agentName'=?
          AND o.attribution->>'status'='ATTRIBUTED'
        """,
            (row, number) -> {
              var participant =
                  row.getString("id") == null
                      ? Optional.<MessageService.Instance>empty()
                      : Optional.of(
                          new MessageService.Instance(
                              row.getString("id"),
                              row.getString("account"),
                              row.getString("project"),
                              row.getString("agent"),
                              row.getString("conversation"),
                              row.getString("lifetime"),
                              row.getBoolean("active")));
              var origin = Origin.of(row.getString("origin"));
              String speaker = row.getString("speaker");
              String named = row.getString("speaker_name");
              boolean direct =
                  "harness".equals(speaker) && named != null && named.startsWith("message ");
              boolean approval =
                  "harness".equals(speaker) && named != null && named.startsWith("approval ");
              if ((origin != Origin.MESSAGE && origin != Origin.TURN)
                  || row.getObject("utterance_ordinal") == null
                  || speaker == null
                  || approval
                  || origin == Origin.MESSAGE && !direct
                  || direct && (row.getString("native_route") == null || participant.isEmpty()))
                throw new MessageRefused("This execution source cannot export native requests");
              return new NativeMessageExports.Source(
                  owner, row.getString("name"), origin, participant);
            },
            owner.accountHandle(),
            owner.agentName(),
            owner.conversations().id(),
            owner.turnOrdinal(),
            Long.valueOf(owner.projectId()),
            owner.accountHandle(),
            owner.accountHandle(),
            owner.projectId(),
            owner.runs().id(),
            owner.agentName());
    if (sources.size() != 1)
      throw new MessageRefused("An exact native export source is unavailable");
    return sources.getFirst();
  }

  @Override
  public void bind(Binding binding) {
    Objects.requireNonNull(binding);
    var policy = binding.policy();
    var nativeRequest = binding.nativeRequest();
    if (jdbc.update(
            """
        INSERT INTO message_execution_exports(source_event,project_id,project,request_topic,channel,
          return_topic,return_generation,adapter_account,request_message,requester,external_participant,account,relay_causation)
        SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb
        FROM relay_execution_publications e JOIN message_instances s ON s.id=?
        JOIN message_instances d ON d.id=? JOIN message_routes r ON r.message=?
        WHERE e.id=? AND e.project_id=? AND e.account=s.account AND e.agent=s.agent
          AND e.conversation_id=s.conversation AND s.account=? AND s.project=?
          AND d.account=s.account AND d.project=s.project AND d.lifetime='external'
          AND r.sender=s.id AND r.recipient=d.id AND r.reply_to IS NULL
        """,
            binding.sourceEvent(),
            binding.projectId(),
            policy.project(),
            policy.requestTopic(),
            policy.channel(),
            policy.returnTopic(),
            binding.returnGeneration(),
            policy.adapterAccount(),
            nativeRequest.requestMessage(),
            nativeRequest.requester(),
            nativeRequest.external(),
            nativeRequest.account(),
            RelayCausationCodec.write(binding.causation()),
            nativeRequest.requester(),
            nativeRequest.external(),
            nativeRequest.requestMessage(),
            binding.sourceEvent(),
            binding.projectId(),
            nativeRequest.account(),
            policy.project())
        != 1) throw new IllegalStateException("Native export binding was not retained");
  }

  @Override
  public Optional<Binding> binding(UUID sourceEvent, boolean lock) {
    return jdbc
        .query(
            "SELECT source_event,project_id,project,request_topic,channel,return_topic,return_generation,adapter_account,request_message,requester,external_participant,account,relay_causation::text FROM message_execution_exports WHERE source_event=?"
                + (lock ? " FOR UPDATE" : ""),
            (row, number) ->
                new Binding(
                    row.getObject("source_event", UUID.class),
                    row.getLong("project_id"),
                    new MessageExportProperties.Policy(
                        row.getString("project"),
                        row.getString("request_topic"),
                        row.getString("channel"),
                        row.getString("return_topic"),
                        row.getString("adapter_account")),
                    row.getObject("return_generation", UUID.class),
                    new NativeMessageExports.Export(
                        row.getString("request_message"),
                        row.getString("requester"),
                        row.getString("external_participant"),
                        row.getString("account"),
                        row.getString("project")),
                    RelayCausationCodec.read(row.getString("relay_causation"))),
            Objects.requireNonNull(sourceEvent))
        .stream()
        .findFirst();
  }

  @Override
  public void lockReturn(UUID requestId) {
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended('message-return:'||?::text,0))",
        Object.class,
        Objects.requireNonNull(requestId));
  }

  @Override
  public Optional<MessageExports.Receipt> receipt(MessageExports.Command command) {
    return jdbc
        .query(
            """
        SELECT source_event,project_id,project,channel,return_topic,adapter_account,fingerprint,message
        FROM message_execution_returns WHERE request_id=?
        """,
            (row, number) -> {
              if (!command.value().sourceEvent().equals(row.getObject("source_event", UUID.class))
                  || command.projectId() != row.getLong("project_id")
                  || !command.project().equals(row.getString("project"))
                  || !command.channel().equals(row.getString("channel"))
                  || !command.returnTopic().equals(row.getString("return_topic"))
                  || !command.adapterAccount().equals(row.getString("adapter_account"))
                  || !fingerprint(command).equals(row.getString("fingerprint")))
                throw new MessageRefused("This return UUID already identifies different work");
              return new MessageExports.Receipt(row.getString("message"));
            },
            command.value().requestId())
        .stream()
        .findFirst();
  }

  @Override
  public void save(
      MessageExports.Command command, MessageExports.Receipt receipt, RelayCausation causation) {
    if (jdbc.update(
            """
        INSERT INTO message_execution_returns(request_id,source_event,project_id,project,channel,
          return_topic,adapter_account,fingerprint,message,relay_causation)
        SELECT ?,?,?,?,?,?,?,?,?,?::jsonb
        FROM message_execution_exports e JOIN message_routes r ON r.message=?
        JOIN message_records m ON m.id=r.message
        WHERE e.source_event=? AND e.project_id=? AND e.project=? AND e.channel=?
          AND e.return_topic=? AND e.adapter_account=? AND r.sender=e.external_participant
          AND r.recipient=e.requester AND r.reply_to=e.request_message AND m.body=? AND r.final=?
        """,
            command.value().requestId(),
            command.value().sourceEvent(),
            command.projectId(),
            command.project(),
            command.channel(),
            command.returnTopic(),
            command.adapterAccount(),
            fingerprint(command),
            receipt.message(),
            RelayCausationCodec.write(Objects.requireNonNull(causation)),
            receipt.message(),
            command.value().sourceEvent(),
            command.projectId(),
            command.project(),
            command.channel(),
            command.returnTopic(),
            command.adapterAccount(),
            command.value().body(),
            command.value().finalReply())
        != 1) throw new IllegalStateException("Native return receipt was not retained");
  }

  private static String fingerprint(MessageExports.Command command) {
    return RelayPort.hash(
        command.value().sourceEvent()
            + "\n"
            + command.value().body()
            + "\n"
            + command.value().finalReply());
  }
}
