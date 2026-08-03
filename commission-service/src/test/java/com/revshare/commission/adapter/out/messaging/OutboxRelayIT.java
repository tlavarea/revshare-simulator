package com.revshare.commission.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.revshare.commission.AbstractKafkaIT;
import com.revshare.commission.TestBrokerage;
import com.revshare.commission.adapter.out.persistence.jpa.OutboxJpaRepository;
import com.revshare.domain.agent.Agent;
import com.revshare.domain.port.in.RecordClosedTransaction;
import com.revshare.domain.port.out.AgentRepository;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * End-to-end tests for the outbox relay, against a real Postgres and a real broker.
 *
 * <p>Drives {@code relayBatch()} directly rather than waiting for the scheduler, which the test profile has effectively
 * disabled. A background poll firing mid-test would publish rows the test had not finished arranging, and the resulting
 * failures would be intermittent and blamed on Kafka.
 */
class OutboxRelayIT extends AbstractKafkaIT {

    private static final String COMMISSION_TOPIC = "revshare.commission.events";
    private static final String REVENUE_SHARE_TOPIC = "revshare.revenue-share.events";

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxRelayScheduler scheduler;

    @Autowired
    private OutboxJpaRepository outbox;

    @Autowired
    private RecordClosedTransaction recordClosedTransaction;

    @Autowired
    private AgentRepository agents;

    private TestBrokerage brokerage;

    @BeforeEach
    void setUp() {
        brokerage = new TestBrokerage(agents);
        // Clear any backlog left by a previous test so counts are unambiguous.
        relay.relayBatch();
    }

    @Nested
    @DisplayName("publishing")
    class Publishing {

        @Test
        @DisplayName("sends a commission event to the commission topic, keyed by agent")
        void publishesCommissionEvent() {
            Agent agent = brokerage.founder();
            recordClosedTransaction.record(TestBrokerage.closing(agent, "10000.00"));

            int published = relay.relayBatch();

            assertThat(published).isPositive();
            List<ConsumerRecord<String, String>> records = drain(COMMISSION_TOPIC, 1);
            assertThat(records).isNotEmpty();
            assertThat(records)
                    .as("commission events must be keyed by agent so one agent's closings stay ordered")
                    .anySatisfy(record -> assertThat(record.key())
                            .isEqualTo(agent.id().value().toString()));
        }

        @Test
        @DisplayName("sends revenue share events to their own topic, keyed by the contributor")
        void publishesRevenueShareEventToItsOwnTopic() {
            List<Agent> chain = brokerage.chain(2);
            Agent sponsor = chain.get(0);
            Agent contributor = chain.get(1);

            recordClosedTransaction.record(
                    TestBrokerage.closing(sponsor, "5000.00", TestBrokerage.JOINED.plusMonths(1)));
            recordClosedTransaction.record(
                    TestBrokerage.closing(contributor, "10000.00", TestBrokerage.JOINED.plusMonths(2)));

            relay.relayBatch();

            List<ConsumerRecord<String, String>> records = drain(REVENUE_SHARE_TOPIC, 1);
            assertThat(records).isNotEmpty();
            // Keyed by the contributor, not a beneficiary: one event concerns up to five
            // beneficiaries, so none of them can own the ordering.
            assertThat(records)
                    .anySatisfy(record -> assertThat(record.key())
                            .isEqualTo(contributor.id().value().toString()));
        }

        @Test
        @DisplayName("carries the stored JSON payload through unchanged")
        void payloadRoundTrips() {
            Agent agent = brokerage.founder();
            recordClosedTransaction.record(TestBrokerage.closing(agent, "10000.00"));

            relay.relayBatch();

            List<ConsumerRecord<String, String>> records = drain(COMMISSION_TOPIC, 1);
            assertThat(records).isNotEmpty();
            assertThat(records).anySatisfy(record -> {
                assertThat(record.value()).contains(agent.id().value().toString());
                // Value objects are flattened by the dedicated event mapper: an agent id is a
                // bare string and money is a number, not a nested object.
                assertThat(record.value()).doesNotContain("\"value\":{");
            });
        }

        @Test
        @DisplayName("attaches routing headers so a consumer need not parse the body")
        void attachesHeaders() {
            Agent agent = brokerage.founder();
            recordClosedTransaction.record(TestBrokerage.closing(agent, "10000.00"));

            relay.relayBatch();

            List<ConsumerRecord<String, String>> records = drain(COMMISSION_TOPIC, 1);
            assertThat(records).isNotEmpty();
            assertThat(records)
                    .anySatisfy(record ->
                            assertThat(headers(record)).containsKeys("event-id", "event-type", "occurred-at"));
            assertThat(records)
                    .anySatisfy(
                            record -> assertThat(header(record, "event-type")).isEqualTo("CommissionCalculated"));
        }
    }

    @Nested
    @DisplayName("bookkeeping")
    class Bookkeeping {

        @Test
        @DisplayName("marks relayed rows published so they are not sent twice")
        void marksRowsPublished() {
            Agent agent = brokerage.founder();
            recordClosedTransaction.record(TestBrokerage.closing(agent, "10000.00"));
            assertThat(outbox.countByPublishedAtIsNull()).isPositive();

            relay.relayBatch();

            assertThat(outbox.countByPublishedAtIsNull())
                    .as("every claimed row should carry a published_at stamp")
                    .isZero();
        }

        @Test
        @DisplayName("marks rows published when driven through the scheduled entry point")
        void marksRowsPublishedViaTheScheduledEntryPoint() {
            Agent agent = brokerage.founder();
            recordClosedTransaction.record(TestBrokerage.closing(agent, "10000.00"));
            assertThat(outbox.countByPublishedAtIsNull()).isPositive();

            // Deliberately the scheduled entry point rather than relayBatch(). Every other test
            // calls relayBatch() from outside the bean, which enters through the proxy and so gets
            // a transaction whether or not the production path would — the precise blind spot that
            // let a self-invocation bug pass this entire suite. Without a transaction the claimed
            // rows come back detached, markPublished() never flushes, and the relay re-sends the
            // same events on every poll forever.
            scheduler.relayPendingEvents();

            assertThat(outbox.countByPublishedAtIsNull())
                    .as("the scheduled path must commit published_at, or every poll re-sends the same events")
                    .isZero();
        }

        @Test
        @DisplayName("a second pass over an empty backlog publishes nothing")
        void secondPassIsANoOp() {
            Agent agent = brokerage.founder();
            recordClosedTransaction.record(TestBrokerage.closing(agent, "10000.00"));

            int first = relay.relayBatch();
            int second = relay.relayBatch();

            assertThat(first).isPositive();
            assertThat(second)
                    .as("republishing already-relayed events would double-deliver to every consumer")
                    .isZero();
        }

        @Test
        @DisplayName("leaves published rows in place rather than deleting them")
        void keepsHistory() {
            Agent agent = brokerage.founder();
            recordClosedTransaction.record(TestBrokerage.closing(agent, "10000.00"));
            long before = outbox.count();

            relay.relayBatch();

            // Rows are marked, not removed, so a published event stays auditable and the
            // relay's progress remains inspectable.
            assertThat(outbox.count()).isEqualTo(before);
            assertThat(outbox.findAll())
                    .allSatisfy(row -> assertThat(row.getPublishedAt()).isNotNull());
        }
    }

    @Test
    @DisplayName("keeps one agent's events in the order they occurred")
    void preservesPerAgentOrdering() {
        // The closing that caps an agent emits CommissionCalculated and CapThresholdReached
        // together. They share a partition key, so they must arrive in that order — a consumer
        // seeing the cap first would project an agent as capped with no transaction behind it.
        Agent agent = brokerage.founder();
        recordClosedTransaction.record(TestBrokerage.closing(agent, "80000.00"));

        relay.relayBatch();

        List<ConsumerRecord<String, String>> records = drain(COMMISSION_TOPIC, 2).stream()
                .filter(record -> record.key().equals(agent.id().value().toString()))
                .toList();

        assertThat(records).hasSize(2);
        assertThat(records)
                .extracting(record -> header(record, "event-type"))
                .containsExactly("CommissionCalculated", "CapThresholdReached");
        // Same key means same partition, which is what actually guarantees the ordering.
        assertThat(records)
                .extracting(ConsumerRecord::partition)
                .containsOnly(records.get(0).partition());
    }
}
