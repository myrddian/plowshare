package io.aeyer.plowshare.server.llm.accounting;

import io.aeyer.plowshare.server.llm.accounting.CostResult.Kind;
import io.aeyer.plowshare.server.llm.accounting.CostResult.Reason;
import io.aeyer.plowshare.server.llm.accounting.UsageObservation.CacheRelation;
import io.aeyer.plowshare.server.llm.accounting.UsageObservation.Source;
import io.aeyer.plowshare.server.llm.dispatch.Lane;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Immutable operator contract for one billing route and exact wire model, snapshotted at admission.
 * Quote once per billable upstream attempt. Reasoning is part of output, never an extra line item.
 * This is a usage estimate, not a provider invoice; do not round until display.
 */
public record RateCard(String revision, String billingRoute, String model, Mode mode, String currency,
        Instant validFrom, Instant validUntil, Rates rates, List<Tier> tiers, BigDecimal requestFee,
        String source) {

    public RateCard {
        UsageLineage.requireId(revision);
        UsageLineage.requireId(billingRoute);
        UsageLineage.requireId(model);
        UsageLineage.requireId(source);
        Objects.requireNonNull(mode, "mode");
        if (mode != Mode.UNPRICED) {
            CostResult.requireCurrency(currency);
        } else if (currency != null) {
            CostResult.requireCurrency(currency);
        }
        if (validFrom != null && validUntil != null && !validFrom.isBefore(validUntil)) {
            throw new IllegalArgumentException("price validity must be a nonempty interval");
        }
        tiers = List.copyOf(tiers);
        long previous = -1;
        for (Tier tier : tiers) {
            if (tier.fromInputTokens() <= previous) {
                throw new IllegalArgumentException("price tiers must have increasing input thresholds");
            }
            previous = tier.fromInputTokens();
        }
        nonnegative(requestFee);
        if (mode != Mode.TOKEN && (rates != null || !tiers.isEmpty() || requestFee != null)) {
            throw new IllegalArgumentException("only token pricing may specify rates, tiers or request fees");
        }
    }

    public boolean activeAt(Instant when) {
        Objects.requireNonNull(when, "when");
        return (validFrom == null || !when.isBefore(validFrom))
                && (validUntil == null || when.isBefore(validUntil));
    }

    public CostResult quote(UsageObservation usage, Lane lane) {
        return quote(usage, lane, null);
    }

    /** An explicit verified provider charge replaces the arithmetic; it is not added to it. */
    public CostResult quote(UsageObservation usage, Lane lane, CostResult.ReportedCharge reported) {
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(lane, "lane");
        String version = version();
        if (reported != null) {
            return new CostResult(Kind.REPORTED, reported.amount(), reported.currency(), version, List.of());
        }
        if (mode == Mode.UNPRICED) {
            return new CostResult(Kind.UNKNOWN, null, currency, version, List.of(Reason.MISSING_PRICE));
        }
        if (mode == Mode.INCLUDED || mode == Mode.ZERO_RATE) {
            return new CostResult(mode == Mode.INCLUDED ? Kind.INCLUDED : Kind.ZERO_RATE,
                    BigDecimal.ZERO, currency, version, List.of());
        }
        var reasons = new ArrayList<Reason>();
        var amounts = new ArrayList<BigDecimal>();
        if (requestFee != null) {
            amounts.add(requestFee);
        }
        if (!usage.issues().isEmpty()) {
            reasons.add(Reason.INVALID_USAGE);
        } else if (usage.source() != Source.PROVIDER) {
            reasons.add(Reason.NO_PROVIDER_USAGE);
        } else if (!tiers.isEmpty() && usage.inputTokens() == null) {
            reasons.add(Reason.TIER_INPUT_UNKNOWN);
        } else {
            Rates selected = ratesFor(usage.inputTokens());
            inputCost(usage, selected, amounts, reasons);
            if (lane == Lane.CHAT) {
                if (usage.outputTokens() == null) {
                    reasons.add(Reason.MISSING_OUTPUT);
                } else if (selected == null || selected.output() == null) {
                    reasons.add(Reason.MISSING_OUTPUT_RATE);
                } else {
                    amounts.add(tokens(usage.outputTokens(), selected.output()));
                }
            }
        }
        BigDecimal amount = amounts.isEmpty() ? null
                : amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CostResult(reasons.isEmpty() ? Kind.ESTIMATED
                : amount == null ? Kind.UNKNOWN : Kind.PARTIAL, amount, currency, version, reasons);
    }

    private Rates ratesFor(Long input) {
        Rates selected = rates;
        for (Tier tier : tiers) {
            if (input != null && input >= tier.fromInputTokens()) {
                selected = tier.rates();
            } else {
                break;
            }
        }
        return selected;
    }

    private static void inputCost(UsageObservation usage, Rates rates, List<BigDecimal> amounts,
            List<Reason> reasons) {
        if (usage.inputTokens() == null) {
            reasons.add(Reason.MISSING_INPUT);
            return;
        }
        if (rates == null || rates.input() == null) {
            reasons.add(Reason.MISSING_INPUT_RATE);
            return;
        }
        if (rates.cacheRead() == null && rates.cacheWrite() == null) {
            amounts.add(tokens(usage.inputTokens(), rates.input()));
            return;
        }
        boolean disjoint = rates.cacheWrite() != null;
        if (usage.cacheRelation() == CacheRelation.UNSPECIFIED
                || (disjoint && usage.cacheRelation() != CacheRelation.DISJOINT_INPUT_SUBSETS)) {
            reasons.add(Reason.UNKNOWN_CACHE_RELATION);
            return;
        }
        Long read = rates.cacheRead() == null ? Long.valueOf(0) : usage.cacheReadTokens();
        Long write = disjoint ? usage.cacheWriteTokens() : Long.valueOf(0);
        if (read == null) {
            reasons.add(Reason.MISSING_CACHE_READ);
        }
        if (write == null) {
            reasons.add(Reason.MISSING_CACHE_WRITE);
        }
        if (read == null || write == null) {
            return;
        }
        // The normalizer validated each subset and, for this contract, their disjoint sum.
        long uncached = usage.inputTokens() - read - write;
        BigDecimal cost = tokens(uncached, rates.input());
        if (rates.cacheRead() != null) {
            cost = cost.add(tokens(read, rates.cacheRead()));
        }
        if (disjoint) {
            cost = cost.add(tokens(write, rates.cacheWrite()));
        }
        amounts.add(cost);
    }

    private static BigDecimal tokens(long count, BigDecimal rate) {
        return rate.multiply(BigDecimal.valueOf(count)).movePointLeft(6);
    }

    /** Canonical content hash: changing a rate cannot mutate the version booked on an earlier call. */
    public String version() {
        var text = new StringBuilder();
        append(text, revision, billingRoute, model, mode.name(), currency,
                validFrom == null ? null : validFrom.toString(),
                validUntil == null ? null : validUntil.toString(), decimal(requestFee), source);
        appendRates(text, rates);
        for (Tier tier : tiers) {
            append(text, Long.toString(tier.fromInputTokens()));
            appendRates(text, tier.rates());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM does not support SHA-256", impossible);
        }
    }

    private static void appendRates(StringBuilder text, Rates rates) {
        append(text, rates == null ? "absent" : "rates");
        if (rates != null) {
            append(text, decimal(rates.input()), decimal(rates.output()),
                    decimal(rates.cacheRead()), decimal(rates.cacheWrite()));
        }
    }

    private static void append(StringBuilder text, String... values) {
        for (String value : values) {
            text.append(value == null ? -1 : value.length()).append(':');
            if (value != null) {
                text.append(value);
            }
        }
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static void nonnegative(BigDecimal value) {
        if (value != null && value.signum() < 0) {
            throw new IllegalArgumentException("pricing amounts must be nonnegative");
        }
    }

    public enum Mode { TOKEN, INCLUDED, ZERO_RATE, UNPRICED }

    /** Missing rates remain missing; no public rate or cache discount is inferred. */
    public record Rates(BigDecimal input, BigDecimal output, BigDecimal cacheRead, BigDecimal cacheWrite) {
        public Rates {
            nonnegative(input);
            nonnegative(output);
            nonnegative(cacheRead);
            nonnegative(cacheWrite);
        }
    }

    /** This full rate set replaces the base set at an inclusive input threshold, per attempt. */
    public record Tier(long fromInputTokens, Rates rates) {
        public Tier {
            if (fromInputTokens < 0) {
                throw new IllegalArgumentException("price tier threshold must be nonnegative");
            }
            Objects.requireNonNull(rates, "rates");
        }
    }
}
