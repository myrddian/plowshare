package io.aeyer.plowshare.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Validated external message content. An adapter owns delivery identifiers and user role; a remote
 * response owns agent identifiers. Data parts use registered application contracts, never arbitrary
 * JSON. Validation conveys no authority to fetch a URL or execute a command.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExternalMessage(
    String messageId,
    String role,
    List<Part> parts,
    String contextId,
    String taskId,
    List<String> extensions,
    Metadata metadata) {
  public ExternalMessage {
    messageId = ContractValues.optionalIdentity(messageId, "messageId", 1024);
    if (role != null && !Set.of("ROLE_USER", "ROLE_AGENT").contains(role))
      throw new IllegalArgumentException("invalid external message role");
    parts = ContractValues.list(parts, "parts", 256);
    if (parts.isEmpty())
      throw new IllegalArgumentException("nonempty external message parts required");
    contextId = ContractValues.optionalIdentity(contextId, "contextId", 1024);
    taskId = ContractValues.optionalIdentity(taskId, "taskId", 1024);
    if (extensions != null) {
      extensions = ContractValues.list(extensions, "extensions", 32);
      for (String extension : extensions) absoluteReference(extension, "extension");
      if (extensions.stream().distinct().count() != extensions.size())
        throw new IllegalArgumentException("duplicate external message extensions");
    }
  }

  public static ExternalMessage text(String value) {
    return new ExternalMessage(null, null, List.of(Part.text(value)), null, null, null, null);
  }

  /** Every content part has exactly one representation. Raw files are bounded base64 bytes. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Part(
      String text,
      String raw,
      String url,
      IntegrationPayload.Request data,
      String mediaType,
      String filename,
      Metadata metadata) {
    public Part {
      if ((text != null ? 1 : 0)
              + (raw != null ? 1 : 0)
              + (url != null ? 1 : 0)
              + (data != null ? 1 : 0)
          != 1)
        throw new IllegalArgumentException(
            "external part requires exactly one content representation");
      text = ContractValues.text(text, "part text", 256 * 1024, false);
      if (raw != null) {
        if (raw.length() > 256 * 1024) throw new IllegalArgumentException("raw file exceeds bound");
        try {
          byte[] bytes = Base64.getDecoder().decode(raw);
          if (!Base64.getEncoder().encodeToString(bytes).equals(raw))
            throw new IllegalArgumentException("raw file must use canonical base64");
        } catch (IllegalArgumentException invalid) {
          throw new IllegalArgumentException("invalid base64 file", invalid);
        }
      }
      if (url != null) url = WebContractValues.url(url);
      mediaType = ContractValues.optionalIdentity(mediaType, "mediaType", 256);
      if (mediaType != null && !mediaType.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+"))
        throw new IllegalArgumentException("invalid content media type");
      filename = ContractValues.optionalIdentity(filename, "filename", 1024);
      if (filename != null
          && (filename.contains("/")
              || filename.contains("\\")
              || filename.equals(".")
              || filename.equals("..")))
        throw new IllegalArgumentException("filename must be a display name, not a path");
    }

    public static Part text(String value) {
      return new Part(Objects.requireNonNull(value), null, null, null, null, null, null);
    }
  }

  /** Supported command extension. Unknown metadata requires its own validated contract. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Metadata(
      String plowshareCommand,
      String plowshareEnding,
      Boolean plowshareGenerated,
      Boolean plowshareFinal) {
    public Metadata {
      new Incoming.Metadata(plowshareCommand);
      if (plowshareEnding != null
          && !plowshareEnding.isEmpty()
          && !Set.of(
                  "ANSWERED",
                  "TURN_CAP",
                  "CALL_BUDGET",
                  "CANCELLED",
                  "STUCK",
                  "UNAVAILABLE",
                  "SUB_AGENT_FAILED",
                  "SESSION_GONE",
                  "AWAITING")
              .contains(plowshareEnding))
        throw new IllegalArgumentException("invalid remote Plowshare ending");
    }

    public Metadata(String command) {
      this(command, null, null, null);
    }
  }

  private static void absoluteReference(String value, String field) {
    ContractValues.identity(value, field, 8192);
    try {
      if (!java.net.URI.create(value).isAbsolute())
        throw new IllegalArgumentException("relative " + field);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("invalid " + field, invalid);
    }
  }
}
