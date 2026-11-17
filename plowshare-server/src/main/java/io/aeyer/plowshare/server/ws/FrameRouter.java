package io.aeyer.plowshare.server.ws;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Envelope;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.access.AccessRequestDecoder;
import io.aeyer.plowshare.server.access.ProjectAuthorization.ExternalAdmission;
import io.aeyer.plowshare.server.agents.ExternalConversationUnavailableException;
import io.aeyer.plowshare.server.api.ConversationView;
import io.aeyer.plowshare.server.api.StartedJob;
import io.aeyer.plowshare.server.archive.ArchiveUnavailableException;
import io.aeyer.plowshare.server.archive.ClientProjects;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.Fault;
import io.aeyer.plowshare.server.faults.Faults;
import io.aeyer.plowshare.server.personal.PersonalScope;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import io.aeyer.plowshare.server.telemetry.FailureReporter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Turns one inbound frame into an {@link Outcome} — a dotted {@code type} to a {@link
 * FrameHandler}, and every way that can fail to a wire-shaped answer instead of a crash.
 *
 * <h2>This class never writes to a socket, and that is structural rather than a rule somebody has
 * to remember</h2>
 *
 * <p>The dispatcher survey names exactly this as the risk most likely to bite: a dispatcher that
 * offers one generic "send the response" convenience gets called from the wrong thread on the wrong
 * channel, and a push-style channel silently re-acquires the blocking-write hazard {@code
 * EventChannelHandler} was built to remove (Tomcat's {@code DEFAULT_BLOCKING_SEND_TIMEOUT}, 20 000
 * ms, on a stalled listener's own turn). The fix here is not a comment saying "don't call socket
 * methods from here" — it is that neither {@link #route(Envelope, Asking)} nor {@link
 * #route(String, Asking)} nor {@link FrameHandler#handle} accepts a {@code WebSocketSession}, a
 * queue, or any other handle a socket write could be attempted through. There is no reference to
 * write with, so the constraint holds even for code that never read this paragraph. What a channel
 * handler does with the {@link Outcome} this class hands back — write it inline (a genuine
 * request/response wait, the file channel's shape) or offer it to a bounded queue and let a drain
 * thread absorb the actual write latency (the event channel's shape) — stays that channel's own
 * decision, undisturbed by anything here.
 *
 * <h2>Registration is a hand-built map, not a scan</h2>
 *
 * <p>The survey's Part 3 recommendation, followed rather than VoidCore's
 * reflection-over-{@code @JsonSubTypes} trick: Plowshare's frame {@code type} is a flat string, the
 * way {@code FileRequest.op} already is, not a sealed type with an annotation to reflect over, so a
 * literal {@code Map<String, FrameHandler>} written out in source is no less honest a single source
 * of truth than a scan, and it is something a reader can open and read without running the app.
 * That literal is now one map per area rather than one for the whole server — see {@link FrameArea}
 * for why fifty types in one {@code @Configuration} method was a shape seven parallel tasks could
 * not share — but it is still literal, and {@link FrameRoutingConfig} only unions what the areas
 * name. This constructor is that map's only consumer of a raw {@code String} key: every key is
 * checked against {@link FrameTypes#requireWellFormed(String)} before it is stored, so a typo'd
 * registration fails the boot instead of failing whichever request first names it correctly.
 *
 * <h2>Every failure resolves through {@link Faults}, including the two this class handles before a
 * handler ever runs</h2>
 *
 * <p>The plan's own instruction is not to write a second exception-to-status mapping, and this
 * class holds to it more strictly than the letter of that sentence requires: it picks no {@link
 * Code} itself for anything a handler threw, and even the two failures that happen before dispatch
 * — a frame that will not parse, and one naming the wrong {@code protocol_version} — are
 * represented as a {@link CallerFault} and handed to {@link Faults#of(Throwable)} rather than
 * answered with a locally-chosen {@code Code.BAD_REQUEST} literal. The one exception is an
 * unregistered {@code type}: that is this router's own routing decision, not a caught exception,
 * and reaching for {@code api.NotFoundException} or a fabricated one just to have something to hand
 * {@code Faults} would misuse a type whose own class javadoc scopes it to "a handle the caller
 * holds does not resolve — a job id this process does not know". An unregistered type is answered
 * with {@link Code#NOT_FOUND} directly instead — the same status the table would have given a job
 * id nothing recognised, read from the one shared vocabulary rather than reconstructed.
 *
 * <h2>A malformed frame does not take the session down</h2>
 *
 * <p>{@code FileChannelHandler.handleTextMessage} already measured why: an exception escaping a
 * {@code WebSocketHandler}'s text-message callback is decorated by Spring's {@code
 * ExceptionWebSocketHandlerDecorator} into a session-wide {@code SERVER_ERROR} close, ending every
 * other outstanding request that session was holding for one bad frame. {@link #route(String,
 * Asking)} matches that class's own double guard rather than inventing a second policy: a frame
 * that fails to parse at all (a syntax error, a wrong {@code protocol_version}, a missing {@code
 * type}) is caught before it becomes an exception a caller of this method ever sees, and a frame
 * that parses to the JSON literal {@code null} — which {@code ObjectMapper} returns as a Java
 * {@code null} with no exception thrown at all, the exact gap {@code FileChannelHandler}'s own
 * javadoc measured — is checked for explicitly rather than dereferenced.
 */
public final class FrameRouter {

  /**
   * Deliberately as lenient as {@code FileChannelHandler}'s own mapper — see {@code EnvelopeTest}'s
   * "the mapper is built here to match a channel's" — because the client and the server ship
   * separately and must tolerate the other side adding a field neither this build nor this frame's
   * own {@link FrameHandler} needs to know about. This is a property of every frame reaching this
   * router, not a per-channel choice, so unlike the survey's note about a channel's own leniency
   * setting, one mapper here is enough.
   */
  private static final ObjectMapper JSON =
      FrameJson.reading()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private final Map<String, FrameHandler> handlers;
  private final ActingRequests acting;
  private final FailureReporter failures;
  private final ReadAdmission readAdmission;
  private final ExternalConversationRequestWork externalWork;

  /**
   * @param handlers every type this router knows, keyed by the dotted discriminator a frame's
   *     {@code type} names. Copied defensively; mutating the map handed in afterwards has no effect
   * @throws IllegalArgumentException if any key is not a dotted discriminator, per {@link
   *     FrameTypes#requireWellFormed(String)}
   * @throws NullPointerException if {@code handlers} is null, or holds a null key or a null handler
   */
  public FrameRouter(Map<String, FrameHandler> handlers) {
    this(handlers, null);
  }

  public FrameRouter(Map<String, FrameHandler> handlers, ActingRequests acting) {
    this(handlers, acting, ReadAdmission.direct());
  }

  public FrameRouter(
      Map<String, FrameHandler> handlers, ActingRequests acting, ReadAdmission admission) {
    this(handlers, acting, admission, FailureReporter.NONE);
  }

  public FrameRouter(
      Map<String, FrameHandler> handlers,
      ActingRequests acting,
      ReadAdmission admission,
      FailureReporter failures) {
    this(handlers, acting, admission, failures, ExternalConversationRequestWork.UNAVAILABLE);
  }

  public FrameRouter(
      Map<String, FrameHandler> handlers,
      ActingRequests acting,
      ReadAdmission admission,
      FailureReporter failures,
      ExternalConversationRequestWork externalWork) {
    this.externalWork = Objects.requireNonNull(externalWork);
    this.failures = Objects.requireNonNull(failures);
    this.readAdmission = Objects.requireNonNull(admission);
    this.acting = acting;
    handlers.keySet().forEach(FrameTypes::requireWellFormed);
    this.handlers = Map.copyOf(handlers);
  }

  QueuedExternalConversationRequest externalRequest(
      long admittedAtNanos,
      Asking asking,
      RequestSessionValidity session,
      BiConsumer<Outcome, Runnable> reply) {
    return QueuedExternalConversationRequest.begin(
        externalWork, admittedAtNanos, asking, session, reply);
  }

  <T> T externalWithin(Supplier<T> action) {
    return externalWork.within(action);
  }

  /** Output runs on its existing writer thread and has a separate bounded disclosure phase. */
  void requireExternalDisclosure(String frame, Asking asking, Outcome outcome) {
    try {
      externalWork.within(
          () -> {
            requireDisclosure(frame, asking, outcome);
            return null;
          });
    } catch (ArchiveUnavailableException unavailable) {
      // Includes the queued session fence, not only target projection: earlier work may be
      // committed.
      throw ExternalConversationUnavailableException.preserveUncertainty(unavailable);
    }
  }

  private static boolean externalSuccess(String operation, Outcome answer) {
    return ExternalConversationRequestWork.external(operation)
        && answer.payload() != null
        && answer.code().httpStatus() >= 200
        && answer.code().httpStatus() < 300;
  }

  private void requireExternalTarget(String operation, Asking asking, Outcome answer) {
    String conversation;
    if (FrameTypes.CONVERSATION_OPEN_EX.equals(operation)
        && answer.payload() instanceof ConversationView opened) conversation = opened.id();
    else if (FrameTypes.AGENT_RUN_EX.equals(operation)
        && answer.payload() instanceof StartedJob started) conversation = started.conversation();
    else throw new CallerFault("External conversation reply has no valid target");
    if (conversation == null) throw new CallerFault("External conversation reply has no target");
    if (authorization == null) return; // Isolated routers; production requires this collaborator.
    Home current =
        authorization.requireExternalConversationTarget(operation, conversation, asking.handle());
    // Reuse the owning resource's narrow current scope. Passing the conversation again to Personal
    // would discard/refetch a full row and attach its durable allowance callback unnecessarily.
    if (personalAccess != null)
      personalAccess.check(
          new PersonalScope(List.of(), List.of(current.project()), List.of()),
          asking.handle(),
          asking.sessionId());
    else {
      PersonalSpaces.requireOwn(current.project(), asking.handle());
      ClientProjects.requireOwn(current.project(), asking.sessionId(), asking.handle());
    }
  }

  /**
   * Every type this router claims — the surface a client can actually reach, as opposed to every
   * constant {@link FrameTypes} happens to name.
   *
   * <p>Exists so that the spec §3.7 parity check can be held against the real table rather than
   * against a second list written down for the purpose, which is the defect {@code ParityTest}'s
   * own javadoc warns about: a fixture "would pass on the day a tool was deleted". Nothing in
   * {@code src/main} calls this, and it is not a hole in the routing path — a dispatcher that
   * offered a way to enumerate handlers <em>to a caller</em> would be the beginning of the
   * dynamic-dispatch shape this class's header argues against. It is a read of a map that is
   * already immutable.
   *
   * @return the registered types, unmodifiable
   */
  public Set<String> types() {
    return handlers.keySet();
  }

  /**
   * Routes an already-parsed, already-valid envelope to whichever {@link FrameHandler} claims
   * {@link Envelope#type()}.
   *
   * <p><b>What this catches is every {@link RuntimeException} and not every {@link Throwable}</b>,
   * so the promise is "no frame a client sends provokes an exception that costs it the socket" and
   * not a promise about an {@link Error}. An {@code OutOfMemoryError} or a {@code
   * StackOverflowError} raised under a handler still travels out to Spring's {@code
   * ExceptionWebSocketHandlerDecorator} and closes the session — deliberately, since the
   * alternative is answering a frame with a 500 from inside a JVM that has just said it cannot
   * continue, on a thread whose stack may be unusable, and then going on to accept the next frame.
   * Widening the catch would buy a worse failure, not a narrower one.
   *
   * <p>Two of the four failures this class must cover happen here: no handler is registered for
   * {@code request.type()} ({@link Code#NOT_FOUND}, naming the type — see the class javadoc for why
   * this one answer is not routed through {@link Faults}), and the handler that is registered
   * throws (whatever {@link Faults#of(Throwable)} says for it, never the exception's own stack
   * trace — {@code Faults}'s catch-all builds a sentence naming the exception's simple name and
   * message, not {@code Throwable.printStackTrace()}).
   *
   * @param request a frame whose {@link Envelope#protocolVersion()} has already been checked — its
   *     own compact constructor refuses to exist otherwise, so there is nothing left for this
   *     method to re-check
   * @param asking which session is asking; a handler that needs a project reads it out of the
   *     payload, per {@link Asking}
   * @return what the handler answered, or a failure {@link Outcome} if no handler claims this type
   *     or the one that does threw
   */
  private io.aeyer.plowshare.server.access.ProjectAuthorization authorization;

  @org.springframework.beans.factory.annotation.Autowired
  public void useAuthorization(
      io.aeyer.plowshare.server.access.ProjectAuthorization authorization) {
    this.authorization = authorization;
  }

  private io.aeyer.plowshare.server.auth.AdminStore accounts;

  @org.springframework.beans.factory.annotation.Autowired
  public void useAccounts(io.aeyer.plowshare.server.auth.AdminStore accounts) {
    this.accounts = accounts;
  }

  private io.aeyer.plowshare.server.personal.PersonalAccess personalAccess;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void usePersonalAccess(io.aeyer.plowshare.server.personal.PersonalAccess access) {
    this.personalAccess = access;
  }

  public Outcome route(Envelope request, Asking asking) {
    try {
      return routeAsync(request, asking, false).toCompletableFuture().join();
    } catch (CompletionException failure) {
      if (failure.getCause() instanceof Error fatal) throw fatal;
      throw failure;
    }
  }

  /**
   * Complete routing, including disclosure and failure mapping, before handing a reply to output.
   */
  public CompletionStage<Outcome> routeAsync(Envelope request, Asking asking) {
    return routeAsync(request, asking, true);
  }

  private CompletionStage<Outcome> routeAsync(
      Envelope request, Asking asking, boolean disclosureFence) {
    FrameHandler handler = handlers.get(request.type());
    if (handler == null) {
      return CompletableFuture.completedFuture(
          Outcome.failed(
              Code.NOT_FOUND, "no handler is registered for type \"" + request.type() + "\""));
    }
    if (ExternalConversationRequestWork.external(request.type())) {
      try {
        // Start before common project/session/Personal SQL, not only at the facade handler. Ex
        // handlers are synchronous; no ThreadLocal SQL or authority context escapes this action.
        return externalWork.within(() -> routeAdmitted(request, asking, disclosureFence));
      } catch (RuntimeException unavailable) {
        Fault fault = observedFault(request.type(), unavailable);
        return CompletableFuture.completedFuture(Outcome.failed(fault.code(), fault.detail()));
      }
    }
    boolean observer = request.type().equals("information.await");
    if (!DispatchPolicy.independentRead(request.type()) && !observer)
      return routeAdmitted(request, asking, disclosureFence);
    ReadAdmission.Lease lease;
    try {
      lease =
          readAdmission.admit(
              asking.handle(),
              observer ? ReadAdmission.Work.OBSERVER : ReadAdmission.Work.INTERACTIVE);
    } catch (io.aeyer.plowshare.server.reads.ReadUnavailableException busy) {
      Fault fault = observedFault(busy);
      return CompletableFuture.completedFuture(Outcome.failed(fault.code(), fault.detail()));
    }
    try {
      return routeAdmitted(request, asking, disclosureFence)
          .whenComplete((answer, failure) -> lease.close());
    } catch (RuntimeException | Error failed) {
      lease.close();
      throw failed;
    }
  }

  private CompletionStage<Outcome> routeAdmitted(
      Envelope request, Asking asking, boolean disclosureFence) {
    FrameHandler handler = handlers.get(request.type());
    ExternalAdmission admission = null;
    try {
      if (request.actingFor() != null) {
        if (acting == null) throw new CallerFault("Acting request admission is unavailable");
        return CompletableFuture.completedFuture(
            acting.route(request.type(), request.payload(), request.actingFor(), asking));
      }
      if (authorization != null
          && !Set.of(FrameTypes.ACTING_CONTEXT_OPEN, FrameTypes.ACTING_CONTEXT_CLOSE)
              .contains(request.type())) {
        if (ExternalConversationRequestWork.external(request.type()))
          admission =
              authorization.requireExternalConversation(
                  request.type(),
                  AccessRequestDecoder.decode(request.type(), request.payload()),
                  asking.handle(),
                  asking.sessionId());
        else
          authorization.require(
              request.type(),
              AccessRequestDecoder.decode(request.type(), request.payload()),
              asking.handle());
      }
      if (accounts != null && serverAdministration(request.type()))
        accounts.requireServerAdmin(asking.handle());
      // A worker facade validates its binding and every nested project/topic before broker use.
      // Running the ordinary Personal fence would require giving that worker the owner's
      // membership.
      // PROC selectors narrow metadata, not content access. Its repository rechecks custody,
      // membership or explicit fleet authority, including when an administrator narrows by project.
      if (!io.aeyer.plowshare.protocol.HostedToolScopes.WORKER_OPERATIONS.contains(request.type())
          && !ProcFrames.OPERATIONS.contains(request.type())) {
        var scope =
            io.aeyer.plowshare.server.personal.PersonalScopeDecoder.decode(request.payload());
        for (String project : scope.clientProjects())
          if (personalAccess != null)
            personalAccess.requireClientProject(project, asking.sessionId(), asking.handle());
          else
            io.aeyer.plowshare.server.archive.ClientProjects.requireOwn(
                project, asking.sessionId(), asking.handle());
        for (String project : scope.projects())
          io.aeyer.plowshare.server.personal.PersonalSpaces.requireOwn(project, asking.handle());
        if (personalAccess != null)
          personalAccess.check(scope, asking.handle(), asking.sessionId());
      }
      Asking admitted = admission == null ? asking : asking.withExternalAdmission(admission);
      var completion =
          disclosureFence && !ExternalConversationRequestWork.external(request.type())
              ? handler.handleAsync(request.payload(), asking)
              : CompletableFuture.completedFuture(handler.handle(request.payload(), admitted));
      return Objects.requireNonNull(completion)
          .thenApply(
              answer -> {
                asking.queryValidity().run();
                Objects.requireNonNull(answer, "Handler returned no outcome");
                // A genuinely asynchronous handler may finish after a membership change. This is a
                // disclosure freshness check, not another admission or a cached permission grant.
                if (externalSuccess(request.type(), answer))
                  requireExternalTarget(request.type(), asking, answer);
                else if (disclosureFence
                    && authorization != null
                    && !Set.of(FrameTypes.ACTING_CONTEXT_OPEN, FrameTypes.ACTING_CONTEXT_CLOSE)
                        .contains(request.type()))
                  authorization.require(
                      request.type(),
                      io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
                          request.type(), request.payload()),
                      asking.handle());
                return authorization == null
                    ? answer
                    : new ProjectListingFilter(authorization)
                        .filter(
                            request.type(),
                            answer,
                            asking.handle(),
                            request.payload().get("project") instanceof String selected
                                ? selected
                                : null);
              })
          .handle(
              (answer, failure) -> {
                if (failure == null) return answer;
                Throwable cause = failure;
                while (cause instanceof CompletionException && cause.getCause() != null)
                  cause = cause.getCause();
                if (cause instanceof Error fatal) throw fatal;
                Fault fault = observedFault(request.type(), cause);
                return Outcome.failed(fault.code(), fault.detail());
              });
    } catch (RuntimeException thrown) {
      Fault fault = observedFault(request.type(), thrown);
      return CompletableFuture.completedFuture(Outcome.failed(fault.code(), fault.detail()));
    } finally {
      if (admission != null) admission.close();
    }
  }

  private Fault observedFault(Throwable failure) {
    return observedFault(null, failure);
  }

  private Fault observedFault(String operation, Throwable failure) {
    Throwable mapped =
        ExternalConversationRequestWork.external(operation)
                && failure instanceof ArchiveUnavailableException unavailable
            ? ExternalConversationUnavailableException.preserveUncertainty(unavailable)
            : failure;
    Fault fault = Faults.of(mapped);
    if (fault.httpStatus() >= 500) failures.report(FailureReporter.Source.WEBSOCKET, failure);
    return fault;
  }

  /** Release temporary subject contexts on physical connection teardown. */
  public void disconnected(String connection) {
    if (acting != null) acting.disconnected(connection);
  }

  /**
   * Queued-output authority check, not handler replay. If the retained listing would now be
   * filtered differently, close its generation instead of emitting already-encoded protected data.
   */
  public void requireDisclosure(String frame, Asking asking, Outcome answer) {
    asking.queryValidity().run();
    if (answer.payload() == null) return;
    try {
      Envelope request = JSON.readValue(frame, Envelope.class);
      if (request.actingFor() != null) {
        if (acting == null) throw new CallerFault("Acting authority unavailable");
        acting.requireDisclosure(request, asking, answer);
        return;
      }
      if (externalSuccess(request.type(), answer)) {
        requireExternalTarget(request.type(), asking, answer);
        return;
      }
      if (accounts != null && serverAdministration(request.type()))
        accounts.requireServerAdmin(asking.handle());
      if (authorization != null
          && !Set.of(FrameTypes.ACTING_CONTEXT_OPEN, FrameTypes.ACTING_CONTEXT_CLOSE)
              .contains(request.type()))
        authorization.require(
            request.type(),
            io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
                request.type(), request.payload()),
            asking.handle());
      if (personalAccess != null
          && !io.aeyer.plowshare.protocol.HostedToolScopes.WORKER_OPERATIONS.contains(
              request.type())
          && !ProcFrames.OPERATIONS.contains(request.type()))
        personalAccess.check(
            io.aeyer.plowshare.server.personal.PersonalScopeDecoder.decode(request.payload()),
            asking.handle(),
            asking.sessionId());
      if (authorization != null) {
        Outcome filtered =
            new ProjectListingFilter(authorization)
                .filter(
                    request.type(),
                    answer,
                    asking.handle(),
                    request.payload().get("project") instanceof String selected ? selected : null);
        if (!answer.equals(filtered)) throw new CallerFault("Reply authority changed while queued");
      }
    } catch (IOException invalid) {
      throw new CallerFault("Invalid reply authority context", invalid);
    }
  }

  private static boolean serverAdministration(String type) {
    return type.startsWith("admin.") && !type.equals("admin.status")
        || type.startsWith("project.")
            && !type.equals("project.list")
            && !type.equals("project.attach")
            && !type.equals("project.access")
            && !type.startsWith("project.member.")
        || type.equals("provider.deregister");
  }

  /**
   * Parses {@code rawFrame} into an {@link Envelope} first, so the two failures a handler never
   * gets a chance to cause — a frame that will not parse at all, and one naming the wrong {@code
   * protocol_version} — are answered here rather than as an exception escaping {@link
   * #route(Envelope, Asking)}. This is the entry point a channel handler calls with the raw text a
   * socket handed it; see the class javadoc's "a malformed frame does not take the session down"
   * section for the double guard this matches.
   *
   * @param rawFrame the frame's raw JSON text, exactly as read off the wire
   * @param asking which session is asking
   * @return what the handler answered, a caller-fault {@link Outcome} if {@code rawFrame} would not
   *     parse or named the wrong {@code protocol_version}, or whatever {@link #route(Envelope,
   *     Asking)} would answer for the parsed envelope
   */
  public Outcome route(String rawFrame, Asking asking) {
    try {
      return routeAsync(rawFrame, asking, false).toCompletableFuture().join();
    } catch (CompletionException failure) {
      if (failure.getCause() instanceof Error fatal) throw fatal;
      throw failure;
    }
  }

  public CompletionStage<Outcome> routeAsync(String rawFrame, Asking asking) {
    return routeAsync(rawFrame, asking, true);
  }

  private CompletionStage<Outcome> routeAsync(
      String rawFrame, Asking asking, boolean disclosureFence) {
    Envelope request;
    try {
      var body = JSON.readTree(rawFrame);
      if (body != null && body.has("actingFor")) {
        var selection = body.get("actingFor");
        if (!selection.isObject()
            || selection.size() != 1
            || !selection.path("context").isTextual())
          throw new CallerFault("Specify exactly actingFor.context");
        ActingFrames.uuid(selection.get("context").textValue());
      }
      request = JSON.treeToValue(body, Envelope.class);
    } catch (IOException notAFrame) {
      return CompletableFuture.completedFuture(refuse(notAFrame));
    } catch (RuntimeException invalidSelection) {
      var fault = observedFault(invalidSelection);
      return CompletableFuture.completedFuture(Outcome.failed(fault.code(), fault.detail()));
    }
    if (request == null) {
      // Measured the same way FileChannelHandler measured it for its own
      // reply type on Jackson 2.17: readValue("null", Envelope.class)
      // returns a Java null with no exception thrown at all. The catch
      // above cannot see this; this is the second half of the guard.
      return CompletableFuture.completedFuture(refuse(null));
    }
    return routeAsync(request, asking, disclosureFence);
  }

  /**
   * Builds the caller-fault {@link Outcome} for a frame that never became an {@link Envelope} —
   * either {@code cause} is the parse failure ({@code null} for the JSON-literal-{@code null} case
   * {@link #route(String, Asking)} checks explicitly), or, when {@code cause}'s root cause is the
   * {@link IllegalArgumentException} {@link Envelope}'s own compact constructor throws for a
   * mismatched {@code protocol_version}, that exception's own message — which already names both
   * {@link Envelope#CURRENT_VERSION} and the version the frame proposed — is reused rather than
   * restated.
   */
  private Outcome refuse(IOException cause) {
    Throwable rootCause = cause == null ? null : cause.getCause();
    CallerFault fault =
        rootCause instanceof IllegalArgumentException versionMismatch
            ? new CallerFault(versionMismatch.getMessage(), versionMismatch)
            : new CallerFault(describe(cause), cause);
    Fault mapped = observedFault(fault);
    return Outcome.failed(mapped.code(), mapped.detail());
  }

  private static String describe(IOException cause) {
    if (cause == null) {
      return "that frame parsed to nothing rather than to an envelope — the JSON literal"
          + " \"null\", or an empty body";
    }
    // Parser diagnostics can quote malformed credential tokens. A transport refusal must never
    // echo proof material into a caller's diagnostic stream.
    return "this server could not read that frame as " + Envelope.CURRENT_VERSION;
  }
}
