package io.aeyer.plowshare.server.agents;

/** Aggregate JobStore task lifecycle, without job IDs, agent names or result content. */
public interface JobTelemetry {
  /** Called on the accepted executor task, not on submission or a droppable socket event. */
  Run started();

  void rejected();

  interface Run {
    /** Records task termination once; cancellation requests alone are not completions. */
    void finished(Outcome.Ending ending);
  }

  JobTelemetry NONE =
      new JobTelemetry() {
        public Run started() {
          return ending -> {};
        }

        public void rejected() {}
      };
}
