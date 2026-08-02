package com.revshare.domain.revshare;

import com.revshare.domain.shared.Money;
import java.time.LocalDate;
import java.time.Period;
import java.util.Objects;

/**
 * The Producing Agent Policy: revenue share is for agents who are still selling.
 *
 * <p>To collect on their downline's production, an agent must themselves have produced at least a minimum of gross
 * commission over a trailing window. An agent who stops selling keeps their position in the tree but stops being paid
 * from it.
 *
 * <p>Note that failing this test forfeits the payment; it does not redirect it. The unearned amount stays with the
 * company rather than rolling up to the next eligible ancestor, which is the same reason a departure does not compress
 * the tree: every beneficiary's tier is a function of structure alone, never of who happens to be eligible this month.
 * Making forfeiture roll upward would make each agent's earnings depend on their upline's sales activity, which is
 * neither the published behavior nor something an agent could reason about.
 *
 * <h2>Assumption: what is measured</h2>
 *
 * <p>"Production" is read here as gross commission income from closings in the window, the figure the commission split
 * is itself computed from. The published policy states a dollar threshold without naming the measure, and gross
 * commission is the only one that makes the threshold independent of an agent's cap status.
 */
public record ProducingAgentPolicy(Money minimumTrailingGross, Period trailingWindow) {

    public ProducingAgentPolicy {
        Objects.requireNonNull(minimumTrailingGross, "minimumTrailingGross must not be null");
        Objects.requireNonNull(trailingWindow, "trailingWindow must not be null");
        if (minimumTrailingGross.isNegative()) {
            throw new IllegalArgumentException(
                    "minimum trailing gross must not be negative, was " + minimumTrailingGross);
        }
        if (trailingWindow.isZero() || trailingWindow.isNegative()) {
            throw new IllegalArgumentException("trailing window must be a positive period, was " + trailingWindow);
        }
    }

    /** $450 of gross commission over the trailing six months. */
    public static ProducingAgentPolicy standard() {
        return new ProducingAgentPolicy(Money.of("450.00"), Period.ofMonths(6));
    }

    /**
     * The inclusive start of the window to measure, given the date being evaluated.
     *
     * <p>The window is anchored to the transaction being distributed, not to "now". Reprocessing a six-month-old
     * closing has to ask whether the beneficiary was producing <em>then</em>, otherwise a replay would silently pay or
     * withhold differently from the original run.
     */
    public LocalDate windowStart(LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf must not be null");
        return asOf.minus(trailingWindow);
    }

    /** Whether the measured trailing production clears the threshold. */
    public boolean isSatisfiedBy(Money trailingGross) {
        Objects.requireNonNull(trailingGross, "trailingGross must not be null");
        return trailingGross.isGreaterThanOrEqualTo(minimumTrailingGross);
    }

    @Override
    public String toString() {
        return "ProducingAgentPolicy[" + minimumTrailingGross + " gross in trailing " + trailingWindow + "]";
    }
}
