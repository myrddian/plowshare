package io.aeyer.plowshare.server.llm.accounting;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.llm.dispatch.Lane;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

final class AccountingFixtures {
  static final Instant NOW = Instant.parse("2026-10-02T01:00:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  static final ObjectMapper MAPPER = new ObjectMapper();
  static final RateCard PRICE =
      new RateCard(
          "local-v1",
          "local",
          "model",
          RateCard.Mode.TOKEN,
          "USD",
          null,
          null,
          new RateCard.Rates(BigDecimal.ONE, new BigDecimal("4"), null, null),
          List.of(),
          null,
          "operator");

  private AccountingFixtures() {}

  static AccountingEvent.CallCreated metadata() {
    var owner =
        UsageAttribution.project("alice", "project-snapshot", UsageAttribution.Operation.AGENT_CHAT)
            .withExecution(
                UsageLineage.root("c-root").child("c-leaf"),
                UsageLineage.root("r-root").child("r-no-call").child("r-leaf"),
                UsageLineage.NONE,
                "researcher",
                2L,
                1L);
    return new AccountingEvent.CallCreated(
        owner, "fast", "pool", "model", null, "local", Lane.CHAT, PRICE);
  }

  static AccountingEvent created() {
    return new AccountingEvent(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, NOW, metadata());
  }
}
