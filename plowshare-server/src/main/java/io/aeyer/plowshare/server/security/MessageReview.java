package io.aeyer.plowshare.server.security;

import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import java.util.function.BooleanSupplier;

/** Optional external text review. Returns the complete approved message or explicitly refuses. */
public interface MessageReview {
  String review(UsageAttribution owner, String role, String text, BooleanSupplier abandoned);

  MessageReview NONE = (owner, role, text, abandoned) -> text;
}
