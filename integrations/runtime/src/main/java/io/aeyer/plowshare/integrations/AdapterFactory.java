package io.aeyer.plowshare.integrations;

import java.io.IOException;
import java.util.function.Function;

/** Explicitly installed modules provide factories through ServiceLoader. */
public interface AdapterFactory {
  String name();

  /** Own the configuration boundary: reject malformed/unknown fields before creating an adapter. */
  AdapterConfiguration decode(String configuration) throws IOException;

  /** Instantiate from validated settings; secrets are resolved separately and never persisted. */
  IntegrationAdapter create(
      AdapterConfiguration configuration, Function<String, String> environment) throws IOException;
}
