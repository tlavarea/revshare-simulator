package com.revshare.seed;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Every knob the generator turns, in one place.
 *
 * <p>The distribution parameters are the interesting part. Real estate production is violently skewed: the median agent
 * closes a handful of deals a year while the top few percent close dozens, and a generator that draws transaction
 * counts uniformly produces a brokerage where either everybody caps or nobody does. Neither exercises the rules. The
 * lognormal defaults here are chosen so that a realistic minority of agents reach the $12,000 cap, which is the
 * population the revenue share program actually pays out on.
 *
 * @param randomSeed fixes the entire output; the same seed always regenerates the identical brokerage, down to the
 *     UUIDs
 * @param agentCount how many agents to enroll
 * @param founderCount agents with no sponsor, seeding the top of the tree
 * @param simulationStart earliest join date
 * @param simulationEnd exclusive end of the transaction window
 * @param medianAnnualClosings median deals per agent per year, before skew
 * @param closingsLogSigma spread of the production lognormal; larger means a heavier tail of top producers
 * @param medianSalePrice median sale price in dollars
 * @param salePriceLogSigma spread of the sale price lognormal
 * @param minCommissionRate lowest per-side commission rate, as a fraction
 * @param maxCommissionRate highest per-side commission rate, as a fraction
 * @param dualAgencyProbability chance a closing is a dual-agency deal, earning both sides
 * @param terminationProbability chance an agent leaves at some point in the window
 * @param eliteStatusProbability chance an agent holds Elite status, which reduces their post-cap transaction fee from
 *     $285 to $129
 * @param sponsorAttachmentAlpha preferential-attachment exponent; higher concentrates recruiting into a few hubs and
 *     deepens the tree
 */
public record SeedConfig(
        long randomSeed,
        int agentCount,
        int founderCount,
        LocalDate simulationStart,
        LocalDate simulationEnd,
        double medianAnnualClosings,
        double closingsLogSigma,
        double medianSalePrice,
        double salePriceLogSigma,
        double minCommissionRate,
        double maxCommissionRate,
        double dualAgencyProbability,
        double terminationProbability,
        double eliteStatusProbability,
        double sponsorAttachmentAlpha) {

    public SeedConfig {
        Objects.requireNonNull(simulationStart, "simulationStart must not be null");
        Objects.requireNonNull(simulationEnd, "simulationEnd must not be null");

        if (agentCount < 1) {
            throw new IllegalArgumentException("agentCount must be at least 1");
        }
        if (founderCount < 1 || founderCount > agentCount) {
            throw new IllegalArgumentException("founderCount must be between 1 and agentCount, was " + founderCount);
        }
        if (!simulationEnd.isAfter(simulationStart)) {
            throw new IllegalArgumentException("simulationEnd must be after simulationStart");
        }
        if (medianAnnualClosings <= 0 || medianSalePrice <= 0) {
            throw new IllegalArgumentException("median closings and sale price must be positive");
        }
        if (minCommissionRate <= 0 || maxCommissionRate < minCommissionRate) {
            throw new IllegalArgumentException("commission rate range is invalid");
        }
        if (outOfUnitRange(dualAgencyProbability)
                || outOfUnitRange(terminationProbability)
                || outOfUnitRange(eliteStatusProbability)) {
            throw new IllegalArgumentException("probabilities must be between 0 and 1");
        }
    }

    /**
     * A mid-sized brokerage over four years: enough agents for five-deep sponsorship chains, enough history for several
     * cap years per agent.
     */
    public static SeedConfig defaults() {
        return new SeedConfig(
                20260802L,
                500,
                8,
                LocalDate.of(2022, 1, 1),
                LocalDate.of(2026, 1, 1),
                // Tuned so that roughly 15% of agents reach the cap in any given year, which
                // is about what a real brokerage on this schedule reports. Raising it much
                // above 3.0 tips the majority of closings onto the post-cap flat fee and
                // leaves the 85/15 split barely exercised; dropping it below 2.0 leaves too
                // few capped agents to produce meaningful revenue share.
                2.5,
                1.0,
                425_000.0,
                0.55,
                0.023,
                0.030,
                0.06,
                0.08,
                0.15,
                1.15);
    }

    public SeedConfig withRandomSeed(long seed) {
        return new SeedConfig(
                seed,
                agentCount,
                founderCount,
                simulationStart,
                simulationEnd,
                medianAnnualClosings,
                closingsLogSigma,
                medianSalePrice,
                salePriceLogSigma,
                minCommissionRate,
                maxCommissionRate,
                dualAgencyProbability,
                terminationProbability,
                eliteStatusProbability,
                sponsorAttachmentAlpha);
    }

    public SeedConfig withAgentCount(int count) {
        return new SeedConfig(
                randomSeed,
                count,
                Math.min(founderCount, count),
                simulationStart,
                simulationEnd,
                medianAnnualClosings,
                closingsLogSigma,
                medianSalePrice,
                salePriceLogSigma,
                minCommissionRate,
                maxCommissionRate,
                dualAgencyProbability,
                terminationProbability,
                eliteStatusProbability,
                sponsorAttachmentAlpha);
    }

    public SeedConfig withWindow(LocalDate start, LocalDate end) {
        return new SeedConfig(
                randomSeed,
                agentCount,
                founderCount,
                start,
                end,
                medianAnnualClosings,
                closingsLogSigma,
                medianSalePrice,
                salePriceLogSigma,
                minCommissionRate,
                maxCommissionRate,
                dualAgencyProbability,
                terminationProbability,
                eliteStatusProbability,
                sponsorAttachmentAlpha);
    }

    private static boolean outOfUnitRange(double probability) {
        return probability < 0.0 || probability > 1.0;
    }
}
