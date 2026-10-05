package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class InformationReportDetailsTest {
  @Test
  void long_runs_can_retain_more_than_one_hundred_findings_reviews_and_evidence_references() {
    var evidence = IntStream.range(0, 120).mapToObj(i -> new UUID(0, i + 1).toString()).toList();
    var findings =
        IntStream.range(0, 120)
            .mapToObj(
                i ->
                    Map.<String, Object>of(
                        "id",
                        "f" + i,
                        "objective",
                        "Topic",
                        "claim",
                        "A retained claim",
                        "support",
                        i == 0 ? evidence : List.of(evidence.get(i)),
                        "rationale",
                        "Not checked",
                        "verdict",
                        "not_checked"))
            .toList();
    var reviews =
        IntStream.range(0, 120)
            .mapToObj(
                i ->
                    Map.of(
                        "stage",
                        "review" + i,
                        "outcome",
                        "not_checked",
                        "text",
                        "Review incomplete"))
            .toList();
    var details =
        io.aeyer.plowshare.server.information.InformationReportDetailsDecoder.from(
            Map.of("objectives", List.of("Topic"), "findings", findings, "reviews", reviews));
    assertEquals(120, details.findings().size());
    assertEquals(120, details.reviews().size());
    assertEquals(120, details.evidence().size());
  }
}
