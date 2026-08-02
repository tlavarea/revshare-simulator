package com.revshare.domain.revshare;

import static org.assertj.core.api.Assertions.assertThat;

import com.revshare.domain.commission.CommissionPlan;
import com.revshare.domain.shared.Money;
import com.revshare.domain.shared.Percentage;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the revenue share schedule to the commission plan that funds it.
 *
 * <p>The point of these tests is that the published per-tier annual maxima are not independent figures to be typed in
 * and maintained. They fall out of the split, the cap, and the tier rates, and if they ever stop falling out, one of
 * the four is wrong.
 */
class RevenueSharePlanTest {

    private final RevenueSharePlan plan = RevenueSharePlan.standard();

    @Test
    @DisplayName("an agent must produce $80,000 of gross commission to cap at $12,000")
    void grossRequiredToCapIsDerivedFromTheSplit() {
        assertThat(plan.commissionPlan().grossCommissionRequiredToCap()).isEqualTo(Money.of("80000.00"));
    }

    @ParameterizedTest(name = "{0} pays at most {1} per capping agent per year")
    @MethodSource("publishedAnnualMaxima")
    @DisplayName("each tier's published annual maximum is exactly its rate applied to $80,000")
    void annualMaximaMatchThePublishedFigures(RevenueShareTier tier, String publishedMaximum) {
        assertThat(plan.annualMaximumPerContributor(tier)).isEqualTo(Money.of(publishedMaximum));
    }

    static Stream<Arguments> publishedAnnualMaxima() {
        return Stream.of(
                Arguments.of(RevenueShareTier.TIER_1, "4000.00"),
                Arguments.of(RevenueShareTier.TIER_2, "3200.00"),
                Arguments.of(RevenueShareTier.TIER_3, "2400.00"),
                Arguments.of(RevenueShareTier.TIER_4, "1600.00"),
                Arguments.of(RevenueShareTier.TIER_5, "800.00"));
    }

    @Test
    @DisplayName("the five tier rates sum to the company's entire 15% share")
    void tierRatesSumToTheCompanySplit() {
        // Revenue share is paid out of the company dollar, not in addition to it. A fully
        // unlocked five-deep upline can therefore consume the whole of it, and never more.
        assertThat(RevenueShareTier.totalPayoutRate())
                .isEqualTo(Percentage.ofPercent("15"))
                .isEqualTo(plan.commissionPlan().companySplit());
    }

    @Test
    @DisplayName("the whole upline can draw at most the commission cap from one agent")
    void allTiersTogetherCannotExceedTheCap() {
        // $4,000 + $3,200 + $2,400 + $1,600 + $800 = $12,000, which is the cap itself.
        // The company can pay out everything it collected from a capping agent, and not a
        // cent beyond it.
        assertThat(plan.annualMaximumAcrossAllTiers())
                .isEqualTo(plan.commissionPlan().annualCap())
                .isEqualTo(Money.of("12000.00"));
    }

    @Test
    @DisplayName("the derived maxima track a change to the cap")
    void maximaFollowTheCommissionPlan() {
        // Raise the cap and every tier maximum moves on its own, in proportion. This is
        // what deriving them buys: the schedule cannot drift out of internal consistency.
        CommissionPlan raisedCap = new CommissionPlan(
                Percentage.ofPercent("15"), Money.of("15000.00"),
                Money.of("285.00"), Money.of("129.00"));
        RevenueSharePlan revised = new RevenueSharePlan(raisedCap);

        assertThat(raisedCap.grossCommissionRequiredToCap()).isEqualTo(Money.of("100000.00"));
        assertThat(revised.annualMaximumPerContributor(RevenueShareTier.TIER_1)).isEqualTo(Money.of("5000.00"));
        assertThat(revised.annualMaximumAcrossAllTiers()).isEqualTo(Money.of("15000.00"));
    }

    @Test
    @DisplayName("tier 1 unlocks automatically, later tiers need producing frontline agents")
    void tierUnlockThresholds() {
        assertThat(RevenueShareTier.unlockedFor(0)).containsExactly(RevenueShareTier.TIER_1);
        assertThat(RevenueShareTier.unlockedFor(4)).containsExactly(RevenueShareTier.TIER_1);
        assertThat(RevenueShareTier.unlockedFor(5)).containsExactly(RevenueShareTier.TIER_1, RevenueShareTier.TIER_2);
        assertThat(RevenueShareTier.unlockedFor(20)).containsExactly(RevenueShareTier.values());
        assertThat(RevenueShareTier.unlockedFor(1_000)).containsExactly(RevenueShareTier.values());
    }
}
