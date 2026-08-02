package com.revshare.domain.revshare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.revshare.domain.agent.AgentId;
import com.revshare.domain.agent.CapYear;
import com.revshare.domain.agent.EliteStatus;
import com.revshare.domain.commission.CapProgress;
import com.revshare.domain.commission.CommissionCalculator;
import com.revshare.domain.commission.CommissionPlan;
import com.revshare.domain.commission.CommissionSplit;
import com.revshare.domain.shared.Money;
import com.revshare.domain.transaction.ClosedTransaction;
import com.revshare.domain.transaction.TransactionId;
import com.revshare.domain.transaction.TransactionSide;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RevenueShareCalculatorTest {

    private static final CommissionPlan COMMISSION_PLAN = CommissionPlan.standard();
    private static final RevenueSharePlan PLAN = RevenueSharePlan.standard();
    private static final ProducingAgentPolicy PRODUCING = ProducingAgentPolicy.standard();

    private static final LocalDate JOINED = LocalDate.of(2024, 1, 15);
    private static final LocalDate CLOSED_ON = LocalDate.of(2024, 6, 1);
    private static final CapYear CAP_YEAR = CapYear.containing(JOINED, CLOSED_ON);

    /** Comfortably above the $450 trailing threshold. */
    private static final Money PRODUCING_GROSS = Money.of("25000.00");

    /** Enough producing frontline agents to have unlocked all five tiers. */
    private static final int ALL_TIERS_UNLOCKED = 20;

    private final RevenueShareCalculator calculator = new RevenueShareCalculator();
    private final CommissionCalculator commissionCalculator = new CommissionCalculator();

    /** The contributor, plus five ancestors above them. Index 0 is the contributor. */
    private final List<AgentId> chain = buildChain(6);

    @Nested
    @DisplayName("a pre-cap closing with a fully eligible upline")
    class FullyEligibleUpline {

        @Test
        @DisplayName("pays 5/4/3/2/1 percent of gross up the five tiers")
        void paysEachTierItsRate() {
            RevenueShareDistribution distribution = distribute(Money.of("10000.00"), allInGoodStanding());

            assertThat(distribution.awards()).hasSize(5);
            assertThat(awardTo(distribution, 1).awarded()).isEqualTo(Money.of("500.00"));
            assertThat(awardTo(distribution, 2).awarded()).isEqualTo(Money.of("400.00"));
            assertThat(awardTo(distribution, 3).awarded()).isEqualTo(Money.of("300.00"));
            assertThat(awardTo(distribution, 4).awarded()).isEqualTo(Money.of("200.00"));
            assertThat(awardTo(distribution, 5).awarded()).isEqualTo(Money.of("100.00"));
        }

        @Test
        @DisplayName("pays out exactly the company's 15% and nothing more")
        void totalPayoutEqualsTheCompanyDollar() {
            RevenueShareDistribution distribution = distribute(Money.of("10000.00"), allInGoodStanding());

            // The whole company share of this closing is redistributed to the upline.
            assertThat(distribution.totalAwarded()).isEqualTo(Money.of("1500.00"));
            assertThat(distribution.totalForfeited()).isEqualTo(Money.ZERO);
        }

        @Test
        @DisplayName("assigns each ancestor the tier matching their depth")
        void assignsTiersByDepth() {
            RevenueShareDistribution distribution = distribute(Money.of("10000.00"), allInGoodStanding());

            for (int depth = 1; depth <= 5; depth++) {
                assertThat(awardTo(distribution, depth).tier())
                        .isEqualTo(RevenueShareTier.atDepth(depth).orElseThrow());
            }
        }
    }

    @Nested
    @DisplayName("eligibility")
    class Eligibility {

        @Test
        @DisplayName("forfeits for a beneficiary who has left the brokerage")
        void forfeitsForDepartedBeneficiary() {
            Map<AgentId, BeneficiaryStanding> standings = allInGoodStanding();
            standings.put(
                    chain.get(2),
                    new BeneficiaryStanding(chain.get(2), false, PRODUCING_GROSS, ALL_TIERS_UNLOCKED, Money.ZERO));

            RevenueShareDistribution distribution = distribute(Money.of("10000.00"), standings);
            RevenueShareAward award = awardTo(distribution, 2);

            assertThat(award.awarded()).isEqualTo(Money.ZERO);
            assertThat(award.forfeited()).isEqualTo(Money.of("400.00"));
            assertThat(award.forfeitReason()).isEqualTo(ForfeitReason.BENEFICIARY_NOT_AFFILIATED);
        }

        @Test
        @DisplayName("does not promote anyone when a mid-chain beneficiary is ineligible")
        void ineligibilityDoesNotPromoteTheOthers() {
            // The agent at tier 2 forfeits. The agents at tiers 3, 4 and 5 keep paying at
            // their own rates rather than each shifting up one; the forfeited amount stays
            // with the company.
            Map<AgentId, BeneficiaryStanding> standings = allInGoodStanding();
            standings.put(
                    chain.get(2),
                    new BeneficiaryStanding(chain.get(2), false, PRODUCING_GROSS, ALL_TIERS_UNLOCKED, Money.ZERO));

            RevenueShareDistribution distribution = distribute(Money.of("10000.00"), standings);

            assertThat(awardTo(distribution, 3).awarded()).isEqualTo(Money.of("300.00"));
            assertThat(awardTo(distribution, 4).awarded()).isEqualTo(Money.of("200.00"));
            assertThat(awardTo(distribution, 5).awarded()).isEqualTo(Money.of("100.00"));
            assertThat(distribution.totalAwarded()).isEqualTo(Money.of("1100.00"));
            assertThat(distribution.totalForfeited()).isEqualTo(Money.of("400.00"));
        }

        @Test
        @DisplayName("forfeits a tier the beneficiary has not unlocked")
        void forfeitsLockedTier() {
            // Four producing frontline agents unlocks tier 1 only. This beneficiary sits at
            // tier 3 above the contributor, so they earn nothing from this closing.
            Map<AgentId, BeneficiaryStanding> standings = allInGoodStanding();
            standings.put(chain.get(3), new BeneficiaryStanding(chain.get(3), true, PRODUCING_GROSS, 4, Money.ZERO));

            RevenueShareAward award = awardTo(distribute(Money.of("10000.00"), standings), 3);

            assertThat(award.forfeitReason()).isEqualTo(ForfeitReason.TIER_LOCKED);
            assertThat(award.forfeited()).isEqualTo(Money.of("300.00"));
        }

        @Test
        @DisplayName("forfeits for a beneficiary below the $450 trailing production threshold")
        void forfeitsForNonProducingBeneficiary() {
            Map<AgentId, BeneficiaryStanding> standings = allInGoodStanding();
            standings.put(
                    chain.get(1),
                    new BeneficiaryStanding(chain.get(1), true, Money.of("449.99"), ALL_TIERS_UNLOCKED, Money.ZERO));

            RevenueShareAward award = awardTo(distribute(Money.of("10000.00"), standings), 1);

            assertThat(award.forfeitReason()).isEqualTo(ForfeitReason.BENEFICIARY_NOT_PRODUCING);
            assertThat(award.forfeited()).isEqualTo(Money.of("500.00"));
        }

        @Test
        @DisplayName("pays a beneficiary sitting exactly on the threshold")
        void thresholdIsInclusive() {
            Map<AgentId, BeneficiaryStanding> standings = allInGoodStanding();
            standings.put(
                    chain.get(1),
                    new BeneficiaryStanding(chain.get(1), true, Money.of("450.00"), ALL_TIERS_UNLOCKED, Money.ZERO));

            assertThat(awardTo(distribute(Money.of("10000.00"), standings), 1).awarded())
                    .isEqualTo(Money.of("500.00"));
        }
    }

    @Nested
    @DisplayName("annual tier maxima")
    class AnnualMaxima {

        @Test
        @DisplayName("pays only up to the remaining allowance and forfeits the excess")
        void paysPartiallyWhenTheAllowanceRunsOut() {
            // Tier 1 allows $4,000 per contributor per year; $3,800 is already drawn.
            Map<AgentId, BeneficiaryStanding> standings = allInGoodStanding();
            standings.put(
                    chain.get(1),
                    new BeneficiaryStanding(
                            chain.get(1), true, PRODUCING_GROSS, ALL_TIERS_UNLOCKED, Money.of("3800.00")));

            RevenueShareAward award = awardTo(distribute(Money.of("10000.00"), standings), 1);

            assertThat(award.entitlement()).isEqualTo(Money.of("500.00"));
            assertThat(award.awarded()).isEqualTo(Money.of("200.00"));
            assertThat(award.forfeited()).isEqualTo(Money.of("300.00"));
            assertThat(award.forfeitReason()).isEqualTo(ForfeitReason.ANNUAL_TIER_MAXIMUM_REACHED);
        }

        @Test
        @DisplayName("pays nothing once the allowance is exhausted")
        void paysNothingWhenExhausted() {
            Map<AgentId, BeneficiaryStanding> standings = allInGoodStanding();
            standings.put(
                    chain.get(1),
                    new BeneficiaryStanding(
                            chain.get(1), true, PRODUCING_GROSS, ALL_TIERS_UNLOCKED, Money.of("4000.00")));

            RevenueShareAward award = awardTo(distribute(Money.of("10000.00"), standings), 1);

            assertThat(award.awarded()).isEqualTo(Money.ZERO);
            assertThat(award.forfeitReason()).isEqualTo(ForfeitReason.ANNUAL_TIER_MAXIMUM_REACHED);
        }

        @Test
        @DisplayName("caps a tier 1 beneficiary at $4,000 across a contributor's whole cap year")
        void tierOneCannotExceedFourThousandFromOneAgent() {
            // Runs a capping agent's full year of production through both calculators,
            // accumulating the tier 1 sponsor's earnings the way the ledger would.
            CapProgress progress = CapProgress.opening(chain.get(0), CAP_YEAR, COMMISSION_PLAN);
            Money awardedSoFar = Money.ZERO;

            for (int i = 0; i < 12; i++) {
                ClosedTransaction transaction = transaction(Money.of("12000.00"));
                CommissionCalculator.CommissionResult priced =
                        commissionCalculator.calculate(transaction, progress, COMMISSION_PLAN, EliteStatus.STANDARD);
                progress = priced.progressAfter();

                Map<AgentId, BeneficiaryStanding> standings = allInGoodStanding();
                standings.put(
                        chain.get(1),
                        new BeneficiaryStanding(chain.get(1), true, PRODUCING_GROSS, ALL_TIERS_UNLOCKED, awardedSoFar));

                RevenueShareDistribution distribution =
                        calculator.distribute(priced.split(), pathOfContributor(), standings, PLAN, PRODUCING);

                if (!distribution.isEmpty()) {
                    awardedSoFar = awardedSoFar.plus(awardTo(distribution, 1).awarded());
                }
            }

            assertThat(progress.isCapped()).isTrue();
            assertThat(awardedSoFar)
                    .isEqualTo(PLAN.annualMaximumPerContributor(RevenueShareTier.TIER_1))
                    .isEqualTo(Money.of("4000.00"));
        }
    }

    @Nested
    @DisplayName("closings that fund nothing")
    class NoFunding {

        @Test
        @DisplayName("produces no awards for a post-cap closing")
        void postCapClosingProducesNoAwards() {
            CapProgress capped = CapProgress.opening(chain.get(0), CAP_YEAR, COMMISSION_PLAN)
                    .withContribution(COMMISSION_PLAN.annualCap());
            CommissionSplit split = commissionCalculator
                    .calculate(transaction(Money.of("30000.00")), capped, COMMISSION_PLAN, EliteStatus.STANDARD)
                    .split();

            RevenueShareDistribution distribution =
                    calculator.distribute(split, pathOfContributor(), allInGoodStanding(), PLAN, PRODUCING);

            assertThat(distribution.isEmpty()).isTrue();
            assertThat(distribution.totalAwarded()).isEqualTo(Money.ZERO);
        }

        @Test
        @DisplayName("produces no awards for an agent with no upline")
        void rootAgentProducesNoAwards() {
            CommissionSplit split = pricedSplit(Money.of("10000.00"));

            RevenueShareDistribution distribution = calculator.distribute(
                    split, com.revshare.domain.agent.SponsorshipPath.root(), Map.of(), PLAN, PRODUCING);

            assertThat(distribution.isEmpty()).isTrue();
        }
    }

    @Test
    @DisplayName("refuses to distribute when an ancestor's standing was not supplied")
    void failsLoudlyOnMissingStanding() {
        Map<AgentId, BeneficiaryStanding> incomplete = allInGoodStanding();
        incomplete.remove(chain.get(3));

        assertThatThrownBy(() -> distribute(Money.of("10000.00"), incomplete))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no standing supplied");
    }

    // --- helpers -----------------------------------------------------------------------

    private RevenueShareDistribution distribute(Money gross, Map<AgentId, BeneficiaryStanding> standings) {
        return calculator.distribute(pricedSplit(gross), pathOfContributor(), standings, PLAN, PRODUCING);
    }

    private CommissionSplit pricedSplit(Money gross) {
        return commissionCalculator
                .calculate(
                        transaction(gross),
                        CapProgress.opening(chain.get(0), CAP_YEAR, COMMISSION_PLAN),
                        COMMISSION_PLAN,
                        EliteStatus.STANDARD)
                .split();
    }

    private ClosedTransaction transaction(Money gross) {
        return new ClosedTransaction(
                TransactionId.newId(),
                chain.get(0),
                CLOSED_ON,
                Money.of("3000000.00"),
                gross,
                TransactionSide.LISTING,
                "PROP-TEST");
    }

    /** The contributor's upline: chain[1] at tier 1 through chain[5] at tier 5. */
    private com.revshare.domain.agent.SponsorshipPath pathOfContributor() {
        return new com.revshare.domain.agent.SponsorshipPath(chain.subList(1, chain.size()));
    }

    private Map<AgentId, BeneficiaryStanding> allInGoodStanding() {
        Map<AgentId, BeneficiaryStanding> standings = new LinkedHashMap<>();
        for (int i = 1; i < chain.size(); i++) {
            standings.put(
                    chain.get(i),
                    BeneficiaryStanding.inGoodStanding(chain.get(i), PRODUCING_GROSS, ALL_TIERS_UNLOCKED));
        }
        return standings;
    }

    private RevenueShareAward awardTo(RevenueShareDistribution distribution, int depth) {
        return distribution.awards().stream()
                .filter(award -> award.beneficiary().equals(chain.get(depth)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no award for the agent at depth " + depth));
    }

    private static List<AgentId> buildChain(int size) {
        List<AgentId> ids = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ids.add(AgentId.newId());
        }
        return List.copyOf(ids);
    }
}
