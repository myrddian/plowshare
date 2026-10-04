package io.aeyer.plowshare.server.files;

/**
 * How much of a scope a grant gives.
 *
 * <p>The implication between the two — write covers read, read does not cover write — is stated
 * once, in {@link Grant#allows}, which owns it and the reason no second copy may exist. There is
 * deliberately no ordering encoded here.
 */
public enum Mode {
  READ,
  WRITE
}
