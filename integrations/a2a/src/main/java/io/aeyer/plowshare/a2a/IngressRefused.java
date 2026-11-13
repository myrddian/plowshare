package io.aeyer.plowshare.a2a;

import java.io.IOException;

/** Explicit negative server evidence, distinct from unknown transport delivery. */
public final class IngressRefused extends IOException {
  public IngressRefused(String category) {
    super(category);
  }
}
