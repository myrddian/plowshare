package io.aeyer.plowshare.server.llm;

import io.aeyer.plowshare.server.llm.accounting.RateCard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One operator-configured price version; snapshots are immutable even if this binding bean changes. */
public class PriceProperties {

    private String revision;
    private String billingRoute;
    private String model;
    private RateCard.Mode mode = RateCard.Mode.TOKEN;
    private String currency;
    private String source = "operator";
    private Instant validFrom;
    private Instant validUntil;
    private Map<String, BigDecimal> ratesPerMillion = new LinkedHashMap<>();
    private List<TierProperties> tiers = new ArrayList<>();
    private BigDecimal requestFee;

    public String getRevision() { return revision; }
    public void setRevision(String value) { revision = value; }
    public String getBillingRoute() { return billingRoute; }
    public void setBillingRoute(String value) { billingRoute = value; }
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
    public RateCard.Mode getMode() { return mode; }
    public void setMode(RateCard.Mode value) { mode = value; }
    public String getCurrency() { return currency; }
    public void setCurrency(String value) { currency = value; }
    public String getSource() { return source; }
    public void setSource(String value) { source = value; }
    public Instant getValidFrom() { return validFrom; }
    public void setValidFrom(Instant value) { validFrom = value; }
    public Instant getValidUntil() { return validUntil; }
    public void setValidUntil(Instant value) { validUntil = value; }
    public BigDecimal getRequestFee() { return requestFee; }
    public void setRequestFee(BigDecimal value) { requestFee = value; }
    public Map<String, BigDecimal> getRatesPerMillion() { return Map.copyOf(ratesPerMillion); }
    public void setRatesPerMillion(Map<String, BigDecimal> value) {
        ratesPerMillion = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }
    public List<TierProperties> getTiers() { return List.copyOf(tiers); }
    public void setTiers(List<TierProperties> value) {
        tiers = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }

    public RateCard snapshot() {
        var frozenTiers = tiers.stream().map(TierProperties::snapshot).toList();
        return new RateCard(revision, billingRoute, model, mode, currency, validFrom, validUntil,
                rates(getRatesPerMillion()), frozenTiers, requestFee, source);
    }

    private static RateCard.Rates rates(Map<String, BigDecimal> values) {
        if (values.isEmpty()) {
            return null;
        }
        if (!Set.of("input", "output", "cache-read", "cache-write").containsAll(values.keySet())) {
            throw new IllegalArgumentException("rates-per-million has an unsupported token category");
        }
        return new RateCard.Rates(values.get("input"), values.get("output"),
                values.get("cache-read"), values.get("cache-write"));
    }

    public static class TierProperties {
        private Long fromInputTokens;
        private Map<String, BigDecimal> ratesPerMillion = new LinkedHashMap<>();

        public Long getFromInputTokens() { return fromInputTokens; }
        public void setFromInputTokens(Long value) { fromInputTokens = value; }
        public Map<String, BigDecimal> getRatesPerMillion() { return Map.copyOf(ratesPerMillion); }
        public void setRatesPerMillion(Map<String, BigDecimal> value) {
            ratesPerMillion = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
        }

        private RateCard.Tier snapshot() {
            if (fromInputTokens == null) {
                throw new IllegalArgumentException("a price tier needs from-input-tokens");
            }
            return new RateCard.Tier(fromInputTokens, rates(getRatesPerMillion()));
        }
    }
}
