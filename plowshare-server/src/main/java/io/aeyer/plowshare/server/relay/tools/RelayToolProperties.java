package io.aeyer.plowshare.server.relay.tools;

import io.aeyer.plowshare.server.relay.RelayPortProperties;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit deployment authority. SDK declarations are installed by an operator, never a model. */
@ConfigurationProperties(value = "plowshare.relay.tools", ignoreUnknownFields = false)
public final class RelayToolProperties implements RelayToolAuthority {
  private List<RelayToolDefinition> bindings = List.of();

  public List<RelayToolDefinition> getBindings() {
    return bindings;
  }

  public void setBindings(List<RelayToolDefinition> value) {
    var checked = List.copyOf(value);
    for (var binding : checked)
      if (RelayToolCodec.write(binding).length() > 65536)
        throw new IllegalArgumentException("Tool declaration exceeds stored bound");
    if (checked.size() > 256) throw new IllegalArgumentException("Too many Relay tools");
    for (int i = 0; i < checked.size(); i++)
      for (int j = 0; j < i; j++) {
        var a = checked.get(i);
        var b = checked.get(j);
        if (a.project().equals(b.project()) && a.name().equals(b.name()))
          throw new IllegalArgumentException("Duplicate project tool binding");
        if (a.name().equals(b.name()) && !a.schema().equals(b.schema()))
          throw new IllegalArgumentException(
              "A tool name must have the same schema across projects");
      }
    bindings = checked;
  }

  /** Tool-result authority cannot be used to create an unrelated independent Relay root. */
  public void validateResult(
      String account, io.aeyer.plowshare.protocol.RelayPort.Publish request) {
    var binding =
        bindings.stream()
            .filter(
                b ->
                    b.account().equals(account)
                        && b.project().equals(request.project())
                        && b.results().equals(request.topic()))
            .findFirst();
    if (binding.isEmpty()) return;
    var b = binding.get();
    var result = RelayToolCodec.result(request.text());
    var invocation = java.util.UUID.fromString(result.invocationId());
    if (!b.requests().equals(request.parentTopic())
        || !result.invocationId().equals(request.parentEventId())
        || !result.invocationId().equals(request.correlationId())
        || !RelayToolCodec.resultRequestId(invocation).toString().equals(request.requestId())
        || !result.project().equals(b.project())
        || !result.provider().equals(b.provider())
        || !result.tool().equals(b.name()))
      throw new io.aeyer.plowshare.server.faults.CallerFault(
          "Tool result must identify its bound invocation and parent");
  }

  /** A binding grants its provider only this tool's request egress and result ingress. */
  public boolean permits(
      String account,
      String project,
      String topic,
      RelayPortProperties.Direction direction,
      String group) {
    return bindings.stream()
        .anyMatch(
            b ->
                b.account().equals(account)
                    && b.project().equals(project)
                    && (direction == RelayPortProperties.Direction.INGRESS
                            && b.results().equals(topic)
                        || direction == RelayPortProperties.Direction.EGRESS
                            && b.requests().equals(topic)
                            && (group == null || RelayToolDefinition.group().equals(group))));
  }
}
