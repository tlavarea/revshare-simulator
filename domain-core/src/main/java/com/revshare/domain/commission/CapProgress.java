package com.revshare.domain.commission;

import com.revshare.domain.agent.AgentId;
import com.revshare.domain.agent.CapYear;
import com.revshare.domain.shared.Money;
import com.revshare.domain.shared.Percentage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * How far one agent has progressed toward their commission cap within one cap year.
 *
 * <p>Aggregate keyed by (agent, cap year). Separate from {@link com.revshare.domain.agent.Agent} because it has a
 * different lifecycle: an agent is one long-lived record, while cap progress is a fresh instance every anniversary, and
 * the two are written on completely different schedules. Keeping them apart also keeps the write path narrow, since
 * closing a transaction contends only on this row rather than on the agent record.
 *
 * <p>Immutable: {@link #withContribution} returns a new instance. The commission engine is a pure function of
 * (transaction, prior progress, plan), and making progress immutable is what lets a statement be replayed from the
 * event log and land on the same numbers.
 *
 * <p><strong>Concurrency note for the persistence adapter:</strong> two transactions closing simultaneously for the
 * same agent both read the same prior progress, and a last-write-wins update would lose one contribution and let the
 * agent over-earn past the cap. This aggregate must be updated under optimistic locking (a version column) or a
 * {@code SELECT ... FOR UPDATE}, with the caller retrying on conflict.
 */
public record CapProgress(AgentId agentId, CapYear capYear, Money contributed, Money capAmount) {

    public CapProgress {
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(capYear, "capYear must not be null");
        Objects.requireNonNull(contributed, "contributed must not be null");
        Objects.requireNonNull(capAmount, "capAmount must not be null");

        if (contributed.isNegative()) {
            throw new IllegalArgumentException("contributed must not be negative, was " + contributed);
        }
        if (!capAmount.isPositive()) {
            throw new IllegalArgumentException("cap amount must be positive, was " + capAmount);
        }
        // The central invariant: an agent can never contribute more than the cap. If this
        // trips, the calculator failed to clamp a cap-crossing transaction.
        if (contributed.isGreaterThan(capAmount)) {
            throw new IllegalArgumentException(
                    "contributed " + contributed + " exceeds cap " + capAmount + " for agent " + agentId);
        }
    }

    /** A fresh cap year with nothing yet contributed. */
    public static CapProgress opening(AgentId agentId, CapYear capYear, CommissionPlan plan) {
        return new CapProgress(agentId, capYear, Money.ZERO, plan.annualCap());
    }

    /** How much company dollar the agent still owes before capping. */
    public Money remaining() {
        return capAmount.minus(contributed).atLeastZero();
    }

    public boolean isCapped() {
        return contributed.isGreaterThanOrEqualTo(capAmount);
    }

    /**
     * Records company dollar against the cap.
     *
     * <p>Rejects an over-contribution rather than silently clamping. Clamping is the calculator's job, and it has to
     * happen there because the amount that exceeds the cap does not vanish, it is paid to the agent. Absorbing it
     * quietly here would make that money disappear from the split.
     */
    public CapProgress withContribution(Money amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        if (amount.isNegative()) {
            throw new IllegalArgumentException("contribution must not be negative, was " + amount);
        }
        if (amount.isGreaterThan(remaining())) {
            throw new IllegalArgumentException("contribution " + amount + " exceeds remaining cap " + remaining()
                    + "; the calculator must clamp cap-crossing transactions");
        }
        return new CapProgress(agentId, capYear, contributed.plus(amount), capAmount);
    }

    /** Progress toward the cap, for dashboard display. */
    public Percentage percentComplete() {
        if (capAmount.isZero()) {
            return Percentage.ZERO;
        }
        BigDecimal fraction = contributed.amount().divide(capAmount.amount(), Percentage.SCALE, RoundingMode.HALF_UP);
        return Percentage.ofFraction(fraction);
    }

    @Override
    public String toString() {
        return "CapProgress[" + agentId + " " + capYear + ": " + contributed + " of " + capAmount
                + (isCapped() ? " CAPPED" : "") + "]";
    }
}
