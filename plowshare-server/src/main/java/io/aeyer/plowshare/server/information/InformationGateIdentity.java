package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.InformationReportDetails;
import java.util.*;

/** Reviewed transition identities. Their fingerprints retain the existing receipt encoding. */
public sealed interface InformationGateIdentity {
  record Intake(
      String name,
      String hash,
      String mediaType,
      String sourceUri,
      InformationContext.Corpus corpus)
      implements InformationGateIdentity {
    public Intake {
      name = required(name, "name");
      hash = digest(hash);
      Objects.requireNonNull(corpus);
    }
  }

  record CodeSnapshot(String name, String hash, String sourceUri)
      implements InformationGateIdentity {
    public CodeSnapshot {
      name = required(name, "name");
      hash = digest(hash);
      sourceUri = required(sourceUri, "sourceUri");
    }
  }

  record Evidence(UUID revision, int start, int end, String quote, String locator)
      implements InformationGateIdentity {
    public Evidence {
      Objects.requireNonNull(revision);
      if (start < 0 || end < start) throw new IllegalArgumentException("invalid evidence span");
      quote = required(quote, "quote");
    }
  }

  record Report(
      String name,
      String hash,
      List<UUID> inputs,
      List<UUID> evidence,
      UUID feedback,
      InformationReportDetails details,
      String producer)
      implements InformationGateIdentity {
    public Report {
      name = required(name, "name");
      hash = digest(hash);
      inputs = List.copyOf(inputs);
      evidence = List.copyOf(evidence);
      Objects.requireNonNull(details);
    }
  }

  record Release(
      String payload,
      String owner,
      InformationContext.Selection selection,
      List<UUID> revisions,
      String reason)
      implements InformationGateIdentity {
    public Release {
      payload = required(payload, "payload");
      owner = required(owner, "owner");
      Objects.requireNonNull(selection);
      revisions = List.copyOf(revisions);
      reason = required(reason, "reason");
    }
  }

  record Adopt(
      UUID document,
      String owner,
      InformationContext.Scope visibility,
      String project,
      String reason)
      implements InformationGateIdentity {
    public Adopt {
      Objects.requireNonNull(document);
      owner = required(owner, "owner");
      Objects.requireNonNull(visibility);
      reason = required(reason, "reason");
    }
  }

  private static String required(String text, String field) {
    if (text == null || text.isBlank() || text.indexOf('\0') >= 0)
      throw new IllegalArgumentException(field + " required");
    return text;
  }

  private static String digest(String value) {
    if (value == null || !value.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("SHA256 digest required");
    return value;
  }
}
