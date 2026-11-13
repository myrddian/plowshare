package io.aeyer.plowshare.a2a.relay;

import io.aeyer.plowshare.a2a.Ingress;
import io.aeyer.plowshare.protocol.RelayPort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Adapter publication evidence and bounded task projections, never an execution queue. */
public interface IngressJournal extends AutoCloseable {
  enum Phase {
    READY,
    INTENT,
    SUBMITTED,
    SETTLED
  }

  record Submission(
      UUID request,
      IngressWire.Action action,
      String client,
      String hash,
      String wire,
      String event,
      Instant occurredAt,
      Phase phase,
      UUID task,
      UUID originalRequest,
      UUID context,
      Ingress.Source source,
      String refusal,
      String next,
      String resume) {
    public Submission {
      Objects.requireNonNull(request);
      Objects.requireNonNull(action);
      IngressWire.checkClient(client);
      if (hash == null || !hash.matches("[a-f0-9]{64}"))
        throw new IllegalArgumentException("Invalid ingress intent hash");
      Objects.requireNonNull(wire);
      IngressWire.uuid(event);
      Objects.requireNonNull(phase);
      occurredAt = Objects.requireNonNull(occurredAt).truncatedTo(ChronoUnit.MICROS);
      if (action == IngressWire.Action.RECEIVE
          && (!request.equals(originalRequest) || source == null))
        throw new IllegalArgumentException("Receive needs retained external provenance");
      if (phase == Phase.SETTLED && ((task == null) == (refusal == null)))
        throw new IllegalArgumentException("Settled operation needs one definite outcome");
    }

    Submission phase(Phase target) {
      return new Submission(
          request,
          action,
          client,
          hash,
          wire,
          event,
          occurredAt,
          target,
          task,
          originalRequest,
          context,
          source,
          refusal,
          next,
          resume);
    }

    Submission settled(IngressWire.Snapshot snapshot) {
      return new Submission(
          request,
          action,
          client,
          hash,
          wire,
          event,
          occurredAt,
          Phase.SETTLED,
          snapshot.task() == null ? null : UUID.fromString(snapshot.task().id()),
          originalRequest,
          context,
          source,
          snapshot.refusal(),
          snapshot.next(),
          snapshot.resume());
    }
  }

  record Assembly(
      String id,
      String body,
      boolean complete,
      boolean finalReply,
      boolean generated,
      String ending,
      Instant postedAt) {
    public Assembly {
      var metadata = new Ingress.ReplyMetadata(id, ending, postedAt);
      id = metadata.id();
      ending = metadata.ending();
      RelayWork.text(body, RelayPort.MAX_TEXT_BYTES);
      if (body.getBytes(StandardCharsets.UTF_8).length > RelayPort.MAX_TEXT_BYTES)
        throw new IllegalArgumentException("Native reply body exceeds50MiBUTF8");
    }

    public Ingress.Reply reply() {
      if (!complete) throw new IllegalStateException("Incomplete immutable reply body");
      return new Ingress.Reply(id, body, finalReply, generated, ending, postedAt);
    }
  }

  record Projection(
      String client,
      long revision,
      IngressWire.Task task,
      long repliesThrough,
      List<String> replies,
      List<String> incomplete,
      String next,
      String resume,
      boolean terminalFollowed) {
    public Projection {
      IngressWire.checkClient(client);
      if (revision < 1) throw new IllegalArgumentException("Positive revision required");
      Objects.requireNonNull(task);
      if (next != null) RelayWork.identity(next);
      if (resume != null) RelayWork.identity(resume);
      replies = List.copyOf(replies);
      incomplete = List.copyOf(incomplete);
      if (!replies.containsAll(incomplete))
        throw new IllegalArgumentException("Untracked incomplete reply");
      if (repliesThrough < replies.size())
        throw new IllegalArgumentException("Invalid reply projection frontier");
      if (replies.size() > 200)
        throw new IllegalArgumentException("Task history exceeds200replies");
    }
  }

  /**
   * Retained historical transport evidence; no Relay publication or native admission is invented.
   */
  record Imported(
      UUID originalRequest,
      UUID externalRequest,
      String client,
      UUID task,
      UUID context,
      String agent,
      String message,
      Instant createdAt,
      Ingress.Source source,
      boolean originalContextKnown,
      Instant importedAt) {
    public Imported {
      Objects.requireNonNull(originalRequest);
      IngressWire.checkClient(client);
      Objects.requireNonNull(task);
      Objects.requireNonNull(context);
      RelayWork.identity(agent);
      RelayWork.identity(message);
      Objects.requireNonNull(createdAt);
      Objects.requireNonNull(importedAt);
      if (source == null && originalContextKnown
          || !originalContextKnown && externalRequest != null)
        throw new IllegalArgumentException(
            "Ambiguous historical provenance cannot identify a fresh mutation");
      if (source != null
          && (source.taskId() != null
              || source.contextId() != null && !source.contextId().equals(context)))
        throw new IllegalArgumentException("Historical source context differs from its owned task");
    }
  }

  Optional<Imported> imported(String client, UUID originalRequest) throws IOException;

  Optional<Imported> importedExternal(UUID externalRequest) throws IOException;

  boolean ambiguousClient(String client) throws IOException;

  void beginImport(String bundleHash) throws IOException;

  Imported validateImport(Imported source, Projection authoritative) throws IOException;

  void importSource(Imported source, Projection authoritative) throws IOException;

  void completeImport(String bundleHash) throws IOException;

  Optional<Submission> submission(UUID request) throws IOException;

  void save(Submission submission) throws IOException;

  Optional<Projection> projection(UUID task) throws IOException;

  /** Ignore older revisions; equal revisions must contain exactly equal evidence. */
  void project(String client, long revision, IngressWire.Task page) throws IOException;

  List<Ingress.Reply> replies(Projection view, int maximum) throws IOException;

  /** Save each completed page before requesting the next; final FOLLOW remains separate. */
  Projection checkpoint(Projection view, String next, String resume, boolean terminalFollowed)
      throws IOException;

  /** Actual return metadata captured before a new publication; never evidence of non-delivery. */
  void returnBoundary(String generation, String through) throws IOException;

  void generation(String generation) throws IOException;

  void observedGap(String expiredThrough) throws IOException;

  @Override
  void close() throws IOException;
}
