package io.aeyer.plowshare.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import okhttp3.HttpUrl;

/** Card declarations are discovery data. They cannot change the configured endpoint or grants. */
final class AgentCards {
  private AgentCards() {}

  static void validate(JsonNode card, HttpUrl endpoint) throws IOException {
    if (card == null || !card.isObject()) throw new IOException("A2A Agent Card must be an object");
    text(card, "name");
    text(card, "description");
    text(card, "version");
    strings(card, "defaultInputModes");
    strings(card, "defaultOutputModes");
    if (!card.path("capabilities").isObject()
        || !card.path("skills").isArray()
        || !card.path("supportedInterfaces").isArray())
      throw new IOException("A2A Agent Card lacks capabilities, skills or supported interfaces");
    boolean supported = false;
    for (JsonNode entry : card.path("supportedInterfaces")) {
      text(entry, "url");
      text(entry, "protocolBinding");
      text(entry, "protocolVersion");
      if ("JSONRPC".equals(entry.path("protocolBinding").asText())
          && "1.0".equals(entry.path("protocolVersion").asText())
          && endpoint.equals(HttpUrl.parse(entry.path("url").asText()))
          && !entry.has("tenant")) supported = true;
    }
    if (!supported)
      throw new IOException(
          "Agent Card does not advertise the configured endpoint as tenant-free A2A 1.0 JSONRPC");
    for (JsonNode skill : card.path("skills")) {
      text(skill, "id");
      text(skill, "name");
      text(skill, "description");
      strings(skill, "tags");
      for (String optional : new String[] {"examples", "inputModes", "outputModes"})
        if (skill.has(optional)) strings(skill, optional);
    }
    for (String capability :
        new String[] {
          "streaming", "pushNotifications", "extendedAgentCard", "stateTransitionHistory"
        })
      if (card.path("capabilities").has(capability)
          && !card.path("capabilities").path(capability).isBoolean())
        throw new IOException("Invalid A2A Agent Card capability");
    JsonNode extensions = card.path("capabilities").path("extensions");
    if (!extensions.isMissingNode() && !extensions.isArray())
      throw new IOException("Invalid A2A Agent Card extensions");
    for (JsonNode extension : extensions) {
      if (!extension.isObject()
          || extension.has("required") && !extension.path("required").isBoolean())
        throw new IOException("Invalid A2A Agent Card extension");
      if (extension.path("required").asBoolean())
        throw new IOException("Required A2A extensions are not supported by this adapter");
    }
  }

  private static void text(JsonNode node, String field) throws IOException {
    if (!node.path(field).isTextual() || node.path(field).asText().isBlank())
      throw new IOException("A2A Agent Card requires nonblank " + field);
  }

  private static void strings(JsonNode node, String field) throws IOException {
    if (!node.path(field).isArray())
      throw new IOException("A2A Agent Card requires array " + field);
    for (JsonNode value : node.path(field))
      if (!value.isTextual() || value.asText().isBlank())
        throw new IOException("Invalid A2A Agent Card " + field);
  }
}
