package io.aeyer.plowshare.server.board;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.agents.SendMessageTool;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Versioned immutable send intent; neither model text nor current route resolution owns identity.
 */
final class MessageSendIdentity {
  private static final ObjectMapper JSON = new ObjectMapper();

  private MessageSendIdentity() {}

  private record Parameters(
      int version,
      String to,
      String body,
      String replyTo,
      boolean replyExpected,
      boolean finalReply,
      String lifetime,
      Long timeoutSeconds,
      String toProject,
      String route,
      boolean generated,
      String ending) {}

  static String fingerprint(SendMessageTool.Request request, boolean generated, String ending) {
    return hash(
        new Parameters(
            1,
            request.to(),
            request.body(),
            request.replyTo(),
            request.replyExpected(),
            request.finalReply(),
            request.lifetime() == null ? "persistent" : request.lifetime(),
            request.timeoutSeconds(),
            request.toProject(),
            request.route(),
            generated,
            ending));
  }

  private record Invocation(
      String account, String project, String run, long turn, long step, String call) {}

  static String invocation(UsageAttribution owner, String call) {
    if (owner.status() != UsageAttribution.Status.ATTRIBUTED
        || owner.runs().id() == null
        || owner.turnOrdinal() == null
        || owner.stepOrdinal() == null
        || call == null) throw new CallerFault("Messaging requires retained invocation ownership");
    return "v2:"
        + hash(
            new Invocation(
                owner.accountHandle(),
                owner.projectId(),
                owner.runs().id(),
                owner.turnOrdinal(),
                owner.stepOrdinal(),
                call));
  }

  static String recover(BoardMessagingRepository.PriorSend prior, String fingerprint) {
    if (prior.fingerprint() == null)
      throw new CallerFault(
          "Legacy send identity has no verifiable request fingerprint; inspect message "
              + prior.message()
              + " without sending again");
    if (!prior.fingerprint().equals(fingerprint))
      throw new CallerFault("Send identity was already used for different message arguments");
    return prior.message();
  }

  private static String hash(Object value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
    } catch (JsonProcessingException | NoSuchAlgorithmException failed) {
      throw new IllegalStateException("Message identity could not be encoded", failed);
    }
  }
}
