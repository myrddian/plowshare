package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.faults.CallerFault;

/** Rollback stops authored writes while retaining read support and worker fences. */
public record InformationContentSettings(boolean writesEnabled) {
  public void requireWrites() {
    if (!writesEnabled) throw new CallerFault("Authored-information writes are disabled");
  }
}
