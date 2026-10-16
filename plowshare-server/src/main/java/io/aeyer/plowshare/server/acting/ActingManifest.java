package io.aeyer.plowshare.server.acting;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.server.applications.ApplicationServiceManifest;
import io.aeyer.plowshare.server.archive.ProjectRole;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strict source boundary shared by deployment and live Application policy validation. */
public final class ActingManifest {
  private ActingManifest() {}

  /**
   * An absent declaration means no acting authority; malformed declarations never degrade to it.
   */
  public static List<ActingPolicySource.Grant> read(String source, String application) {
    try {
      var json =
          JsonMapper.builder()
              .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
              .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .build();
      JsonNode root = json.readTree(source);
      if (root == null || !root.isObject()) throw new IllegalArgumentException("Invalid manifest");
      var entries = root.get("acting");
      if (entries == null) return List.of();
      if (!entries.isArray() || entries.size() > 64)
        throw new IllegalArgumentException("acting needs a bounded grant list");
      if (!entries.isEmpty() && ApplicationServiceManifest.declaration(source).isEmpty())
        throw new IllegalArgumentException("Acting grants require an owned Application service");
      if (!entries.isEmpty()) ActingValues.application(application);
      var result = new ArrayList<ActingPolicySource.Grant>();
      var names = new HashSet<String>();
      for (var entry : entries) {
        if (!entry.isObject()
            || entry.size() != 4
            || !entry.path("grant").isTextual()
            || !entry.path("ceiling").isTextual()
            || !entry.path("personal").isBoolean()
            || !entry.path("proofs").isArray())
          throw new IllegalArgumentException(
              "Acting grants need grant, ceiling, personal and proofs");
        var proofs = entry.get("proofs");
        if (proofs.size() > 1
            || proofs.size() == 1 && !"acting-token".equals(proofs.get(0).textValue()))
          throw new IllegalArgumentException("Only acting-token proof is supported");
        var grant =
            new ActingPolicySource.Grant(
                application,
                entry.get("grant").textValue(),
                ProjectRole.valueOf(entry.get("ceiling").textValue()),
                entry.get("personal").booleanValue(),
                proofs.isEmpty() ? Set.of() : Set.of(ActingPolicySource.ProofKind.ACTING_TOKEN));
        if (!names.add(grant.name())) throw new IllegalArgumentException("Duplicate acting grant");
        result.add(grant);
      }
      return List.copyOf(result);
    } catch (IOException invalid) {
      throw new IllegalArgumentException("Invalid acting manifest", invalid);
    }
  }
}
