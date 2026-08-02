package com.revshare.domain.revshare;

import com.revshare.domain.agent.AgentId;
import com.revshare.domain.agent.SponsorshipPath;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * One agent's downline, flattened into the five revenue share tiers.
 *
 * <p>Built by reading other agents' {@link SponsorshipPath}s rather than by walking parent pointers, which is what
 * makes it correct in the presence of departures: an agent whose sponsor has left still carries that sponsor in their
 * path, and so still sits at the same depth beneath everyone above them.
 *
 * <p>This is the shape the read side serves. It is expensive to assemble from the write model (every descendant of an
 * agent, grouped by depth) and cheap to serve once assembled, it is read far more often than it changes, and it is
 * naturally a nested document rather than a set of rows. That combination is the argument for projecting it into a
 * document store on the query side instead of running a recursive CTE per dashboard load.
 *
 * <p>Membership is structural only. Being in someone's tier 2 says nothing about whether either party is currently
 * active, producing, or unlocked; those are evaluated per transaction at distribution time.
 */
public final class RevenueShareDownline {

    private final AgentId beneficiary;
    private final Map<RevenueShareTier, List<AgentId>> membersByTier;

    private RevenueShareDownline(AgentId beneficiary, Map<RevenueShareTier, List<AgentId>> membersByTier) {
        this.beneficiary = beneficiary;
        this.membersByTier = membersByTier;
    }

    /**
     * Assembles the downline of {@code beneficiary} from the sponsorship paths of an organization.
     *
     * <p>Linear in the size of the organization, which is fine for a domain-level projection built once per rebuild. A
     * persistence adapter serving this incrementally would index by ancestor instead.
     *
     * @param organization every agent's frozen path, keyed by agent
     */
    public static RevenueShareDownline of(AgentId beneficiary, Map<AgentId, SponsorshipPath> organization) {

        Objects.requireNonNull(beneficiary, "beneficiary must not be null");
        Objects.requireNonNull(organization, "organization must not be null");

        Map<RevenueShareTier, List<AgentId>> byTier = new EnumMap<>(RevenueShareTier.class);

        organization.forEach((agentId, path) -> {
            if (agentId.equals(beneficiary)) {
                return;
            }
            OptionalInt depth = path.tierOf(beneficiary);
            if (depth.isEmpty()) {
                return;
            }
            RevenueShareTier.atDepth(depth.getAsInt())
                    .ifPresent(tier ->
                            byTier.computeIfAbsent(tier, t -> new ArrayList<>()).add(agentId));
        });

        // Sorted so that a rebuild of the same organization produces a byte-identical
        // projection, which makes the read model diffable and its tests stable.
        byTier.values().forEach(Collections::sort);

        return new RevenueShareDownline(beneficiary, byTier);
    }

    /** An agent with nobody beneath them. */
    public static RevenueShareDownline empty(AgentId beneficiary) {
        return new RevenueShareDownline(Objects.requireNonNull(beneficiary), new EnumMap<>(RevenueShareTier.class));
    }

    public AgentId beneficiary() {
        return beneficiary;
    }

    /** The agents this beneficiary personally sponsored. */
    public List<AgentId> frontline() {
        return membersAt(RevenueShareTier.TIER_1);
    }

    public List<AgentId> membersAt(RevenueShareTier tier) {
        Objects.requireNonNull(tier, "tier must not be null");
        return Collections.unmodifiableList(membersByTier.getOrDefault(tier, List.of()));
    }

    /** Which tier a given descendant sits at, or empty if they are not in this downline. */
    public Optional<RevenueShareTier> tierOf(AgentId member) {
        return membersByTier.entrySet().stream()
                .filter(entry -> entry.getValue().contains(member))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /** Everyone within revenue share reach, tier 1 first. */
    public Map<RevenueShareTier, List<AgentId>> membersByTier() {
        Map<RevenueShareTier, List<AgentId>> copy = new LinkedHashMap<>();
        for (RevenueShareTier tier : RevenueShareTier.values()) {
            List<AgentId> members = membersAt(tier);
            if (!members.isEmpty()) {
                copy.put(tier, members);
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    public int size() {
        return membersByTier.values().stream().mapToInt(List::size).sum();
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * The tiers this beneficiary can currently earn from.
     *
     * <p>Takes the producing frontline count as an argument rather than computing it, because "producing" is a
     * time-dependent question about transaction history that this structural projection has no business answering. See
     * {@link ProducingAgentPolicy}.
     */
    public Set<RevenueShareTier> unlockedTiers(int producingFrontlineCount) {
        return RevenueShareTier.unlockedFor(producingFrontlineCount);
    }

    @Override
    public String toString() {
        return "RevenueShareDownline[" + beneficiary + ": " + size() + " across " + membersByTier.size() + " tiers]";
    }
}
