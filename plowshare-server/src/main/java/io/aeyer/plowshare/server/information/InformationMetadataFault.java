package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.SchemaValidation;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Objects;

/** Stable metadata refusal and bounded diagnostics; private instance values are never echoed. */
public final class InformationMetadataFault extends CallerFault {
  private final SchemaValidation.Result validation;

  public InformationMetadataFault(SchemaValidation.Result validation) {
    super(
        validation.status() == SchemaValidation.Status.BUDGET_EXHAUSTED
            ? "INFORMATION_VALIDATION_BUDGET_EXHAUSTED"
            : "INFORMATION_METADATA_INVALID");
    this.validation = Objects.requireNonNull(validation);
    if (validation.status() == SchemaValidation.Status.VALID)
      throw new IllegalArgumentException("A valid instance cannot be refused");
  }

  public SchemaValidation.Result validation() {
    return validation;
  }
}
