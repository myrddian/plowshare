package io.aeyer.plowshare.server.acting;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.AccessRequest;
import io.aeyer.plowshare.server.access.ResourceScopeRepository;
import io.aeyer.plowshare.server.agents.RunAuthority;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import java.util.List;
import java.util.Objects;

/** A retained Personal turn cannot widen its home through a tool argument or resource ID. */
public final class PersonalRunAuthority implements RunAuthority {
  private final Home home;
  private final Runnable check;
  private final ResourceScopeRepository resources;

  public PersonalRunAuthority(String subject, Runnable check, ResourceScopeRepository resources) {
    this.home = Home.of(PersonalSpaces.name(subject));
    this.check = Objects.requireNonNull(check);
    this.resources = Objects.requireNonNull(resources);
  }

  @Override
  public void require(Home requested, List<AccessRequest.Resource> references) {
    check.run();
    if (!home.equals(requested)) throw refused();
    for (var resource : references) {
      var scopes = resources.projects(resource);
      if (scopes.size() != 1 || !home.project().equals(scopes.getFirst())) throw refused();
    }
    check.run();
  }

  private static CallerFault refused() {
    return new CallerFault("Resource is outside retained Personal authority");
  }
}
