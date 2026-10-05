package io.aeyer.plowshare.testpeer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/** Server assertions are answered by the maintained Node enforcer, not a Java filesystem clone. */
public final class NodeFiles {
  private static final ObjectMapper JSON =
      new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  private final TestWorkspace workspace;

  public NodeFiles(TestWorkspace workspace) {
    this.workspace = workspace;
  }

  public FileReply answer(FileRequest request) {
    if (request.op().equals("roots"))
      return io.aeyer.plowshare.protocol.FileReply.listed(
          request.id(), workspace.roots().stream().map(Object::toString).toList());
    if (workspace.roots().isEmpty())
      return FileReply.refused(
          request.id(),
          io.aeyer.plowshare.protocol.FileResult.refused(
              request.op(), io.aeyer.plowshare.protocol.FileResult.NO_WORKSPACE, null));
    try {
      return JSON.readValue(
          NodeBridge.exchange(
              JSON.writeValueAsString(
                  Map.of(
                      "mode",
                      "files",
                      "roots",
                      workspace.roots().stream().map(Object::toString).toList(),
                      "request",
                      request))),
          FileReply.class);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }
}
