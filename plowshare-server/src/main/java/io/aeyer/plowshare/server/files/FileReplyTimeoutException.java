package io.aeyer.plowshare.server.files;

/** The server sent this file request but the matching reply did not arrive before its deadline. */
public final class FileReplyTimeoutException extends WorkspaceUnavailableException {
  private final String requestId;

  public FileReplyTimeoutException(String requestId, String message, Throwable cause) {
    super(message, cause);
    this.requestId = requestId;
  }

  public String requestId() {
    return requestId;
  }
}
