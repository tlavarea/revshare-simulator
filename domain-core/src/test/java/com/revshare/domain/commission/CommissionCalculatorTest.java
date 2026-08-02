package com.revshare.domain.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.revshare.domain.agent.AgentId;
import com.revshare.domain.agent.CapYear;
import com.revshare.domain.agent.EliteStatus;
import com.revshare.domain.commission.CommissionCalculator.CommissionResult;
import com.revshare.domain.shared.Money;
import com.revshare.domain.transaction.ClosedTransaction;
import com.revshare.domain.transaction.TransactionId;
import com.revshare.domain.transaction.TransactionSide;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CommissionCalculatorTest {

    private static final CommissionPlan PLAN = CommissionPlan.standard();
    private static final AgentId AGENT = AgentId.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDate JOINED = LocalDate.of(2024, 3, 14);
    private static final CapYear CAP_YEAR = CapYear.containing(JOINED, LocalDate.of(2024, 6, 1));
    private static final LocalDate CLOSED_ON = LocalDate.of(2024, 6, 1);

    private final CommissionCalculator calculator = new CommissionCalculator();

    @Nested
    @DisplayName("below the cap")
    class BelowCap {

        @Test
        @DisplayName("splits 85/15 and puts the whole company share toward the cap")
        void splitsEightyFiveFifteen() {
            CommissionResult result =
                    calculator.calculate(transaction("10000.00"), openingProgress(), PLAN, EliteStatus.STANDARD);

            assertThat(result.split().agentEarnings()).isEqualTo(Money.of("8500.00"));
            assertThat(result.split().companyEarnings()).isEqualTo(Money.of("1500.00"));
            assertThat(result.split().capContribution()).isEqualTo(Money.of("1500.00"));
            assertThat(result.split().postCapFeeCharged()).isEqualTo(Money.ZERO);
            assertThat(result.progressAfter().contributed()).isEqualTo(Money.of("1500.00"));
            assertThat(result.progressAfter().isCapped()).isFalse();
        }

        @Test
        @DisplayName("makes the entire gross commission eligible for revenue share")
        void wholeGrossFundsRevenueShare() {
            CommissionResult result =
                    calculator.calculate(transaction("10000.00"), openingProgress(), PLAN, EliteStatus.STANDARD);

            assertThat(result.split().revenueShareEligibleGross()).isEqualTo(Money.of("10000.00"));
        }
    }

    @Nested
    @DisplayName("reaching the cap")
    class ReachingCap {

        @Test
        @DisplayName("caps exactly on $80,000 of gross commission, which is $12,000 at 15%")
        void capsAtEightyThousandGross() {
            CommissionResult result =
                    calculator.calculate(transaction("80000.00"), openingProgress(), PLAN, EliteStatus.STANDARD);

            assertThat(result.split().companyEarnings()).isEqualTo(Money.of("12000.00"));
            assertThat(result.split().agentEarnings()).isEqualTo(Money.of("68000.00"));
            assertThat(result.progressAfter().isCapped()).isTrue();
            assertThat(result.reachedCap()).isTrue();
        }

        @Test
        @DisplayName("pays the agent the portion of the split that overshoots the cap")
        void overshootGoesToTheAgent() {
            // $11,000 already contributed, so only $1,000 of cap remains. A $20,000 closing
            // would otherwise hand the company $3,000; the $2,000 excess is the agent's.
            CapProgress nearlyCapped = openingProgress().withContribution(Money.of("11000.00"));

            CommissionResult result =
                    calculator.calculate(transaction("20000.00"), nearlyCapped, PLAN, EliteStatus.STANDARD);

            assertThat(result.split().capContribution()).isEqualTo(Money.of("1000.00"));
            assertThat(result.split().companyEarnings()).isEqualTo(Money.of("1000.00"));
            assertThat(result.split().agentEarnings()).isEqualTo(Money.of("19000.00"));
            assertThat(result.reachedCap()).isTrue();
        }

        @Test
        @DisplayName("charges no flat fee on the transaction that crosses the cap")
        void noFlatFeeOnTheCrossingTransaction() {
            CapProgress nearlyCapped = openingProgress().withContribution(Money.of("11000.00"));

            CommissionResult result =
                    calculator.calculate(transaction("20000.00"), nearlyCapped, PLAN, EliteStatus.STANDARD);

            assertThat(result.split().postCapFeeCharged()).isEqualTo(Money.ZERO);
            assertThat(result.split().pricedUnderPostCapFee()).isFalse();
        }

        @Test
        @DisplayName("makes only the pre-crossing slice of gross eligible for revenue share")
        void onlyThePreCrossingSliceFundsRevenueShare() {
            CapProgress nearlyCapped = openingProgress().withContribution(Money.of("11000.00"));

            CommissionResult result =
                    calculator.calculate(transaction("20000.00"), nearlyCapped, PLAN, EliteStatus.STANDARD);

            // $1,000 of company dollar was generated, which at a 15% split corresponds to
            // $6,666.67 of gross commission, not the full $20,000.
            assertThat(result.split().revenueShareEligibleGross()).isEqualTo(Money.of("6666.67"));
        }
    }

    @Nested
    @DisplayName("above the cap")
    class AboveCap {

        @Test
        @DisplayName("charges the flat $285 fee instead of the split")
        void chargesFlatFee() {
            CommissionResult result =
                    calculator.calculate(transaction("9000.00"), cappedProgress(), PLAN, EliteStatus.STANDARD);

            assertThat(result.split().companyEarnings()).isEqualTo(Money.of("285.00"));
            assertThat(result.split().agentEarnings()).isEqualTo(Money.of("8715.00"));
            assertThat(result.split().pricedUnderPostCapFee()).isTrue();
        }

        @Test
        @DisplayName("charges Elite agents the reduced $129 fee")
        void chargesEliteFee() {
            CommissionResult result =
                    calculator.calculate(transaction("9000.00"), cappedProgress(), PLAN, EliteStatus.ELITE);

            assertThat(result.split().companyEarnings()).isEqualTo(Money.of("129.00"));
            assertThat(result.split().agentEarnings()).isEqualTo(Money.of("8871.00"));
        }

        @Test
        @DisplayName("adds nothing further to the cap")
        void doesNotAdvanceTheCap() {
            CapProgress capped = cappedProgress();

            CommissionResult result = calculator.calculate(transaction("9000.00"), capped, PLAN, EliteStatus.STANDARD);

            assertThat(result.split().capContribution()).isEqualTo(Money.ZERO);
            assertThat(result.progressAfter()).isEqualTo(capped);
        }

        @Test
        @DisplayName("generates no revenue share for the upline")
        void generatesNoRevenueShare() {
            CommissionResult result =
                    calculator.calculate(transaction("9000.00"), cappedProgress(), PLAN, EliteStatus.STANDARD);

            assertThat(result.split().revenueShareEligibleGross()).isEqualTo(Money.ZERO);
            assertThat(result.split().generatesRevenueShare()).isFalse();
        }

        @Test
        @DisplayName("never charges more than the gross commission on a tiny closing")
        void clampsFeeToGross() {
            CommissionResult result =
                    calculator.calculate(transaction("100.00"), cappedProgress(), PLAN, EliteStatus.STANDARD);

            assertThat(result.split().companyEarnings()).isEqualTo(Money.of("100.00"));
            assertThat(result.split().agentEarnings()).isEqualTo(Money.ZERO);
        }
    }

    @Nested
    @DisplayName("across a whole cap year")
    class WholeCapYear {

        @Test
        @DisplayName("collects exactly the cap, plus one flat fee per post-cap closing")
        void collectsExactlyTheCap() {
            // Deliberately awkward amounts, including one that straddles the cap.
            String[] closings = {"18333.33", "27500.01", "9999.99", "41200.55", "6800.12", "150000.00", "725.44"};

            CapProgress progress = openingProgress();
            Money companyTotal = Money.ZERO;
            Money agentTotal = Money.ZERO;
            Money grossTotal = Money.ZERO;
            Money feeTotal = Money.ZERO;

            for (String gross : closings) {
                CommissionResult result =
                        calculator.calculate(transaction(gross), progress, PLAN, EliteStatus.STANDARD);
                progress = result.progressAfter();
                companyTotal = companyTotal.plus(result.split().companyEarnings());
                agentTotal = agentTotal.plus(result.split().agentEarnings());
                grossTotal = grossTotal.plus(result.split().grossCommissionIncome());
                feeTotal = feeTotal.plus(result.split().postCapFeeCharged());
            }

            // The cap is hit exactly, never approximately and never overshot, even though
            // the individual 15% shares round to the cent.
            assertThat(progress.isCapped()).isTrue();
            assertThat(progress.contributed()).isEqualTo(Money.of("12000.00"));

            // Everything the company collects beyond the cap is flat fees, nothing else.
            assertThat(companyTotal).isEqualTo(PLAN.annualCap().plus(feeTotal));

            // And no money is created or destroyed along the way.
            assertThat(agentTotal.plus(companyTotal)).isEqualTo(grossTotal);
        }

        @Test
        @DisplayName("makes exactly $80,000 of gross eligible for revenue share")
        void eligibleGrossEqualsTheGrossRequiredToCap() {
            // The invariant the published per-tier annual maxima are derived from: however
            // much an agent produces, only $80,000 of it can ever fund revenue share in one
            // cap year, so tier 1 at 5% can never pay more than $4,000 from that agent.
            // Amounts chosen so that 15% of each is a whole number of cents, isolating the
            // rule from the rounding behaviour exercised by the next test.
            String[] closings = {"20000.00", "20000.00", "20000.00", "20000.00", "5000.00"};

            CapProgress progress = openingProgress();
            Money eligibleTotal = Money.ZERO;

            for (String gross : closings) {
                CommissionResult result =
                        calculator.calculate(transaction(gross), progress, PLAN, EliteStatus.STANDARD);
                progress = result.progressAfter();
                eligibleTotal = eligibleTotal.plus(result.split().revenueShareEligibleGross());
            }

            assertThat(progress.isCapped()).isTrue();
            assertThat(eligibleTotal).isEqualTo(PLAN.grossCommissionRequiredToCap());
            assertThat(eligibleTotal).isEqualTo(Money.of("80000.00"));
        }

        @Test
        @DisplayName("keeps eligible gross within a few cents of $80,000 on awkward amounts")
        void eligibleGrossDriftsOnlyByRoundingOnAwkwardAmounts() {
            // When 15% of a closing is not a whole number of cents, the company share is
            // rounded before it is applied to the cap. The cap is therefore consumed in
            // rounded increments, and the straddling closing's eligible slice, recovered by
            // dividing the rounded remainder back out, cannot land on the exact figure.
            //
            // The drift is bounded: at most half a cent of rounding per pre-cap closing,
            // divided by the 15% split, so roughly 3.4 cents each. It is left uncorrected
            // because the alternative, back-solving the cap from unrounded shares, would
            // mean the cap contributions recorded against an agent no longer sum to the
            // amounts actually collected from them.
            String[] closings = {"33333.33", "12500.75", "44821.19", "90000.00", "1200.00"};
            int preCapClosings = 3;

            CapProgress progress = openingProgress();
            Money eligibleTotal = Money.ZERO;

            for (String gross : closings) {
                CommissionResult result =
                        calculator.calculate(transaction(gross), progress, PLAN, EliteStatus.STANDARD);
                progress = result.progressAfter();
                eligibleTotal = eligibleTotal.plus(result.split().revenueShareEligibleGross());
            }

            // The cap itself is still exact; only the derived gross figure drifts.
            assertThat(progress.contributed()).isEqualTo(Money.of("12000.00"));
            assertThat(eligibleTotal.amount())
                    .isCloseTo(
                            new BigDecimal("80000.00"),
                            within(new BigDecimal("0.05").multiply(BigDecimal.valueOf(preCapClosings))));
        }

        @Test
        @DisplayName("reports reaching the cap on exactly one closing")
        void capIsReachedExactlyOnce() {
            String[] closings = {"30000.00", "30000.00", "30000.00", "30000.00"};

            CapProgress progress = openingProgress();
            int capEvents = 0;

            for (String gross : closings) {
                CommissionResult result =
                        calculator.calculate(transaction(gross), progress, PLAN, EliteStatus.STANDARD);
                progress = result.progressAfter();
                if (result.reachedCap()) {
                    capEvents++;
                }
            }

            assertThat(capEvents).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("guards")
    class Guards {

        @Test
        @DisplayName("rejects cap progress belonging to a different agent")
        void rejectsMismatchedAgent() {
            CapProgress someoneElse = CapProgress.opening(AgentId.newId(), CAP_YEAR, PLAN);

            assertThatThrownBy(
                            () -> calculator.calculate(transaction("1000.00"), someoneElse, PLAN, EliteStatus.STANDARD))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("belongs to agent");
        }

        @Test
        @DisplayName("rejects a closing that falls outside the cap year being tracked")
        void rejectsWrongCapYear() {
            ClosedTransaction nextYear = new ClosedTransaction(
                    TransactionId.newId(),
                    AGENT,
                    LocalDate.of(2025, 9, 1),
                    Money.of("500000.00"),
                    Money.of("15000.00"),
                    TransactionSide.LISTING,
                    "PROP-OUT-OF-RANGE");

            assertThatThrownBy(() -> calculator.calculate(nextYear, openingProgress(), PLAN, EliteStatus.STANDARD))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not fall within cap year");
        }
    }

    private static CapProgress openingProgress() {
        return CapProgress.opening(AGENT, CAP_YEAR, PLAN);
    }

    private static CapProgress cappedProgress() {
        return openingProgress().withContribution(PLAN.annualCap());
    }

    private static ClosedTransaction transaction(String grossCommission) {
        return new ClosedTransaction(
                TransactionId.of(UUID.randomUUID()),
                AGENT,
                CLOSED_ON,
                Money.of("2000000.00"),
                Money.of(grossCommission),
                TransactionSide.LISTING,
                "PROP-TEST");
    }
}
