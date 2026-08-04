package com.revshare.commission.adapter.out.messaging;

import java.util.Map;

/**
 * Maps an outbox row's aggregate type onto the topic it publishes to.
 *
 * <h2>One topic per aggregate, not per event type</h2>
 *
 * <p>The tempting alternative is a topic per event type — {@code commission.calculated},
 * {@code commission.cap-reached}, and so on. It reads tidily and it is wrong here, because Kafka orders records within
 * a partition and nothing else. {@code CommissionCalculated} and {@code CapThresholdReached} describe the same agent at
 * the same instant: the closing that takes them to their cap emits both. Split across topics, a consumer can see the
 * cap announcement before the commission that caused it, and a read model projecting cap progress would briefly show an
 * agent capped with no transaction to explain it.
 *
 * <p>Grouping by aggregate keeps every event about one agent on one topic, and {@code DomainEvent#partitionKey()} keeps
 * them on one partition, so they arrive in the order they happened.
 *
 * <p>Revenue share events are a separate topic because they are keyed differently — by the contributing agent rather
 * than the beneficiary, since one event concerns up to five beneficiaries and none of them can own the ordering. Mixing
 * two keying schemes onto one topic would make the partition assignment meaningless.
 */
final class EventTopics {

    static final String COMMISSION = "revshare.commission.events";
    static final String REVENUE_SHARE = "revshare.revenue-share.events";
    static final String TRANSACTION = "revshare.transaction.events";
    static final String AGENT = "revshare.agent.events";

    /** Keyed by the {@code aggregate_type} column written by {@code OutboxEventPublisher}. */
    private static final Map<String, String> BY_AGGREGATE_TYPE = Map.of(
            "commission", COMMISSION,
            "revenue-share", REVENUE_SHARE,
            "transaction", TRANSACTION,
            "agent", AGENT);

    private EventTopics() {}

    /**
     * @throws IllegalStateException if an aggregate type has no topic, which means an event type was added without
     *     deciding where it belongs — better a loud failure in the relay than an event quietly published nowhere
     */
    static String forAggregateType(String aggregateType) {
        String topic = BY_AGGREGATE_TYPE.get(aggregateType);
        if (topic == null) {
            throw new IllegalStateException(
                    "no topic configured for aggregate type '" + aggregateType + "'; add one to EventTopics");
        }
        return topic;
    }

    static Map<String, String> all() {
        return BY_AGGREGATE_TYPE;
    }
}
