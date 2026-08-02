package com.revshare.domain.agent;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * The twelve-month window over which one agent's commission cap accrues.
 *
 * <p>The window is anchored to the agent's own anniversary, not the calendar year. An agent who joined on 14 March caps
 * against the period 14 March to 13 March, and the balance resets on their anniversary rather than on 1 January.
 * Modeling this as a calendar year would be the single easiest way to get every downstream number wrong, since it
 * decides which transactions contribute to the cap and therefore which ones generate revenue share.
 *
 * <p>{@code start} is inclusive, {@code endExclusive} is not.
 */
public record CapYear(LocalDate start, LocalDate endExclusive, int ordinal) {

    public CapYear {
        Objects.requireNonNull(start, "start must not be null");
        Objects.requireNonNull(endExclusive, "endExclusive must not be null");
        if (!endExclusive.isAfter(start)) {
            throw new IllegalArgumentException("cap year must be a non-empty range");
        }
        if (ordinal < 0) {
            throw new IllegalArgumentException("cap year ordinal must not be negative");
        }
    }

    /**
     * Resolves the cap year of {@code anniversaryOrigin} that contains {@code on}.
     *
     * <p>The apparently redundant adjustment loops exist for February 29. {@code plusYears} clamps 29 February to 28
     * February in non-leap years, which makes the naive {@code origin.plusYears(yearsBetween)} window occasionally fail
     * to contain the very date it was derived from. The loops normalize that away, and run zero times for every other
     * origin date.
     *
     * @param anniversaryOrigin the agent's join date, which fixes the anniversary
     * @param on a date on or after the join date
     */
    public static CapYear containing(LocalDate anniversaryOrigin, LocalDate on) {
        Objects.requireNonNull(anniversaryOrigin, "anniversaryOrigin must not be null");
        Objects.requireNonNull(on, "on must not be null");
        if (on.isBefore(anniversaryOrigin)) {
            throw new IllegalArgumentException("date " + on + " precedes the agent's join date " + anniversaryOrigin);
        }

        int n = (int) ChronoUnit.YEARS.between(anniversaryOrigin, on);
        LocalDate start = anniversaryOrigin.plusYears(n);
        LocalDate end = anniversaryOrigin.plusYears(n + 1L);

        while (!on.isBefore(end)) {
            n++;
            start = anniversaryOrigin.plusYears(n);
            end = anniversaryOrigin.plusYears(n + 1L);
        }
        while (on.isBefore(start)) {
            n--;
            start = anniversaryOrigin.plusYears(n);
            end = anniversaryOrigin.plusYears(n + 1L);
        }

        return new CapYear(start, end, n);
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(start) && date.isBefore(endExclusive);
    }

    /** The window immediately following this one, where the cap balance resets to zero. */
    public CapYear next(LocalDate anniversaryOrigin) {
        return containing(anniversaryOrigin, endExclusive);
    }

    @Override
    public String toString() {
        return start + " to " + endExclusive.minusDays(1) + " (year " + (ordinal + 1) + ")";
    }
}
