package com.revshare.domain.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A currency amount, held as a {@link BigDecimal} normalised to two decimal places.
 *
 * <p>Every instance is normalised in the compact constructor, which is what makes {@code equals} usable:
 * {@code BigDecimal} equality is scale-sensitive, so an un-normalised {@code 2.5} and {@code 2.50} would compare
 * unequal. Because construction is the only way to obtain a {@code Money}, all instances in the system share scale
 * {@value #SCALE} and value equality behaves as a caller expects.
 *
 * <p>Rounding is {@link RoundingMode#HALF_UP}, applied once per operation rather than accumulated in a running double.
 * Commission arithmetic is the entire point of this system, so binary floating point is not used anywhere in the
 * domain.
 *
 * <p>Negative amounts are permitted. Chargebacks and commission reversals are negative by nature, and rejecting them
 * here would push the sign handling into every caller. Where a non-negative value is genuinely an invariant, the owning
 * type asserts it.
 */
public record Money(BigDecimal amount) implements Comparable<Money> {

    /** Minor-unit precision. Two places for USD. */
    public static final int SCALE = 2;

    /** Half-up matches how commission statements are conventionally rounded. */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    public static final Money ZERO = Money.of(BigDecimal.ZERO);

    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        amount = amount.setScale(SCALE, ROUNDING);
    }

    public static Money of(BigDecimal amount) {
        return new Money(amount);
    }

    /**
     * Parses an exact decimal string, e.g. {@code Money.of("12000.00")}.
     *
     * <p>Prefer this over the {@code double} and {@code long} factories in tests and configuration: {@code new
     * BigDecimal(0.1)} is {@code 0.1000000000000000055511...}, and the string form is the only one that says exactly
     * what was meant.
     */
    public static Money of(String amount) {
        return new Money(new BigDecimal(amount));
    }

    public static Money of(long wholeUnits) {
        return new Money(BigDecimal.valueOf(wholeUnits));
    }

    public Money plus(Money other) {
        return new Money(this.amount.add(other.amount));
    }

    public Money minus(Money other) {
        return new Money(this.amount.subtract(other.amount));
    }

    /** Applies a rate, rounding the product once. */
    public Money multipliedBy(Percentage rate) {
        return new Money(this.amount.multiply(rate.fraction()));
    }

    public Money multipliedBy(int factor) {
        return new Money(this.amount.multiply(BigDecimal.valueOf(factor)));
    }

    /**
     * Inverts {@link #multipliedBy(Percentage)}: given a share and the rate that produced it, recovers the base it was
     * taken from.
     *
     * <p>Used to answer "how much gross commission generated this much company dollar?", which is the quantity revenue
     * share is actually assessed on.
     */
    public Money dividedBy(Percentage rate) {
        if (rate.isZero()) {
            throw new ArithmeticException("cannot divide " + this + " by a zero rate");
        }
        return new Money(this.amount.divide(rate.fraction(), SCALE, ROUNDING));
    }

    public Money negated() {
        return new Money(this.amount.negate());
    }

    public boolean isZero() {
        return this.amount.signum() == 0;
    }

    public boolean isPositive() {
        return this.amount.signum() > 0;
    }

    public boolean isNegative() {
        return this.amount.signum() < 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isGreaterThanOrEqualTo(Money other) {
        return compareTo(other) >= 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    public static Money min(Money a, Money b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    public static Money max(Money a, Money b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    /** Clamps to zero. Convenient where a remaining-allowance calculation can undershoot. */
    public Money atLeastZero() {
        return isNegative() ? ZERO : this;
    }

    @Override
    public int compareTo(Money other) {
        return this.amount.compareTo(other.amount);
    }

    @Override
    public String toString() {
        return "$" + amount.toPlainString();
    }
}
