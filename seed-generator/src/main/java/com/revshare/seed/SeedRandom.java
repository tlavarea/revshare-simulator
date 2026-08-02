package com.revshare.seed;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

/**
 * The single source of randomness, and the reason a seed reproduces a brokerage exactly.
 *
 * <p>Built on {@link Random} rather than the newer {@code RandomGenerator} implementations or {@code ThreadLocalRandom}
 * for one reason: {@code Random}'s linear congruential algorithm and its {@code nextGaussian} are specified exactly in
 * the javadoc and are guaranteed stable across JDK versions and platforms. A seeded run in CI on Linux therefore
 * produces byte-identical output to a seeded run on a developer's machine, which is what makes the generated data
 * usable as a test fixture rather than just a demo.
 *
 * <p>Everything drawn here goes through this one instance in a fixed order. Introducing a second generator, or drawing
 * values in a different order, changes the output for a given seed even if no parameter changed.
 */
final class SeedRandom {

    private final Random random;

    SeedRandom(long seed) {
        this.random = new Random(seed);
    }

    /** Uniform in {@code [0, 1)}. */
    double nextUnit() {
        return random.nextDouble();
    }

    /** Uniform in {@code [min, max)}. */
    double nextDouble(double min, double max) {
        return min + random.nextDouble() * (max - min);
    }

    /** Uniform in {@code [0, bound)}. */
    int nextInt(int bound) {
        return random.nextInt(bound);
    }

    boolean nextChance(double probability) {
        return random.nextDouble() < probability;
    }

    <T> T pick(List<T> items) {
        return items.get(random.nextInt(items.size()));
    }

    /**
     * Draws from a lognormal distribution with the given median.
     *
     * <p>Parameterised by median rather than by the underlying normal's mean because the median is the number anyone
     * actually has an intuition for: "the typical agent closes four deals a year" is a statement about the median, and
     * for a lognormal it is exactly {@code exp(mu)}. Sigma then controls only how heavy the upper tail is, so the two
     * knobs stay independent.
     */
    double nextLogNormal(double median, double sigma) {
        return median * Math.exp(sigma * random.nextGaussian());
    }

    /**
     * Picks an item with probability proportional to a weight.
     *
     * <p>Used for preferential attachment when choosing sponsors: weighting by an agent's existing recruit count
     * produces the hub-and-chain structure real referral networks have, rather than the shallow, uniformly bushy tree
     * that uniform selection gives.
     */
    <T> T pickWeighted(List<T> items, ToDoubleFunction<T> weight) {
        double total = 0.0;
        for (T item : items) {
            total += weight.applyAsDouble(item);
        }
        if (total <= 0.0) {
            return pick(items);
        }
        double target = random.nextDouble() * total;
        double cumulative = 0.0;
        for (T item : items) {
            cumulative += weight.applyAsDouble(item);
            if (cumulative >= target) {
                return item;
            }
        }
        return items.get(items.size() - 1);
    }

    /** A uniformly random date in {@code [from, toExclusive)}. */
    LocalDate nextDate(LocalDate from, LocalDate toExclusive) {
        long days = ChronoUnit.DAYS.between(from, toExclusive);
        if (days <= 0) {
            return from;
        }
        return from.plusDays(Math.floorMod(random.nextLong(), days));
    }

    /**
     * A well-formed version 4 UUID drawn from the seeded stream.
     *
     * <p>{@link UUID#randomUUID()} would break reproducibility, since it draws from a {@code SecureRandom} this class
     * cannot seed. The version and variant bits are set by hand so the results are indistinguishable from real v4 UUIDs
     * to anything that validates them.
     */
    UUID nextUuid() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x40);
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);

        long most = 0;
        long least = 0;
        for (int i = 0; i < 8; i++) {
            most = (most << 8) | (bytes[i] & 0xffL);
        }
        for (int i = 8; i < 16; i++) {
            least = (least << 8) | (bytes[i] & 0xffL);
        }
        return new UUID(most, least);
    }

    /** Rounds a drawn double to a money-shaped decimal. */
    static BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    /** Rounds a drawn sale price to the nearest hundred dollars, as listings tend to be. */
    static BigDecimal salePrice(double value) {
        return BigDecimal.valueOf(Math.round(value / 100.0) * 100L).setScale(2, RoundingMode.HALF_UP);
    }
}
