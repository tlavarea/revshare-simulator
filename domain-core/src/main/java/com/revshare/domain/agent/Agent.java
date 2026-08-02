package com.revshare.domain.agent;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * An agent affiliated with the brokerage. Aggregate root of the agent context.
 *
 * <p>Mutable, unlike the value objects it is built from. State transitions ({@link #terminate},
 * {@link #grantEliteStatus}) are expressed as guarded methods rather than setters, so an illegal transition is
 * impossible to express rather than merely discouraged. That distinction, immutable values inside a mutable aggregate
 * with behavior, is the reason this type is a class and {@link SponsorshipPath} is a record.
 *
 * <p>The aggregate boundary stops at the agent. It deliberately holds neither its downline nor its transactions: both
 * are unbounded, and loading an agent should not drag an entire subtree into memory. Downline structure is derived from
 * the sponsorship paths of other agents, and cap progress is its own aggregate keyed by agent and cap year.
 */
public final class Agent {

    private final AgentId id;
    private final String firstName;
    private final String lastName;
    private final String email;

    /**
     * Fixes both the cap-year anniversary and the agent's place in the tree. Never changes, including across
     * termination and rehire, which is what makes cap windows and sponsorship tiers stable facts rather than
     * derived-on-read guesses.
     */
    private final LocalDate joinedOn;

    private final SponsorshipPath sponsorshipPath;

    private AgentStatus status;
    private EliteStatus eliteStatus;
    private LocalDate terminatedOn;

    private Agent(
            AgentId id,
            String firstName,
            String lastName,
            String email,
            LocalDate joinedOn,
            SponsorshipPath sponsorshipPath,
            AgentStatus status,
            EliteStatus eliteStatus,
            LocalDate terminatedOn) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.firstName = requireText(firstName, "firstName");
        this.lastName = requireText(lastName, "lastName");
        this.email = requireText(email, "email");
        this.joinedOn = Objects.requireNonNull(joinedOn, "joinedOn must not be null");
        this.sponsorshipPath = Objects.requireNonNull(sponsorshipPath, "sponsorshipPath must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.eliteStatus = Objects.requireNonNull(eliteStatus, "eliteStatus must not be null");
        this.terminatedOn = terminatedOn;
    }

    /** Enrolls an agent at the top of a tree, with no sponsor. */
    public static Agent enroll(AgentId id, String firstName, String lastName, String email, LocalDate joinedOn) {
        return new Agent(
                id,
                firstName,
                lastName,
                email,
                joinedOn,
                SponsorshipPath.root(),
                AgentStatus.ACTIVE,
                EliteStatus.STANDARD,
                null);
    }

    /**
     * Enrolls an agent beneath a sponsor.
     *
     * <p>Takes the sponsor's whole {@link SponsorshipPath} rather than just its id, because the new agent's path is
     * computed here, once, and then frozen. There is deliberately no way to re-parent an agent afterwards.
     */
    public static Agent enrollSponsoredBy(
            AgentId id,
            String firstName,
            String lastName,
            String email,
            LocalDate joinedOn,
            AgentId sponsorId,
            SponsorshipPath sponsorPath) {
        if (id.equals(sponsorId)) {
            throw new IllegalArgumentException("an agent cannot sponsor themselves: " + id);
        }
        SponsorshipPath path = SponsorshipPath.sponsoredBy(sponsorId, sponsorPath);
        if (path.ancestorsNearestFirst().contains(id)) {
            throw new IllegalArgumentException("enrolling " + id + " under " + sponsorId + " would create a cycle");
        }
        return new Agent(
                id, firstName, lastName, email, joinedOn, path, AgentStatus.ACTIVE, EliteStatus.STANDARD, null);
    }

    /** Rehydrates a persisted agent. For use by outbound adapters only. */
    public static Agent rehydrate(
            AgentId id,
            String firstName,
            String lastName,
            String email,
            LocalDate joinedOn,
            SponsorshipPath sponsorshipPath,
            AgentStatus status,
            EliteStatus eliteStatus,
            LocalDate terminatedOn) {
        return new Agent(id, firstName, lastName, email, joinedOn, sponsorshipPath, status, eliteStatus, terminatedOn);
    }

    /**
     * Ends the agent's affiliation.
     *
     * <p>Note what is absent: any mutation of the sponsorship tree. Terminating an agent stops them collecting, and
     * does nothing else. Their downline does not collapse upward and their upline does not lose a tier.
     */
    public void terminate(LocalDate on) {
        Objects.requireNonNull(on, "termination date must not be null");
        if (status == AgentStatus.TERMINATED) {
            throw new IllegalStateException("agent " + id + " is already terminated");
        }
        if (on.isBefore(joinedOn)) {
            throw new IllegalArgumentException("termination date " + on + " precedes join date " + joinedOn);
        }
        this.status = AgentStatus.TERMINATED;
        this.terminatedOn = on;
    }

    public void grantEliteStatus() {
        this.eliteStatus = EliteStatus.ELITE;
    }

    public void revokeEliteStatus() {
        this.eliteStatus = EliteStatus.STANDARD;
    }

    /** The cap window this date falls into, anchored on the agent's own anniversary. */
    public CapYear capYearOn(LocalDate date) {
        return CapYear.containing(joinedOn, date);
    }

    /** Whether the agent was affiliated on a given date. */
    public boolean wasAffiliatedOn(LocalDate date) {
        if (date.isBefore(joinedOn)) {
            return false;
        }
        return terminatedOn == null || !date.isAfter(terminatedOn);
    }

    public AgentId id() {
        return id;
    }

    public String firstName() {
        return firstName;
    }

    public String lastName() {
        return lastName;
    }

    public String fullName() {
        return firstName + " " + lastName;
    }

    public String email() {
        return email;
    }

    public LocalDate joinedOn() {
        return joinedOn;
    }

    public SponsorshipPath sponsorshipPath() {
        return sponsorshipPath;
    }

    public Optional<AgentId> sponsorId() {
        return sponsorshipPath.sponsor();
    }

    public AgentStatus status() {
        return status;
    }

    public EliteStatus eliteStatus() {
        return eliteStatus;
    }

    public Optional<LocalDate> terminatedOn() {
        return Optional.ofNullable(terminatedOn);
    }

    /** Aggregate identity: two agents are the same agent if their ids match. */
    @Override
    public boolean equals(Object o) {
        return o instanceof Agent other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Agent[" + id + " " + fullName() + " " + status + "]";
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
