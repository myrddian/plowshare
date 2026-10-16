package io.aeyer.plowshare.server.information;

import com.fasterxml.jackson.annotation.*;
import java.util.*;

/** The closed set of successful write-gate receipts. Persisted responses never expose raw JSON. */
public sealed interface InformationGateResult
    permits InformationGateResult.Imported,
        InformationCatalogue.Admission,
        InformationGateResult.Recorded,
        InformationGateResult.ContentTypeVersion,
        InformationGateResult.Completed {
  record Imported(@JsonValue io.aeyer.plowshare.protocol.InformationInterchange.Imported value)
      implements InformationGateResult {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public Imported {
      Objects.requireNonNull(value);
    }
  }

  record Recorded(@JsonValue UUID id) implements InformationGateResult {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public Recorded {
      Objects.requireNonNull(id);
    }
  }

  record ContentTypeVersion(@JsonValue io.aeyer.plowshare.protocol.InformationContent.Version value)
      implements InformationGateResult {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ContentTypeVersion {
      Objects.requireNonNull(value);
    }
  }

  record Completed(@JsonValue boolean completed) implements InformationGateResult {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public Completed {}
  }
}
