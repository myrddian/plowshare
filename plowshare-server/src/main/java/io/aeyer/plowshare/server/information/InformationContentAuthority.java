package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.InformationContent;
import java.util.Optional;
import java.util.UUID;

/** Resolves exact immutable revision typing before gates and rechecks assignment inside commit. */
public interface InformationContentAuthority {
  Optional<InformationContent.Binding> resolve(
      InformationContext context,
      String name,
      UUID expectedRevision,
      InformationContent.Typing typing);

  void recheck(InformationContext context, InformationContent.Binding binding, boolean inherit);
}
