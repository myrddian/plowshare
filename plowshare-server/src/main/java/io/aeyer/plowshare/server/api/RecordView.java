package io.aeyer.plowshare.server.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.aeyer.plowshare.server.orchestrations.RecordRow;
import java.time.Instant;

/**
 * One row of the orchestration record, as both surfaces send it.
 *
 * @param body the row's whole text (V64), sent only when it has one: left out rather than sent as
 *     null, unlike {@code detail}, so the page a reader built before it read is the page it still
 *     reads for every row but the few a person reads whole
 */
public record RecordView(
    int ordinal,
    Instant at,
    String run,
    String actor,
    String kind,
    String text,
    String detail,
    @JsonInclude(JsonInclude.Include.NON_NULL) String body) {

  public static RecordView of(RecordRow row) {
    return new RecordView(
        row.ordinal(),
        row.at(),
        row.run(),
        row.actor(),
        row.kind().wire(),
        row.text(),
        row.detail(),
        row.body());
  }
}
