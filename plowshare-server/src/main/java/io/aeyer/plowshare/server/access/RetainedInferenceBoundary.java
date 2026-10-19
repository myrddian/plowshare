package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Attribution and a positive allowance cannot substitute for explicit live execution authority. */
@Component
public final class RetainedInferenceBoundary implements InferenceBoundary {
  private final InferenceAdmissions records;

  public RetainedInferenceBoundary(InferenceAdmissions records) {
    this.records = Objects.requireNonNull(records);
  }

  private InferenceBinding binding(CallAuthorization authorization) {
    var binding = authorization.binding();
    if (binding == null)
      throw new CallerFault(
          "Inference requires retained authority and an operation allowance claim");
    return binding;
  }

  @Override
  public void submitted(CallAuthorization authorization) {
    require(authorization);
    records.submitted(binding(authorization));
  }

  @Override
  public void require(CallAuthorization authorization) {
    var binding = binding(authorization);
    authorization.require();
    records.require(binding);
  }

  @Override
  public void accounted(CallAuthorization authorization, InferenceCapture capture) {
    if (capture != null) records.accounted(binding(authorization), capture.callId());
  }
}
