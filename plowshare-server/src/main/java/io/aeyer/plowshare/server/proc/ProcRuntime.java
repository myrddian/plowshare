package io.aeyer.plowshare.server.proc;

/** Read-only evidence from this server's runtime, never a second execution registry. */
@FunctionalInterface
public interface ProcRuntime {
  ProcRepository.Live snapshot();

  /** Fleet-only approximate local executor occupancy; never includes upstream hidden queues. */
  default java.util.List<ProcViews.Pool> pools() {
    return java.util.List.of();
  }

  /** Safe capture health, with no journal paths or provider error bodies. */
  default java.util.List<String> notes() {
    return java.util.List.of();
  }
}
