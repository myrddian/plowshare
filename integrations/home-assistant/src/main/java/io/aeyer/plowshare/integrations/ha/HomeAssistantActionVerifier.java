package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Availability;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ReadRequest;
import java.io.IOException;
import java.util.Comparator;
import java.util.Objects;

/** Reads once, checks an explicit state predicate, and never actuates or retries an action. */
public final class HomeAssistantActionVerifier implements ActionVerifier {
  private final ResourceDiscovery discovery;
  private final ResourceReader reader;

  public HomeAssistantActionVerifier(ResourceDiscovery discovery, ResourceReader reader) {
    this.discovery = Objects.requireNonNull(discovery);
    this.reader = Objects.requireNonNull(reader);
  }

  @Override
  public Verification verify(Request request, OperationBudget budget) throws IOException {
    budget.remaining();
    var references =
        request.expectedStates().keySet().stream()
            .sorted(Comparator.comparing(HomeAssistantResources.Reference::alias))
            .toList();
    for (var reference : references) {
      var description = discovery.describe(reference, request.revision(), budget);
      if (description.isEmpty()
          || !request.receipt().targets().contains(description.orElseThrow().entityId()))
        throw new IOException(
            "Verification selection is outside the retained action target bundle");
    }
    var observation = reader.read(new ReadRequest(request.revision(), references), budget);
    budget.remaining();
    if (!observation.revision().equals(request.revision())
        || !observation.states().stream()
            .map(HomeAssistantResources.State::reference)
            .toList()
            .containsAll(references)
        || observation.states().size() != references.size())
      throw new IOException("Incomplete or foreign verification observation");
    boolean available =
        observation.states().stream()
            .allMatch(
                state ->
                    !state.stale()
                        && state.availability() == Availability.AVAILABLE
                        && !state.observedAt().isBefore(request.receipt().recordedAt()));
    boolean matches =
        available
            && observation.states().stream()
                .allMatch(
                    state ->
                        state
                            .value()
                            .filter(request.expectedStates().get(state.reference())::equals)
                            .isPresent());
    return new Verification(
        request.receipt(),
        observation,
        !available ? Predicate.UNAVAILABLE : matches ? Predicate.MATCHED : Predicate.NOT_MATCHED);
  }
}
