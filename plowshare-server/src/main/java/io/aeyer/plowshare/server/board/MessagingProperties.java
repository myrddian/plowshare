package io.aeyer.plowshare.server.board;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Transport resource bounds, independent of an experimental swarm's seat limits. */
@ConfigurationProperties("plowshare.messaging")
public class MessagingProperties {
  private int queueLimit = 256;
  private int instanceLimit = 256;
  private int bodyBytes = 65536;
  private final Personal personal = new Personal();

  public Personal getPersonal() {
    return personal;
  }

  /** Account-owned Personal projects are a routing hub unless the server narrows these defaults. */
  public static class Personal {
    private boolean sendToAnyProject = true;
    private boolean acceptFromAnyProject = true;

    public boolean isSendToAnyProject() {
      return sendToAnyProject;
    }

    public void setSendToAnyProject(boolean value) {
      sendToAnyProject = value;
    }

    public boolean isAcceptFromAnyProject() {
      return acceptFromAnyProject;
    }

    public void setAcceptFromAnyProject(boolean value) {
      acceptFromAnyProject = value;
    }
  }

  public int getQueueLimit() {
    return queueLimit;
  }

  public void setQueueLimit(int value) {
    queueLimit = positive(value);
  }

  public int getInstanceLimit() {
    return instanceLimit;
  }

  public void setInstanceLimit(int value) {
    instanceLimit = positive(value);
  }

  public int getBodyBytes() {
    return bodyBytes;
  }

  public void setBodyBytes(int value) {
    bodyBytes = positive(value);
  }

  private static int positive(int value) {
    if (value < 1)
      throw new IllegalArgumentException("A messaging resource limit must be positive.");
    return value;
  }
}
