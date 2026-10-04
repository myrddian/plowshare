package io.aeyer.plowshare.server.union;

import io.aeyer.plowshare.server.data.DataLayout;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** A project name to its hub. Empty when the name has no row or the server keeps no data. */
public final class Hubs {

  private final DataLayout layout;
  private final Function<String, Long> ids;

  public Hubs(DataLayout layout, Function<String, Long> ids) {
    this.layout = Objects.requireNonNull(layout, "layout");
    this.ids = Objects.requireNonNull(ids, "ids");
  }

  public Optional<Hub> of(String project) {
    if (!layout.keepsAnything()) {
      return Optional.empty();
    }
    Long id = ids.apply(project);
    return id == null ? Optional.empty() : Optional.of(new Hub(layout.unionFor(id)));
  }
}
