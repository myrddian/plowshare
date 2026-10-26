package io.aeyer.plowshare.server.information;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Current retained-input visibility, reusable across feeds with the same effective account. Facts
 * never replace the owner's log ownership/selection checks. The callback applies them before
 * paging/counting; its result is returned only after a current access/authority fence. Callbacks
 * must be pure database reads: a raced fence refuses the read and never replays a mutation.
 */
public interface InformationLogVisibility {
  /** Empty means the repository must evaluate the authoritative predicate in its own statement. */
  record Facts(Optional<List<String>> blocked) {
    public Facts {
      blocked = blocked.map(List::copyOf);
    }

    public static Facts authoritative() {
      return new Facts(Optional.empty());
    }
  }

  <T> T read(String account, Function<Facts, T> reader);

  static InformationLogVisibility direct() {
    return new InformationLogVisibility() {
      public <T> T read(String account, Function<Facts, T> reader) {
        return reader.apply(Facts.authoritative());
      }
    };
  }
}
