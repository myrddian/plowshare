package io.aeyer.plowshare.server.applications;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.protocol.ApplicationIdentity.Declaration;
import java.io.IOException;
import java.util.Optional;

/**
 * Strict manifest boundary; source transformation changes only the resolved execution principal.
 */
public final class ApplicationServiceManifest {
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      JsonMapper.builder()
          .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private ApplicationServiceManifest() {}

  public static Optional<Declaration> declaration(String source) {
    try {
      var body = JSON.readTree(source);
      if (body == null || !body.isObject())
        throw new IllegalArgumentException("Invalid Application manifest");
      var service = body.get("service");
      if (service == null) return Optional.empty();
      if (!service.isObject()
          || service.size() != 2
          || !service.has("handle")
          || !service.get("handle").isTextual()
          || !service.has("role")
          || !service.get("role").isTextual())
        throw new IllegalArgumentException("service needs exactly handle and role");
      return Optional.of(
          new Declaration(service.get("handle").textValue(), service.get("role").textValue()));
    } catch (IOException invalid) {
      throw new IllegalArgumentException("Invalid Application service declaration", invalid);
    }
  }

  public static String resolve(String source, String principal) {
    declaration(source).orElseThrow();
    try {
      ObjectNode body = (ObjectNode) JSON.readTree(source);
      var prior = body.get("executionAccount");
      if (prior != null && (!prior.isTextual() || !principal.equals(prior.textValue())))
        throw new IllegalArgumentException(
            "Application executionAccount conflicts with its owned identity");
      body.put("executionAccount", principal);
      return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(body) + "\n";
    } catch (IOException invalid) {
      throw new IllegalArgumentException("Invalid Application service manifest", invalid);
    }
  }
}
