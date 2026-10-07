package io.aeyer.plowshare.server.archive;

/** Durable downgrade protection for a project whose source has declared an application boundary. */
public interface ApplicationRegistrations {
  boolean required(String project);

  /** Monotonic: removing or losing a manifest must never restore legacy unrestricted membership. */
  void require(String project);
}
