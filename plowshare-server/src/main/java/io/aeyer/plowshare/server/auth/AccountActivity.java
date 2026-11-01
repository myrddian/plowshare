package io.aeyer.plowshare.server.auth;

/** Current account lifecycle, including an exact service-token principal and its owner's state. */
public interface AccountActivity {
  /** Does not resolve a service token to a different actor or confer any project/tool authority. */
  boolean isEnabled(String handle);
}
