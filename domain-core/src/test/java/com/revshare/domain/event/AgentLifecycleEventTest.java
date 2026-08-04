package com.revshare.domain.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.revshare.domain.agent.AgentId;
import com.revshare.domain.agent.SponsorshipPath;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The events that make an agent visible before they have earned anything.
 *
 * <p>What is worth asserting here is not that the records hold their fields. It is that they carry the sponsorship path
 * <em>in order</em> and refuse a path that contains the agent themselves — the two properties a consumer places
 * ancestors at the right tier by, and the two that no consumer could detect were wrong.
 */
@DisplayName("agent lifecycle events")
class AgentLifecycleEventTest {

    private static final Instant AT = Instant.parse("2025-01-01T00:00:00Z");
    private static final LocalDate JOINED = LocalDate.of(2025, 3, 14);

    private static final AgentId SPONSOR = AgentId.newId();
    private static final AgentId GRANDSPONSOR = AgentId.newId();

    private static SponsorshipPath twoDeep() {
        return new SponsorshipPath(List.of(SPONSOR, GRANDSPONSOR));
    }

    @Nested
    @DisplayName("enrolment")
    class Enrolment {

        @Test
        @DisplayName("partitions on the enrolling agent, so their own lifecycle stays ordered")
        void partitionsOnTheAgent() {
            AgentId agent = AgentId.newId();

            AgentEnrolled event = new AgentEnrolled(UUID.randomUUID(), AT, agent, twoDeep(), JOINED);

            // Not on the sponsor: one enrolment concerns up to five ancestors, so no single
            // ancestor can own its ordering. Keyed on the agent, this event stays ordered
            // against the termination that may follow it, which is the pair that matters.
            assertThat(event.partitionKey()).isEqualTo(agent.toString());
        }

        @Test
        @DisplayName("keeps the upline nearest first, because index 0 is tier 1")
        void theOrderIsTheContract() {
            AgentEnrolled event = new AgentEnrolled(UUID.randomUUID(), AT, AgentId.newId(), twoDeep(), JOINED);

            assertThat(event.sponsorshipPath().ancestorsNearestFirst()).containsExactly(SPONSOR, GRANDSPONSOR);
            assertThat(event.sponsorId()).contains(SPONSOR);
        }

        @Test
        @DisplayName("reports no sponsor for an agent at the top of a tree")
        void anUnsponsoredAgentHasNoSponsor() {
            AgentEnrolled event =
                    new AgentEnrolled(UUID.randomUUID(), AT, AgentId.newId(), SponsorshipPath.root(), JOINED);

            assertThat(event.sponsorId()).isEmpty();
        }

        @Test
        @DisplayName("refuses a path that contains the agent themselves")
        void rejectsAnAgentInTheirOwnUpline() {
            AgentId agent = AgentId.newId();
            SponsorshipPath impossible = new SponsorshipPath(List.of(SPONSOR, agent));

            // A cycle that reached a consumer would place the agent in their own downline and
            // pay them revenue share on their own production. Caught at construction, so it
            // cannot be written into an event at all.
            assertThatThrownBy(() -> new AgentEnrolled(UUID.randomUUID(), AT, agent, impossible, JOINED))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("own sponsorship path");
        }
    }

    @Nested
    @DisplayName("termination")
    class Termination {

        @Test
        @DisplayName("carries the path so every ancestor's roster can be found without a lookup")
        void theTerminationCarriesThePathToo() {
            AgentTerminated event =
                    new AgentTerminated(UUID.randomUUID(), AT, AgentId.newId(), twoDeep(), LocalDate.of(2025, 9, 30));

            assertThat(event.sponsorshipPath().ancestorsNearestFirst()).containsExactly(SPONSOR, GRANDSPONSOR);
        }

        @Test
        @DisplayName("partitions on the departing agent, matching their enrolment")
        void partitionsOnTheAgent() {
            AgentId agent = AgentId.newId();

            AgentTerminated event =
                    new AgentTerminated(UUID.randomUUID(), AT, agent, twoDeep(), LocalDate.of(2025, 9, 30));

            // The same key as AgentEnrolled, which is what guarantees a consumer never sees an
            // agent depart before it saw them join.
            assertThat(event.partitionKey()).isEqualTo(agent.toString());
        }

        @Test
        @DisplayName("refuses a path that contains the agent themselves")
        void rejectsAnAgentInTheirOwnUpline() {
            AgentId agent = AgentId.newId();
            SponsorshipPath impossible = new SponsorshipPath(List.of(agent));

            assertThatThrownBy(() -> new AgentTerminated(UUID.randomUUID(), AT, agent, impossible, JOINED))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
