package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.auth.AuthFilter;
import io.aeyer.plowshare.server.orchestrations.RecordReads;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/orchestrations/{id}/record} — a run tree's record, the HTTP twin of {@code
 * orchestration.record} (spec 2026-09-28 §3). Every rule is {@link RecordReads}'; the account is
 * the one {@code AuthFilter} signed this request in as.
 */
@RestController
public class OrchestrationRecordController {

  private final RecordReads reads;

  public OrchestrationRecordController(RecordReads reads) {
    this.reads = Objects.requireNonNull(reads, "reads");
  }

  @GetMapping("/v1/orchestrations/{id}/record")
  public ResponseEntity<RecordPageView> record(
      @PathVariable String id,
      @RequestParam(required = false) Integer after,
      @RequestParam(required = false) Integer before,
      @RequestParam(required = false) Boolean tail,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) List<String> kinds,
      @RequestAttribute(name = AuthFilter.HANDLE_ATTRIBUTE, required = false) String handle) {
    return ResponseEntity.ok(
        RecordPageView.of(reads.read(handle, id, after, before, tail, limit, kinds)));
  }
}
