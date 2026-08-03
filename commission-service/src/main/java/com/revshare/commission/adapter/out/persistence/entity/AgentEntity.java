package com.revshare.commission.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persistence mapping for an agent.
 *
 * <p>A separate type from {@code Agent}, deliberately. The domain aggregate carries behaviour and guarded transitions;
 * this carries columns. Annotating the aggregate itself would put JPA on the core's compile classpath — which the build
 * forbids — and would let Hibernate's requirements (a no-arg constructor, non-final fields, mutable collections)
 * dictate the shape of the domain model.
 *
 * <p>The cost is a mapper. The benefit is that {@code Agent} has no setters and cannot be put into an invalid state,
 * and that changing a column name is not a domain change.
 */
@Entity
@Table(name = "agent")
public class AgentEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "joined_on", nullable = false)
    private LocalDate joinedOn;

    /** Null for a founder. */
    @Column(name = "sponsor_id")
    private UUID sponsorId;

    /**
     * The frozen materialised path, nearest ancestor first, mapped onto a Postgres {@code uuid[]}.
     *
     * <p>Hibernate 6 maps a {@code List<UUID>} to a native SQL array directly, so this needs no converter and no join
     * table. Keeping it as a real array rather than a delimited string is what lets the GIN index answer "everyone
     * below agent X" as a containment query, with {@code array_position} yielding the tier.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "sponsorship_path", nullable = false)
    private List<UUID> sponsorshipPath;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "elite_status", nullable = false)
    private String eliteStatus;

    @Column(name = "terminated_on")
    private LocalDate terminatedOn;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected AgentEntity() {
        // Required by Hibernate. Not for application use.
    }

    public AgentEntity(
            UUID id,
            String firstName,
            String lastName,
            String email,
            LocalDate joinedOn,
            UUID sponsorId,
            List<UUID> sponsorshipPath,
            String status,
            String eliteStatus,
            LocalDate terminatedOn) {
        this.id = id;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.joinedOn = joinedOn;
        this.sponsorId = sponsorId;
        this.sponsorshipPath = sponsorshipPath;
        this.status = status;
        this.eliteStatus = eliteStatus;
        this.terminatedOn = terminatedOn;
    }

    public UUID getId() {
        return id;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getEmail() {
        return email;
    }

    public LocalDate getJoinedOn() {
        return joinedOn;
    }

    public UUID getSponsorId() {
        return sponsorId;
    }

    public List<UUID> getSponsorshipPath() {
        return sponsorshipPath;
    }

    public String getStatus() {
        return status;
    }

    public String getEliteStatus() {
        return eliteStatus;
    }

    public LocalDate getTerminatedOn() {
        return terminatedOn;
    }

    public long getVersion() {
        return version;
    }

    /**
     * Applies the mutable half of the aggregate onto an already-loaded row.
     *
     * <p>Only status, elite status and termination date are writable. Identity, join date and sponsorship path are
     * immutable in the domain and so have no setters here either — re-parenting an agent would silently break every
     * tier calculation beneath them.
     */
    public void applyMutableState(String status, String eliteStatus, LocalDate terminatedOn) {
        this.status = status;
        this.eliteStatus = eliteStatus;
        this.terminatedOn = terminatedOn;
    }
}
