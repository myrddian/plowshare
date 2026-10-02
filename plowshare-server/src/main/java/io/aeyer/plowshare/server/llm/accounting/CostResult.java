package io.aeyer.plowshare.server.llm.accounting;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/** An unrounded booked estimate or a known subtotal. A null amount is never a free call. */
public record CostResult(Kind kind, BigDecimal amount, String currency, String priceVersion,
        List<Reason> reasons) {

    public CostResult {
        Objects.requireNonNull(kind, "kind");
        reasons = List.copyOf(reasons);
        if (currency != null) {
            requireCurrency(currency);
        }
        if (amount != null) {
            if (amount.signum() < 0) {
                throw new IllegalArgumentException("cost must be nonnegative");
            }
            requireCurrency(currency);
        }
        if (kind == Kind.UNKNOWN) {
            if (amount != null || reasons.isEmpty()) {
                throw new IllegalArgumentException("unknown cost needs reasons and no amount");
            }
        } else if (amount == null) {
            throw new IllegalArgumentException("a known cost or subtotal needs an amount");
        }
        if (kind == Kind.PARTIAL && reasons.isEmpty()) {
            throw new IllegalArgumentException("a cost subtotal needs its missing coverage");
        }
        if (kind != Kind.PARTIAL && kind != Kind.UNKNOWN && !reasons.isEmpty()) {
            throw new IllegalArgumentException("a complete cost cannot carry missing coverage");
        }
        if ((kind == Kind.ZERO_RATE || kind == Kind.INCLUDED) && amount.signum() != 0) {
            throw new IllegalArgumentException("zero-rate or included cost must have a zero amount");
        }
    }

    public static CostResult unpriced() {
        return new CostResult(Kind.UNKNOWN, null, null, null, List.of(Reason.MISSING_PRICE));
    }

    /** DTOs use this decimal string, preserving values below a cent and JavaScript precision. */
    public String amountDecimal() {
        return amount == null ? null : amount.toPlainString();
    }

    static void requireCurrency(String currency) {
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("pricing needs an uppercase ISO currency");
        }
        Currency.getInstance(currency);
    }

    public enum Kind { ESTIMATED, REPORTED, INCLUDED, ZERO_RATE, UNKNOWN, PARTIAL }

    public enum Reason {
        MISSING_PRICE, MISSING_INPUT, MISSING_OUTPUT, MISSING_INPUT_RATE, MISSING_OUTPUT_RATE,
        MISSING_CACHE_READ, MISSING_CACHE_WRITE, UNKNOWN_CACHE_RELATION,
        INVALID_USAGE, NO_PROVIDER_USAGE, TIER_INPUT_UNKNOWN
    }

    /** A charge from a verified monetary provider field; no current transport guesses this. */
    public record ReportedCharge(BigDecimal amount, String currency) {
        public ReportedCharge {
            Objects.requireNonNull(amount, "amount");
            if (amount.signum() < 0) {
                throw new IllegalArgumentException("reported charge must be nonnegative");
            }
            requireCurrency(currency);
        }
    }
}
