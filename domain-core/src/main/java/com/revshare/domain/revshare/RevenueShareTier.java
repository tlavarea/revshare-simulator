package com.revshare.domain.revshare;

import com.revshare.domain.agent.SponsorshipPath;
import com.revshare.domain.shared.Percentage;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * The five levels of the revenue share program, and what each pays.
 *
 * <p>Depth is measured along an agent's frozen {@link SponsorshipPath}: an agent's tier 1 is the people they personally
 * sponsored, tier 2 is the people <em>those</em> agents sponsored, and so on. Because the path never changes, a
 * departure in the middle of a chain does not promote anyone. See {@link SponsorshipPath} for why that matters.
 *
 * <p>The rates sum to exactly 15%, which is the company's entire share of a pre-cap closing. That is not a coincidence
 * but the defining property of the program: revenue share is paid <em>out of</em> the company dollar rather than in
 * addition to it, so a fully unlocked five-deep upline can consume the whole of it. {@link RevenueSharePlan} asserts
 * this against the commission plan at construction.
 *
 * <h2>Assumption: unlock thresholds</h2>
 *
 * <p>Tier 1 unlocks automatically, with a threshold of zero. Tiers 2 through 5 require a growing number of
 * <em>producing</em> personally-sponsored agents. The specific counts modeled here (5, 10, 15, 20) are a plausible
 * reading of a published schedule that states the requirement exists without fixing the numbers; they live in this enum
 * precisely so that correcting them is a one-line change rather than a hunt through the calculator.
 */
public enum RevenueShareTier {
    TIER_1(1, "5", 0),
    TIER_2(2, "4", 5),
    TIER_3(3, "3", 10),
    TIER_4(4, "2", 15),
    TIER_5(5, "1", 20);

    private final int depth;
    private final Percentage rate;
    private final int producingFrontlineRequired;

    RevenueShareTier(int depth, String ratePercent, int producingFrontlineRequired) {
        this.depth = depth;
        this.rate = Percentage.ofPercent(ratePercent);
        this.producingFrontlineRequired = producingFrontlineRequired;
    }

    /** How many sponsorship levels below the beneficiary this tier sits. 1-based. */
    public int depth() {
        return depth;
    }

    /** The share of a contributor's revenue-share-eligible gross commission this tier pays. */
    public Percentage rate() {
        return rate;
    }

    /** Producing personally-sponsored agents needed before this tier pays anything. */
    public int producingFrontlineRequired() {
        return producingFrontlineRequired;
    }

    /** The tier at a given 1-based depth, or empty beyond the program's reach. */
    public static Optional<RevenueShareTier> atDepth(int depth) {
        return Arrays.stream(values()).filter(t -> t.depth == depth).findFirst();
    }

    /** The tiers a beneficiary with this many producing frontline agents can earn from. */
    public static Set<RevenueShareTier> unlockedFor(int producingFrontlineCount) {
        if (producingFrontlineCount < 0) {
            throw new IllegalArgumentException(
                    "producing frontline count must not be negative, was " + producingFrontlineCount);
        }
        EnumSet<RevenueShareTier> unlocked = EnumSet.noneOf(RevenueShareTier.class);
        for (RevenueShareTier tier : values()) {
            if (producingFrontlineCount >= tier.producingFrontlineRequired) {
                unlocked.add(tier);
            }
        }
        return unlocked;
    }

    /** The sum of all five tier rates. Expected to equal the company's split. */
    public static Percentage totalPayoutRate() {
        return Arrays.stream(values()).map(RevenueShareTier::rate).reduce(Percentage.ZERO, Percentage::plus);
    }

    @Override
    public String toString() {
        return "Tier " + depth + " (" + rate + ")";
    }
}
