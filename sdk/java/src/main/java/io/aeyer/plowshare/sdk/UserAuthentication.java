package io.aeyer.plowshare.sdk;

import java.io.IOException;

/** Optional user-authentication HTTP bootstrap. Never replays application operations. */
public interface UserAuthentication {
  /** Canonical server origin to which this capability sends credentials. */
  String origin();

  /** Sign in without saving the password. Restricted sessions remain explicit. */
  SignIn login(String handle, String password) throws IOException;

  /** Query current server authority; a negative status represents an explicit HTTP 401. */
  Standing standing(Tokens tokens) throws IOException;

  /** Rotate a single-use pair once. Call through Credentials for shared saved sessions. */
  Tokens refresh(Tokens tokens) throws IOException;

  /** Revoke this session. Failure must leave local credentials available for recovery. */
  void logout(Tokens tokens) throws IOException;

  /** Private bearer material. Refresh is null only for nonrenewable sessions. */
  record Tokens(String access, String refresh) {
    public Tokens {
      AuthState.secret(access);
      if (refresh != null) AuthState.secret(refresh);
    }

    @Override
    public String toString() {
      return "Tokens[redacted]";
    }
  }

  /** Sign-in result; restricted sessions are never stored as renewable credentials. */
  record SignIn(Tokens tokens, boolean mustChangePassword, boolean setupRequired) {
    public SignIn {
      java.util.Objects.requireNonNull(tokens, "tokens");
    }
  }

  /** Server session status, independent of local saved metadata. */
  record Standing(boolean signedIn, boolean mustChangePassword) {}
}
