package com.revshare.seed;

import com.revshare.domain.agent.Agent;
import com.revshare.domain.shared.Money;
import com.revshare.domain.transaction.ClosedTransaction;
import com.revshare.domain.transaction.TransactionId;
import com.revshare.domain.transaction.TransactionSide;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Produces the stream of closed transactions the whole simulation replays.
 *
 * <h2>Where the realism actually matters</h2>
 *
 * <p>Three properties of the distribution decide whether the generated data exercises the rules or merely fills a
 * table.
 *
 * <p><strong>Production is drawn per agent, not per transaction.</strong> Each agent gets one lognormal draw for their
 * annual pace and keeps it for their whole career. Redrawing per transaction would make every agent regress to the
 * median over a few years, and the population of agents who cap, the only population revenue share pays out on, would
 * vanish.
 *
 * <p><strong>The pace is heavily skewed.</strong> The median agent closes about four deals a year and never comes close
 * to $80,000 of gross commission; the top few percent close dozens and cap early. That spread is what puts data on both
 * sides of every cap-related branch, including the transaction that straddles the cap.
 *
 * <p><strong>Closings are seasonal.</strong> Deals cluster in spring and summer, so an agent's cap year fills unevenly.
 * An agent whose anniversary falls in January reaches the cap at a different point in their year than one whose
 * anniversary falls in July, which is precisely the behaviour an anniversary-based cap window exists to model and a
 * calendar-year implementation would hide.
 */
final class TransactionStreamGenerator {

    /**
     * Relative closing volume by month, January first. A mild northern-hemisphere spring and summer peak; the exact
     * figures matter less than the fact that the year is uneven.
     */
    private static final double[] SEASONAL_WEIGHTS = {
        0.65, 0.70, 0.90, 1.05, 1.20, 1.25, 1.15, 1.10, 1.00, 0.95, 0.80, 0.70
    };

    private static final double PEAK_WEIGHT = 1.25;
    private static final int SEASONAL_RESAMPLE_ATTEMPTS = 8;
    private static final double DAYS_PER_YEAR = 365.25;

    private final SeedConfig config;
    private final SeedRandom random;

    TransactionStreamGenerator(SeedConfig config, SeedRandom random) {
        this.config = config;
        this.random = random;
    }

    /** All closings across the roster, ordered as they would have arrived. */
    List<ClosedTransaction> generate(List<Agent> roster) {
        List<ClosedTransaction> transactions = new ArrayList<>();
        int propertyOrdinal = 0;

        for (Agent agent : roster) {
            LocalDate from = agent.joinedOn();
            LocalDate to = agent.terminatedOn()
                    .map(terminated ->
                            terminated.isBefore(config.simulationEnd()) ? terminated : config.simulationEnd())
                    .orElse(config.simulationEnd());

            if (!to.isAfter(from)) {
                continue;
            }

            double yearsActive = ChronoUnit.DAYS.between(from, to) / DAYS_PER_YEAR;
            double annualPace = random.nextLogNormal(config.medianAnnualClosings(), config.closingsLogSigma());
            int closings = (int) Math.round(annualPace * yearsActive);

            for (int i = 0; i < closings; i++) {
                transactions.add(closing(agent, from, to, ++propertyOrdinal));
            }
        }

        // Ordered by close date, then by id so that ties resolve deterministically. This is
        // the order a consumer would have seen the events, which is what makes the file
        // replayable against the write side.
        transactions.sort(Comparator.comparing(ClosedTransaction::closedOn).thenComparing(t -> t.id().value()));

        return List.copyOf(transactions);
    }

    private ClosedTransaction closing(Agent agent, LocalDate from, LocalDate to, int propertyOrdinal) {
        LocalDate closedOn = seasonalDate(from, to);

        BigDecimal salePrice =
                SeedRandom.salePrice(random.nextLogNormal(config.medianSalePrice(), config.salePriceLogSigma()));

        TransactionSide side = random.nextChance(config.dualAgencyProbability())
                ? TransactionSide.DUAL
                : (random.nextChance(0.5) ? TransactionSide.LISTING : TransactionSide.BUYING);

        double commissionRate = random.nextDouble(config.minCommissionRate(), config.maxCommissionRate());
        BigDecimal gross = SeedRandom.money(salePrice.doubleValue() * commissionRate * side.commissionSides());

        return new ClosedTransaction(
                TransactionId.of(random.nextUuid()),
                agent.id(),
                closedOn,
                Money.of(salePrice),
                Money.of(gross),
                side,
                NameCatalog.propertyReference(propertyOrdinal));
    }

    /**
     * A closing date in {@code [from, to)}, biased toward the busy months.
     *
     * <p>Rejection sampling against the seasonal weights, with a bounded number of attempts so a narrow window that
     * happens to sit entirely in a quiet month still terminates. Falling back to the last draw skews that rare case
     * slightly toward uniform, which is a better failure mode than looping.
     */
    private LocalDate seasonalDate(LocalDate from, LocalDate to) {
        LocalDate candidate = random.nextDate(from, to);
        for (int attempt = 0; attempt < SEASONAL_RESAMPLE_ATTEMPTS; attempt++) {
            double weight = SEASONAL_WEIGHTS[candidate.getMonthValue() - 1];
            if (random.nextUnit() < weight / PEAK_WEIGHT) {
                return candidate;
            }
            candidate = random.nextDate(from, to);
        }
        return candidate;
    }
}
