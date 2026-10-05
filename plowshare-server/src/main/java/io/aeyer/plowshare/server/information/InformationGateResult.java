package io.aeyer.plowshare.server.information;

import com.fasterxml.jackson.annotation.*;
import java.util.*;

/** The closed set of successful write-gate receipts. Persisted responses never expose raw JSON. */
public sealed interface InformationGateResult
    permits InformationCatalogue.Admission,
        InformationGateResult.Recorded,
        InformationGateResult.Completed {
  record Recorded(@JsonValue UUID id) implements InformationGateResult {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public Recorded {
      Objects.requireNonNull(id);
    }
  }

  record Completed(@JsonValue boolean completed) implements InformationGateResult {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public Completed {}
  }
}
