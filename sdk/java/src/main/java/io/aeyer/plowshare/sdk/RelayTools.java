package io.aeyer.plowshare.sdk;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.IntegrationPayload;
import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Named external tools over public Relay operations. Declarations never grant agent authority. */
public final class RelayTools {
  private RelayTools() {}

  private static final String VERSION = "plowshare-tool/1";
  private static final String GROUP = "tool-provider";
  private static final ObjectMapper JSON =
      SdkJson.mapper()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  public enum Type {
    STRING,
    NUMBER,
    INTEGER,
    BOOLEAN
  }

  public enum State {
    COMPLETED,
    REJECTED,
    UNKNOWN
  }

  public enum Phase {
    EXECUTING,
    READY,
    PUBLISHING,
    DONE
  }

  public record Parameter(String name, Type type, String description, boolean required) {
    public Parameter {
      if (name == null || !name.matches("[a-zA-Z_][a-zA-Z0-9_]{0,63}"))
        throw new IllegalArgumentException("Invalid parameter name");
      Objects.requireNonNull(type);
      text(description, 4096, false);
    }
  }

  public record Declaration(
      String name, String description, List<Parameter> parameters, int timeoutSeconds) {
    public Declaration {
      toolName(name);
      text(description, 4096, true);
      parameters = List.copyOf(parameters);
      if (parameters.size() > 32
          || parameters.stream().map(Parameter::name).distinct().count() != parameters.size()
          || timeoutSeconds < 1
          || timeoutSeconds > 300) throw new IllegalArgumentException("Invalid tool declaration");
    }

    public void validate(Map<String, IntegrationPayload.Parameter> arguments) {
      Objects.requireNonNull(arguments);
      for (var key : arguments.keySet())
        if (parameters.stream().noneMatch(p -> p.name().equals(key)))
          throw new IllegalArgumentException("Unknown tool argument");
      for (var parameter : parameters) {
        var value = arguments.get(parameter.name());
        if (value == null) {
          if (parameter.required())
            throw new IllegalArgumentException("Required tool argument missing");
          continue;
        }
        if (value instanceof IntegrationPayload.NumberParameter number)
          validateNumber(number.value());
        boolean valid =
            switch (parameter.type()) {
              case STRING -> value instanceof IntegrationPayload.TextParameter;
              case BOOLEAN -> value instanceof IntegrationPayload.BooleanParameter;
              case NUMBER -> value instanceof IntegrationPayload.NumberParameter;
              case INTEGER ->
                  value instanceof IntegrationPayload.NumberParameter number
                      && number.value().stripTrailingZeros().scale() <= 0;
            };
        if (!valid) throw new IllegalArgumentException("Tool argument type mismatch");
      }
    }
  }

  public record Binding(String project, String provider, String account) {
    public Binding {
      RelayPort.identity(project);
      RelayPort.identity(account);
      providerName(provider);
    }
  }

  /** Immutable, validated invocation context. Its UUID is suitable for downstream deduplication. */
  public record Call(
      String schema,
      String invocationId,
      String project,
      String provider,
      String tool,
      String account,
      String run,
      String call,
      String deadline,
      Map<String, IntegrationPayload.Parameter> arguments) {
    public Call {
      if (!VERSION.equals(schema))
        throw new IllegalArgumentException("Invalid tool envelope version");
      RelayPort.uuid(invocationId);
      RelayPort.identity(project);
      providerName(provider);
      toolName(tool);
      RelayPort.identity(account);
      RelayPort.identity(run);
      RelayPort.identity(call);
      instant(deadline);
      arguments = Map.copyOf(arguments);
      if (arguments.size() > 32) throw new IllegalArgumentException("Too many tool arguments");
      for (var entry : arguments.entrySet()) {
        if (!entry.getKey().matches("[a-zA-Z_][a-zA-Z0-9_]{0,63}"))
          throw new IllegalArgumentException("Invalid tool argument name");
        if (entry.getValue() instanceof IntegrationPayload.NumberParameter number)
          validateNumber(number.value());
      }
    }
  }

  public record Result(State state, String text) {
    public Result {
      Objects.requireNonNull(state);
      RelayTools.text(text, 16384, true);
    }
  }

  /** Handler completion is bounded. A timeout cannot promise that external effects stopped. */
  @FunctionalInterface
  public interface Handler {
    CompletionStage<Result> execute(Call call) throws Exception;
  }

  public record RegisteredTool(Declaration declaration, Handler handler) {
    public RegisteredTool {
      Objects.requireNonNull(declaration);
      Objects.requireNonNull(handler);
    }
  }

  public record Receipt(
      String invocationId,
      String request,
      Phase phase,
      Optional<Result> result,
      Optional<String> occurredAt) {
    public Receipt {
      RelayPort.uuid(invocationId);
      Objects.requireNonNull(request);
      Objects.requireNonNull(phase);
      Objects.requireNonNull(result);
      Objects.requireNonNull(occurredAt);
      if ((phase == Phase.EXECUTING) != result.isEmpty()
          || (phase == Phase.PUBLISHING || phase == Phase.DONE) != occurredAt.isPresent())
        throw new IllegalArgumentException("Invalid tool receipt phase");
      occurredAt.ifPresent(RelayTools::instant);
    }
  }

  /** Exclusive provider-owned persistence; save must commit durably before it returns. */
  public interface Journal {
    String identity();

    List<Receipt> all() throws IOException;

    void save(Receipt receipt) throws IOException;
  }

  /** Narrow SDK-only transport contract for composition and deterministic behavior tests. */
  public interface Ports {
    RelayPort.Batch consume(RelayPort.Consume request) throws IOException;

    RelayPort.Acknowledged acknowledge(RelayPort.Ack request) throws IOException;

    RelayPort.Published publish(RelayPort.Publish request) throws IOException;

    RelayLog.Page log(RelayLog.Query request) throws IOException;
  }

  public static Ports ports(RelayClient relay) {
    Objects.requireNonNull(relay);
    return new Ports() {
      public RelayPort.Batch consume(RelayPort.Consume request) throws IOException {
        return relay.consume(request);
      }

      public RelayPort.Acknowledged acknowledge(RelayPort.Ack request) throws IOException {
        return relay.acknowledge(request);
      }

      public RelayPort.Published publish(RelayPort.Publish request) throws IOException {
        return relay.publish(request);
      }

      public RelayLog.Page log(RelayLog.Query request) throws IOException {
        return relay.log(request);
      }
    };
  }

  private record Installed(
      String project,
      String provider,
      String account,
      String name,
      String description,
      List<Parameter> parameters,
      int timeoutSeconds) {}

  private record Configuration(PlowshareConfiguration plowshare) {}

  private record PlowshareConfiguration(RelayConfiguration relay) {}

  private record RelayConfiguration(ToolConfiguration tools) {}

  private record ToolConfiguration(List<Installed> bindings) {}

  private record ResultEnvelope(
      String schema,
      String invocationId,
      String project,
      String provider,
      String tool,
      State state,
      String text) {}

  /** Export operator-installed server bindings. Install these separately from agent tool grants. */
  public static String deploymentConfig(Binding binding, List<Declaration> tools) {
    Objects.requireNonNull(binding);
    if (tools.isEmpty()
        || tools.size() > 256
        || tools.stream().map(Declaration::name).distinct().count() != tools.size())
      throw new IllegalArgumentException("Invalid provider declarations");
    return write(
        new Configuration(
            new PlowshareConfiguration(
                new RelayConfiguration(
                    new ToolConfiguration(
                        tools.stream()
                            .map(
                                tool ->
                                    new Installed(
                                        binding.project(),
                                        binding.provider(),
                                        binding.account(),
                                        tool.name(),
                                        tool.description(),
                                        tool.parameters(),
                                        tool.timeoutSeconds()))
                            .toList())))));
  }

  public static Call decodeCall(String source) {
    text(source, 32768, true);
    try {
      return JSON.readValue(source, Call.class);
    } catch (IOException invalid) {
      throw new IllegalArgumentException("Invalid tool request envelope", invalid);
    }
  }

  public static String resultRequestId(String invocationId) {
    RelayPort.uuid(invocationId);
    String hex = RelayPort.hash("tool-result:" + invocationId).substring(0, 32);
    return hex.substring(0, 8)
        + "-"
        + hex.substring(8, 12)
        + "-"
        + hex.substring(12, 16)
        + "-"
        + hex.substring(16, 20)
        + "-"
        + hex.substring(20);
  }

  /**
   * One serial provider pass. Interrupted intents become UNKNOWN; uncertain publication is
   * read-only reconciled.
   */
  public static final class Provider {
    private final Ports ports;
    private final Binding binding;
    private final List<RegisteredTool> tools;
    private final Journal journal;
    private final String consumer = UUID.randomUUID().toString();
    private boolean busy;

    public Provider(Ports ports, Binding binding, List<RegisteredTool> tools, Journal journal) {
      this.ports = Objects.requireNonNull(ports);
      this.binding = Objects.requireNonNull(binding);
      this.tools = List.copyOf(tools);
      this.journal = Objects.requireNonNull(journal);
      if (!RelayPort.hash(
              deploymentConfig(binding, tools.stream().map(RegisteredTool::declaration).toList()))
          .equals(journal.identity()))
        throw new IllegalArgumentException("Tool journal belongs to another configuration");
    }

    public synchronized int poll() throws IOException {
      enter();
      try {
        for (var retained : receipts()) {
          if (retained.phase() == Phase.PUBLISHING) throw attention();
          var receipt = retained;
          if (receipt.phase() == Phase.EXECUTING) {
            receipt =
                ready(
                    receipt,
                    new Result(
                        State.UNKNOWN,
                        "Provider restarted after execution intent; external outcome is unknown."));
            journal.save(receipt);
          }
          if (receipt.phase() == Phase.READY) publish(receipt);
        }
        int count = 0;
        for (var registered : tools) {
          var declaration = registered.declaration();
          var request =
              new RelayPort.Consume(
                  binding.project(),
                  requests(declaration.name()),
                  GROUP,
                  consumer,
                  RelayPort.Start.OLDEST_RETAINED,
                  1,
                  0);
          var batch = ports.consume(request);
          if (!batch.project().equals(request.project())
              || !batch.topic().equals(request.topic())
              || !batch.group().equals(GROUP)
              || !batch.consumerId().equals(consumer)
              || batch.events().size() > 1) throw new IOException("Foreign tool batch");
          if (batch.status() == RelayPort.Status.GAP)
            throw new IOException("Tool request retention gap requires operator inspection");
          if (batch.status() != RelayPort.Status.DATA) continue;
          var event = batch.events().getFirst();
          if (!event.publisher().equals("tool-runtime") || !event.payload().kind().equals("TEXT"))
            throw new IOException("Invalid tool request publisher or payload");
          Call call = bound(event.payload().text());
          if (!call.tool().equals(declaration.name())
              || !event.eventId().equals(call.invocationId())
              || !Objects.equals(event.correlationId(), call.invocationId()))
            throw new IOException("Foreign tool request identity");
          declaration.validate(call.arguments());
          Optional<Receipt> existing =
              receipts().stream()
                  .filter(r -> r.invocationId().equals(call.invocationId()))
                  .findFirst();
          var receipt =
              existing.orElseGet(
                  () ->
                      new Receipt(
                          call.invocationId(),
                          event.payload().text(),
                          Phase.EXECUTING,
                          Optional.empty(),
                          Optional.empty()));
          if (!receipt.request().equals(event.payload().text()))
            throw new IOException("Conflicting tool invocation");
          if (existing.isEmpty()) journal.save(receipt);
          var ack =
              ports.acknowledge(
                  new RelayPort.Ack(
                      binding.project(),
                      request.topic(),
                      GROUP,
                      consumer,
                      batch.batchId(),
                      batch.fence(),
                      null));
          if (!ack.project().equals(binding.project())
              || !ack.topic().equals(request.topic())
              || !ack.group().equals(GROUP)
              || !ack.batchId().equals(batch.batchId())
              || ack.gap()) throw new IOException("Foreign tool acknowledgement");
          if (existing.isEmpty()) {
            Result result;
            long wait =
                Math.min(
                    ChronoUnit.MILLIS.between(Instant.now(), instant(call.deadline())),
                    declaration.timeoutSeconds() * 1000L);
            if (wait <= 0)
              result =
                  new Result(State.REJECTED, "Tool request deadline expired before execution.");
            else {
              try {
                result =
                    Objects.requireNonNull(registered.handler().execute(call))
                        .toCompletableFuture()
                        .get(wait, TimeUnit.MILLISECONDS);
                resultText(call, result);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(
                    "Tool handler interrupted; retained execution requires reconciliation",
                    interrupted);
              } catch (ExecutionException | TimeoutException failure) {
                result =
                    new Result(
                        State.UNKNOWN,
                        "Tool handler did not confirm an outcome; do not repeat external effects.");
              } catch (Exception failure) {
                result =
                    new Result(
                        State.UNKNOWN,
                        "Tool handler did not confirm an outcome; do not repeat external effects.");
              }
            }
            receipt = ready(receipt, result);
            journal.save(receipt);
            publish(receipt);
          }
          count++;
        }
        return count;
      } finally {
        busy = false;
      }
    }

    /** Inspects retained result events only. A missing event never authorizes republishing. */
    public synchronized int reconcile() throws IOException {
      enter();
      try {
        int count = 0;
        for (var receipt : receipts()) {
          if (receipt.phase() != Phase.PUBLISHING) continue;
          var call = bound(receipt.request());
          String after = "0";
          boolean finished = false;
          for (int pageNumber = 0; pageNumber < 100; pageNumber++) {
            var query =
                new RelayLog.Query(binding.project(), false, results(call.tool()), after, 100);
            var page = ports.log(query);
            if (!page.scope().project().equals(binding.project())
                || page.scope().system()
                || !page.topic().name().equals(query.topic())
                || !page.after().equals(after)) throw new IOException("Foreign tool result log");
            for (var event : page.events()) {
              if (!event.eventId().equals(resultRequestId(call.invocationId()))) continue;
              if (!event.publisher().equals(RelayPort.publisher(binding.account()))
                  || !Objects.equals(event.correlationId(), call.invocationId())
                  || !Objects.equals(event.causationId(), call.invocationId())
                  || !event.payload().kind().equals("TEXT")
                  || !event
                      .payload()
                      .text()
                      .equals(resultText(call, receipt.result().orElseThrow()))
                  || !event.occurredAt().equals(instant(receipt.occurredAt().orElseThrow())))
                throw new IOException("Conflicting tool result event");
              journal.save(
                  new Receipt(
                      receipt.invocationId(),
                      receipt.request(),
                      Phase.DONE,
                      receipt.result(),
                      receipt.occurredAt()));
              count++;
              finished = true;
              break;
            }
            if (finished || page.next().equals(page.topic().through())) {
              finished = true;
              break;
            }
            after = page.next();
          }
          if (!finished)
            throw new IOException(
                "Tool reconciliation exceeds bounded inspection; retain receipts");
        }
        return count;
      } finally {
        busy = false;
      }
    }

    private List<Receipt> receipts() throws IOException {
      var receipts = List.copyOf(journal.all());
      if (receipts.size() > 1000
          || receipts.stream().map(Receipt::invocationId).distinct().count() != receipts.size())
        throw new IOException("Invalid tool journal size or identities");
      for (var receipt : receipts) {
        var call = bound(receipt.request());
        tools.stream()
            .filter(t -> t.declaration().name().equals(call.tool()))
            .findFirst()
            .orElseThrow()
            .declaration()
            .validate(call.arguments());
        if (!call.invocationId().equals(receipt.invocationId()))
          throw new IOException("Foreign tool receipt");
        if (receipt.result().isPresent()) resultText(call, receipt.result().orElseThrow());
      }
      return receipts;
    }

    private void publish(Receipt receipt) throws IOException {
      var call = bound(receipt.request());
      String occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS).toString();
      var intent =
          new Receipt(
              receipt.invocationId(),
              receipt.request(),
              Phase.PUBLISHING,
              receipt.result(),
              Optional.of(occurredAt));
      String payload = resultText(call, receipt.result().orElseThrow());
      journal.save(intent);
      RelayPort.Published published;
      try {
        published =
            ports.publish(
                new RelayPort.Publish(
                    resultRequestId(call.invocationId()),
                    binding.project(),
                    results(call.tool()),
                    payload,
                    instant(occurredAt),
                    call.invocationId(),
                    requests(call.tool()),
                    call.invocationId()));
      } catch (Plowshare.TransportException failure) {
        if (failure.delivery() == Plowshare.Delivery.NOT_SUBMITTED) journal.save(receipt);
        throw failure;
      }
      if (!published.requestId().equals(resultRequestId(call.invocationId()))
          || !published.project().equals(binding.project())
          || !published.topic().equals(results(call.tool())))
        throw new IOException("Foreign tool publication");
      journal.save(
          new Receipt(
              intent.invocationId(),
              intent.request(),
              Phase.DONE,
              intent.result(),
              intent.occurredAt()));
    }

    private Call bound(String source) throws IOException {
      Call call;
      try {
        call = decodeCall(source);
      } catch (IllegalArgumentException invalid) {
        throw new IOException("Invalid tool request", invalid);
      }
      if (!call.project().equals(binding.project())
          || !call.provider().equals(binding.provider())
          || tools.stream().noneMatch(t -> t.declaration().name().equals(call.tool())))
        throw new IOException("Foreign tool binding");
      return call;
    }

    private String requests(String tool) {
      return "tool." + binding.provider() + "." + tool + ".request";
    }

    private String results(String tool) {
      return "tool." + binding.provider() + "." + tool + ".result";
    }

    private void enter() throws IOException {
      if (busy) throw new IOException("Tool provider pass already running");
      busy = true;
    }
  }

  private static IOException attention() {
    return new IOException(
        "Tool result publication is uncertain; use read-only reconciliation before polling");
  }

  private static Receipt ready(Receipt receipt, Result result) {
    return new Receipt(
        receipt.invocationId(),
        receipt.request(),
        Phase.READY,
        Optional.of(result),
        Optional.empty());
  }

  private static String resultText(Call call, Result result) {
    Objects.requireNonNull(result);
    String payload =
        write(
            new ResultEnvelope(
                VERSION,
                call.invocationId(),
                call.project(),
                call.provider(),
                call.tool(),
                result.state(),
                result.text()));
    text(payload, 32768, true);
    return payload;
  }

  private static String write(Object boundaryValue) {
    try {
      return JSON.writeValueAsString(boundaryValue);
    } catch (IOException invalid) {
      throw new IllegalArgumentException("Invalid tool transport value", invalid);
    }
  }

  private static void text(String value, int max, boolean nonblank) {
    if (value == null
        || value.length() > max
        || value.indexOf('\0') >= 0
        || nonblank && value.isBlank()) throw new IllegalArgumentException("Invalid tool text");
  }

  private static void providerName(String value) {
    if (value == null || value.length() > 48 || !value.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*"))
      throw new IllegalArgumentException("Invalid tool provider");
  }

  private static void toolName(String value) {
    if (value == null || value.length() > 64 || !value.matches("[a-z][a-z0-9]*(?:_[a-z0-9]+)*"))
      throw new IllegalArgumentException("Invalid tool name");
  }

  private static Instant instant(String value) {
    if (value == null || !value.endsWith("Z"))
      throw new IllegalArgumentException("Expected UTC tool timestamp");
    var instant = Instant.parse(value);
    if (instant.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
        || instant.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z")))
      throw new IllegalArgumentException("Tool timestamp outside supported range");
    return instant;
  }

  private static void validateNumber(BigDecimal value) {
    if (value.abs().compareTo(BigDecimal.valueOf(9007199254740991L)) > 0
        || value.stripTrailingZeros().scale() > 18
        || BigDecimal.valueOf(value.doubleValue()).compareTo(value) != 0)
      throw new IllegalArgumentException("Tool number exceeds portable numeric range");
  }
}
