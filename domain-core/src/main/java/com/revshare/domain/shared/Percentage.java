package com.revshare.domain.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A rate, stored as a fraction of one ({@code 15%} is {@code 0.15}).
 *
 * <p>Held at {@value #SCALE} decimal places rather than {@link Money}'s two, because a rate is an intermediate value:
 * rounding it to cents before multiplying would push error into every amount derived from it. Rounding happens once,
 * when a rate meets an amount in {@link Money#multipliedBy(Percentage)}.
 *
 * <p>The distinction between the percent form and the fraction form is the classic source of hundred-fold errors in
 * commission code, so there is no ambiguous single-argument factory. Callers must say which one they have.
 */
public record Percentage(BigDecimal fraction) implements Comparable<Percentage> {

    /**
     * Rate precision. Deliberately finer than {@link Money#SCALE}: a rate is an intermediate, and rounding it to cents
     * would push error into every derived amount.
     */
    public static final int SCALE = 8;

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    public static final Percentage ZERO = Percentage.ofFraction(BigDecimal.ZERO);
    public static final Percentage ONE_HUNDRED_PERCENT = Percentage.ofPercent("100");

    public Percentage {
        Objects.requireNonNull(fraction, "fraction must not be null");
        fraction = fraction.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** {@code ofPercent("15")} is fifteen percent. */
    public static Percentage ofPercent(String percent) {
        return new Percentage(new BigDecimal(percent).divide(ONE_HUNDRED, SCALE, RoundingMode.HALF_UP));
    }

    /** {@code ofPercent(15)} is fifteen percent. */
    public static Percentage ofPercent(int percent) {
        return ofPercent(Integer.toString(percent));
    }

    /** {@code ofFraction(0.15)} is fifteen percent. */
    public static Percentage ofFraction(BigDecimal fraction) {
        return new Percentage(fraction);
    }

    public Percentage plus(Percentage other) {
        return new Percentage(this.fraction.add(other.fraction));
    }

    /** The remainder up to 100%. The agent's share is the complement of the company's. */
    public Percentage complement() {
        return new Percentage(ONE_HUNDRED_PERCENT.fraction.subtract(this.fraction));
    }

    public boolean isZero() {
        return this.fraction.signum() == 0;
    }

    @Override
    public int compareTo(Percentage other) {
        return this.fraction.compareTo(other.fraction);
    }

    /** Renders the percent form, trailing zeros stripped: {@code 15%}, {@code 2.5%}. */
    @Override
    public String toString() {
        return fraction.multiply(ONE_HUNDRED).stripTrailingZeros().toPlainString() + "%";
    }
}
