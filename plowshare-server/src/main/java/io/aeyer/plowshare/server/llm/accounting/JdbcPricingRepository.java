package io.aeyer.plowshare.server.llm.accounting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owns pricing override persistence; booked accounting versions remain append-only. */
@Repository
public class JdbcPricingRepository implements PricingRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public JdbcPricingRepository(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public Optional<PriceOverride> find(String route, String model) {
    return jdbc
        .query(
            "SELECT rate_card::text,updated_at FROM admin_pricing WHERE billing_route=? AND model=?",
            (row, n) ->
                new PriceOverride(decode(row.getString(1)), row.getTimestamp(2).toInstant()),
            route,
            model)
        .stream()
        .findFirst();
  }

  private RateCard decode(String value) {
    try {
      return json.readValue(value, RateCard.class);
    } catch (JsonProcessingException invalid) {
      throw new IllegalStateException("Stored pricing is invalid", invalid);
    }
  }

  @Override
  public void lockChanges() {
    jdbc.execute("SELECT pg_advisory_xact_lock(734921865)");
  }

  @Override
  public void save(RateCard card, String actor) {
    final String encoded;
    try {
      encoded = json.writeValueAsString(card);
    } catch (JsonProcessingException invalid) {
      throw new IllegalStateException("Could not encode pricing", invalid);
    }
    jdbc.update(
        "INSERT INTO admin_pricing(billing_route,model,rate_card) VALUES(?,?,?::jsonb) ON CONFLICT(billing_route,model) DO UPDATE SET rate_card=EXCLUDED.rate_card,updated_at=now()",
        card.billingRoute(),
        card.model(),
        encoded);
    jdbc.update(
        "INSERT INTO admin_pricing_history(actor,billing_route,model,version,rate_card) VALUES(?,?,?,?,?::jsonb)",
        actor,
        card.billingRoute(),
        card.model(),
        card.version(),
        encoded);
    jdbc.update(
        "INSERT INTO admin_audit(actor,action,target) VALUES(?,'pricing.set',?)",
        actor,
        card.billingRoute() + ":" + card.model());
  }
}
