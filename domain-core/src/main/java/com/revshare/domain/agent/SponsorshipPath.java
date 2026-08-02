package com.revshare.domain.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The immutable chain of sponsors above an agent, nearest first.
 *
 * <p>This is the single most important invariant in the revenue share program, and it is why the chain is stored as a
 * materialized path rather than recomputed by walking parent pointers: <strong>the path is fixed at enrolment and never
 * rewritten.</strong>
 *
 * <p>Consider A sponsors B, B sponsors C. C's path is {@code [B, A]}: B is C's tier 1, A is C's tier 2. If B later
 * leaves the brokerage, a naive implementation that walks live parent links would either break (B is gone) or compress
 * the tree, promoting C to A's tier 1 and silently paying A the 5% rate instead of 4%. Neither is correct. The
 * hierarchy persists through departures: C remains two levels below A forever, and A keeps earning at tier 2 on C's
 * production. B simply stops collecting.
 *
 * <p>Storing the path also makes the read side cheap. Resolving "who earns from this transaction, and at what tier?" is
 * an array lookup on a value the writer already holds, not a recursive CTE per transaction.
 *
 * <p>Tiers are 1-based to match how the program is published: index 0 of the ancestor list is tier 1.
 */
public record SponsorshipPath(List<AgentId> ancestorsNearestFirst) {

    /**
     * How deep revenue share pays. Ancestors beyond this are still recorded, because an agent's true depth in the
     * organisation is a fact independent of the current payout schedule, and a future schedule may reach further.
     */
    public static final int REVENUE_SHARE_DEPTH = 5;

    private static final SponsorshipPath ROOT = new SponsorshipPath(List.of());

    public SponsorshipPath {
        Objects.requireNonNull(ancestorsNearestFirst, "ancestors must not be null");
        ancestorsNearestFirst = List.copyOf(ancestorsNearestFirst);
        if (ancestorsNearestFirst.stream().distinct().count() != ancestorsNearestFirst.size()) {
            throw new IllegalArgumentException("sponsorship path contains a cycle: " + ancestorsNearestFirst);
        }
    }

    /** An agent with no sponsor, at the top of a tree. */
    public static SponsorshipPath root() {
        return ROOT;
    }

    /**
     * Builds the path of an agent sponsored by {@code sponsor}, given the sponsor's own path. Called exactly once, when
     * the agent is enrolled.
     */
    public static SponsorshipPath sponsoredBy(AgentId sponsor, SponsorshipPath sponsorPath) {
        Objects.requireNonNull(sponsor, "sponsor must not be null");
        Objects.requireNonNull(sponsorPath, "sponsor path must not be null");
        if (sponsorPath.ancestorsNearestFirst.contains(sponsor)) {
            throw new IllegalArgumentException("sponsor " + sponsor + " already appears in its own upline");
        }
        List<AgentId> extended = new ArrayList<>(sponsorPath.ancestorsNearestFirst.size() + 1);
        extended.add(sponsor);
        extended.addAll(sponsorPath.ancestorsNearestFirst);
        return new SponsorshipPath(extended);
    }

    /** The immediate sponsor, if any. Empty for a root agent. */
    public Optional<AgentId> sponsor() {
        return ancestorsNearestFirst.isEmpty() ? Optional.empty() : Optional.of(ancestorsNearestFirst.get(0));
    }

    /** How many levels of upline exist above this agent. */
    public int depth() {
        return ancestorsNearestFirst.size();
    }

    public boolean isRoot() {
        return ancestorsNearestFirst.isEmpty();
    }

    /**
     * The 1-based tier at which {@code ancestor} sits above this agent, or empty if the ancestor is not in this agent's
     * upline at all.
     */
    public OptionalInt tierOf(AgentId ancestor) {
        int index = ancestorsNearestFirst.indexOf(ancestor);
        return index < 0 ? OptionalInt.empty() : OptionalInt.of(index + 1);
    }

    /**
     * The ancestors eligible to earn revenue share from this agent, nearest first, capped at
     * {@link #REVENUE_SHARE_DEPTH}.
     *
     * <p>Eligibility here is purely structural. Whether a given ancestor actually collects also depends on the tier
     * being unlocked and on the producing-agent policy, both of which are evaluated at distribution time.
     */
    public List<AgentId> revenueShareUpline() {
        return ancestorsNearestFirst.subList(0, Math.min(REVENUE_SHARE_DEPTH, ancestorsNearestFirst.size()));
    }

    @Override
    public String toString() {
        return isRoot()
                ? "root"
                : String.join(
                        " <- ",
                        ancestorsNearestFirst.stream().map(AgentId::toString).toList());
    }

    /** Defensive: the canonical list is already immutable, this documents the intent. */
    @Override
    public List<AgentId> ancestorsNearestFirst() {
        return Collections.unmodifiableList(ancestorsNearestFirst);
    }
}
