package io.aeyer.plowshare.server.approvals;

/** Claims one exact file question after its retained execution fence has been won. */
@FunctionalInterface
public interface FileApprovalConsent {
  boolean consumeFile(String id, FileApproval expected);
}
