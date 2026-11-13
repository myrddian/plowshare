package io.aeyer.plowshare.integrations;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RelayRuntimeFixtures {
  static final String PRODUCER = "execution:" + "a".repeat(64) + ":" + "b".repeat(64);
  static final String PUBLISHER = RelayPort.publisher("adapter");

  static RelayRequests.Policy policy(String name) {
    return new RelayRequests.Policy(
        name + ".requests",
        Json.identity(name + ".requests"),
        name + ".returns",
        Json.identity(name + ".returns"),
        name,
        PUBLISHER,
        Set.of(PRODUCER),
        8,
        65536);
  }

  static Configuration.Binding binding(Configuration.Binding value) {
    return new Configuration.Binding(
        value.name(),
        value.adapter(),
        value.project(),
        value.configuration(),
        value.routes(),
        value.allowBindings(),
        value.script(),
        value.fingerprint(),
        value.queue(),
        value.feedback(),
        policy(value.name()));
  }

  static class Gateway extends IntegrationRuntimeTest.FakeGateway {
    final Map<String, List<RelayLog.Event>> events = new HashMap<>();
    final Map<String, String> generations = new HashMap<>();
    boolean losePublish;
    int publications;

    RelayLog.Event request(String binding, String text) {
      var topic = policy(binding).requests();
      var rows = events.computeIfAbsent(topic, ignored -> new ArrayList<>());
      var id = Json.identity(binding + ":" + rows.size() + ":" + text).toString();
      var at = Instant.parse("2026-11-13T00:00:00Z");
      var event =
          new RelayLog.Event(
              Integer.toString(rows.size() + 1),
              id,
              PRODUCER,
              at,
              at,
              null,
              null,
              new RelayLog.Payload("TEXT", text, null, null, null),
              RelayCausation.root(id));
      rows.add(event);
      return event;
    }

    @Override
    public RelayLog.Page log(RelayLog.Query query) throws IOException {
      var all = events.getOrDefault(query.topic(), List.of());
      var rows =
          all.stream()
              .filter(e -> Long.parseLong(e.position()) > Long.parseLong(query.after()))
              .limit(query.limit())
              .toList();
      String through = all.isEmpty() ? "0" : all.getLast().position();
      String next = rows.isEmpty() ? query.after() : rows.getLast().position();
      return new RelayLog.Page(
          new RelayLog.Scope(query.project(), false),
          new RelayLog.Topic(
              query.topic(),
              "TEXT",
              "3600",
              null,
              through,
              "0",
              generations.getOrDefault(query.topic(), Json.identity(query.topic()).toString())),
          query.after(),
          next,
          null,
          rows,
          List.of(),
          List.of());
    }

    @Override
    public RelayPort.Published publish(RelayPort.Publish request) throws IOException {
      publications++;
      var rows = events.computeIfAbsent(request.topic(), ignored -> new ArrayList<>());
      String position = Integer.toString(rows.size() + 1);
      rows.add(
          new RelayLog.Event(
              position,
              request.requestId(),
              PUBLISHER,
              request.occurredAt(),
              request.occurredAt(),
              request.correlationId(),
              request.parentEventId(),
              new RelayLog.Payload("TEXT", request.text(), null, null, null)));
      if (losePublish) {
        losePublish = false;
        throw new IOException("Reply lost after publication committed");
      }
      return new RelayPort.Published(
          request.requestId(), request.project(), request.topic(), position, request.occurredAt());
    }
  }
}
