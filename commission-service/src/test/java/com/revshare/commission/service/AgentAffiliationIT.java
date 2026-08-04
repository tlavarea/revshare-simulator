package com.revshare.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.revshare.commission.AbstractPostgresIT;
import com.revshare.commission.adapter.out.persistence.jpa.OutboxJpaRepository;
import com.revshare.domain.agent.Agent;
import com.revshare.domain.agent.AgentId;
import com.revshare.domain.agent.AgentStatus;
import com.revshare.domain.port.in.AgentAffiliation;
import com.revshare.domain.port.out.AgentRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Enrolment and departure against a real Postgres. */
class AgentAffiliationIT extends AbstractPostgresIT {

    private static final LocalDate JOINED = LocalDate.of(2024, 1, 15);

    @Autowired
    private AgentAffiliation affiliation;

    @Autowired
    private AgentRepository agents;

    @Autowired
    private OutboxJpaRepository outbox;

    private static int sequence;

    private AgentAffiliation.Enrollment enrollment(AgentId sponsorId) {
        int n = ++sequence;
        return new AgentAffiliation.Enrollment(
                AgentId.newId(), "Agent", "Number" + n, "affiliation" + n + "@example.test", JOINED, sponsorId);
    }

    private Agent enrolledUnder(AgentId sponsorId) {
        return affiliation.enroll(enrollment(sponsorId));
    }

    @Nested
    @DisplayName("enrolment")
    class Enrolment {

        @Test
        @DisplayName("derives the sponsorship path from the sponsor rather than taking one")
        void derivesThePath() {
            Agent top = enrolledUnder(null);
            Agent middle = enrolledUnder(top.id());
            Agent bottom = enrolledUnder(middle.id());

            assertThat(top.sponsorshipPath().isRoot()).isTrue();
            assertThat(middle.sponsorshipPath().ancestorsNearestFirst()).containsExactly(top.id());

            // Nearest first: the sponsor, then everyone above them. This is the value every
            // tier and every revenue share rate downstream is computed from, which is why it
            // is derived here and never accepted from a caller.
            assertThat(bottom.sponsorshipPath().ancestorsNearestFirst()).containsExactly(middle.id(), top.id());
        }

        @Test
        @DisplayName("persists the agent so the closing path can find them")
        void theAgentIsReadable() {
            Agent enrolled = enrolledUnder(null);

            Agent stored = agents.findById(enrolled.id()).orElseThrow();
            assertThat(stored.joinedOn()).isEqualTo(JOINED);
            assertThat(stored.status()).isEqualTo(AgentStatus.ACTIVE);
        }

        @Test
        @DisplayName("rejects a second enrolment of the same id")
        void duplicatesAreRejected() {
            AgentAffiliation.Enrollment first = enrollment(null);
            affiliation.enroll(first);

            // Not an idempotent replay: the request carries a name and an email that may differ
            // from the stored ones, so accepting it would either overwrite the record from what
            // looks like a retry or discard the new values while reporting success.
            assertThatThrownBy(() -> affiliation.enroll(first))
                    .isInstanceOf(AgentAffiliation.AgentAlreadyEnrolledException.class);
        }

        @Test
        @DisplayName("rejects an enrolment beneath a sponsor that does not exist")
        void unknownSponsorIsRejected() {
            assertThatThrownBy(() -> enrolledUnder(AgentId.newId()))
                    .isInstanceOf(AgentAffiliation.UnknownSponsorException.class);
        }

        @Test
        @DisplayName("still enrols beneath a sponsor who has left")
        void aTerminatedSponsorStillSponsors() {
            Agent sponsor = enrolledUnder(null);
            affiliation.terminate(sponsor.id(), JOINED.plusMonths(2));

            Agent recruit = enrolledUnder(sponsor.id());

            // Termination stops the sponsor collecting; it does not remove them from the tree.
            // Refusing the enrolment here would compress the hierarchy by the back door, and
            // the recruit's true depth is a fact independent of who is currently earning.
            assertThat(recruit.sponsorshipPath().ancestorsNearestFirst()).containsExactly(sponsor.id());
        }

        @Test
        @DisplayName("announces the enrolment on the agent topic, keyed by the new agent")
        void writesAnEnrolmentEvent() {
            Agent enrolled = enrolledUnder(null);

            var events = outbox.findAll().stream()
                    .filter(e -> "AgentEnrolled".equals(e.getEventType()))
                    .filter(e -> enrolled.id().toString().equals(e.getPartitionKey()))
                    .toList();

            assertThat(events).hasSize(1);
            assertThat(events.get(0).getAggregateType())
                    .as("its own aggregate type, so a roster rebuild does not replay every closing in the brokerage")
                    .isEqualTo("agent");
        }
    }

    @Nested
    @DisplayName("termination")
    class Termination {

        @Test
        @DisplayName("ends the affiliation and records the date")
        void marksTheAgentTerminated() {
            Agent enrolled = enrolledUnder(null);
            LocalDate left = JOINED.plusMonths(6);

            Agent terminated = affiliation.terminate(enrolled.id(), left);

            assertThat(terminated.status()).isEqualTo(AgentStatus.TERMINATED);
            assertThat(agents.findById(enrolled.id()).orElseThrow().terminatedOn())
                    .contains(left);
        }

        @Test
        @DisplayName("leaves the sponsorship path untouched")
        void theTreeDoesNotChange() {
            Agent sponsor = enrolledUnder(null);
            Agent recruit = enrolledUnder(sponsor.id());

            affiliation.terminate(sponsor.id(), JOINED.plusMonths(6));

            // The recruit stays exactly where they were. Their upline keeps every tier it had,
            // and nobody is promoted — the single most important invariant in the programme.
            assertThat(agents.findById(recruit.id())
                            .orElseThrow()
                            .sponsorshipPath()
                            .ancestorsNearestFirst())
                    .containsExactly(sponsor.id());
        }

        @Test
        @DisplayName("refuses to terminate an agent twice")
        void terminatingTwiceIsRejected() {
            Agent enrolled = enrolledUnder(null);
            affiliation.terminate(enrolled.id(), JOINED.plusMonths(6));

            // Silently accepting would let a later date overwrite the real departure, which
            // revenue share eligibility is evaluated against.
            assertThatThrownBy(() -> affiliation.terminate(enrolled.id(), JOINED.plusMonths(9)))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("rejects an agent that was never enrolled")
        void unknownAgentIsRejected() {
            assertThatThrownBy(() -> affiliation.terminate(AgentId.newId(), JOINED))
                    .isInstanceOf(AgentAffiliation.UnknownAgentException.class);
        }

        @Test
        @DisplayName("announces the departure")
        void writesATerminationEvent() {
            Agent enrolled = enrolledUnder(null);
            affiliation.terminate(enrolled.id(), JOINED.plusMonths(6));

            assertThat(outbox.findAll())
                    .filteredOn(e -> "AgentTerminated".equals(e.getEventType()))
                    .extracting(e -> e.getPartitionKey())
                    .contains(enrolled.id().toString());
        }

        @Test
        @DisplayName("writes nothing when the termination is refused")
        void aRefusedTerminationAnnouncesNothing() {
            Agent enrolled = enrolledUnder(null);
            affiliation.terminate(enrolled.id(), JOINED.plusMonths(6));
            long afterFirst = outbox.count();

            assertThatThrownBy(() -> affiliation.terminate(enrolled.id(), JOINED.plusMonths(9)))
                    .isInstanceOf(IllegalStateException.class);

            // The event and the state change share a transaction, so a refused change cannot
            // leave an announcement behind.
            assertThat(outbox.count()).isEqualTo(afterFirst);
        }
    }
}
