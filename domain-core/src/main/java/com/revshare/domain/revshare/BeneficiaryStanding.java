package com.revshare.domain.revshare;

import com.revshare.domain.agent.AgentId;
import com.revshare.domain.shared.Money;
import java.util.Objects;

/**
 * Everything the distribution rules need to know about one potential beneficiary, resolved as at the moment the
 * contributing transaction closed.
 *
 * <p>This type exists so {@link RevenueShareCalculator} can stay a pure function. The facts here are all expensive
 * lookups against repositories: affiliation, trailing production, how many frontline agents are currently producing,
 * how much of the annual allowance is already spent. Injecting four ports into the calculator would drag persistence
 * into the one place in the system that most benefits from having none, and would make every rule test a mocking
 * exercise. Instead the application service gathers the facts, and the domain service decides what they mean.
 *
 * <p>Every field is point-in-time, anchored to the closing date rather than to now. Replaying an old transaction has to
 * reach the same verdict it reached originally.
 */
public record BeneficiaryStanding(
        AgentId agentId,
        boolean affiliated,
        Money trailingGrossCommission,
        int producingFrontlineCount,
        Money alreadyAwardedFromContributorThisCapYear) {

    public BeneficiaryStanding {
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(trailingGrossCommission, "trailingGrossCommission must not be null");
        Objects.requireNonNull(
                alreadyAwardedFromContributorThisCapYear, "alreadyAwardedFromContributorThisCapYear must not be null");

        if (trailingGrossCommission.isNegative()) {
            throw new IllegalArgumentException(
                    "trailing gross commission must not be negative, was " + trailingGrossCommission);
        }
        if (producingFrontlineCount < 0) {
            throw new IllegalArgumentException(
                    "producing frontline count must not be negative, was " + producingFrontlineCount);
        }
        if (alreadyAwardedFromContributorThisCapYear.isNegative()) {
            throw new IllegalArgumentException("already-awarded amount must not be negative");
        }
    }

    /**
     * A beneficiary in good standing who has not yet drawn anything from this contributor. Convenience for tests and
     * for the seeded simulation.
     */
    public static BeneficiaryStanding inGoodStanding(
            AgentId agentId, Money trailingGrossCommission, int producingFrontlineCount) {
        return new BeneficiaryStanding(agentId, true, trailingGrossCommission, producingFrontlineCount, Money.ZERO);
    }
}
