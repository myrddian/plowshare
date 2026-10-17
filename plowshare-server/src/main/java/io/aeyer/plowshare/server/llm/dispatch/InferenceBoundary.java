package io.aeyer.plowshare.server.llm.dispatch;

/** Mandatory production gate; legacy constructors use only the isolated-fixture implementation. */
public interface InferenceBoundary {
  void submitted(CallAuthorization authorization);

  void require(CallAuthorization authorization);

  void accounted(CallAuthorization authorization, InferenceCapture capture);

  InferenceBoundary FIXTURE =
      new InferenceBoundary() {
        public void submitted(CallAuthorization authorization) {
          authorization.require();
        }

        public void require(CallAuthorization authorization) {
          authorization.require();
        }

        public void accounted(CallAuthorization authorization, InferenceCapture capture) {}
      };
}
