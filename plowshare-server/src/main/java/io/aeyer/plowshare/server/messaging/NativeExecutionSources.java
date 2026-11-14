package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;

/** Exact retained run/turn to native requester proof shared by owning export boundaries. */
public interface NativeExecutionSources {
  NativeMessageExports.Source source(UsageAttribution owner);
}
