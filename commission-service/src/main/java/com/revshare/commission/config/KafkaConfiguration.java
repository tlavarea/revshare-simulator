package com.revshare.commission.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topics the relay publishes to.
 *
 * <p>Created explicitly rather than left to broker auto-creation. An auto-created topic takes the broker's defaults for
 * partition count and replication, which means the number of partitions — the thing that caps consumer parallelism and
 * cannot be reduced later — ends up decided by whichever environment the topic happened to be created in first.
 *
 * <p>Partition count is the interesting knob. Ordering is guaranteed only within a partition, and every event here is
 * keyed by the aggregate it concerns, so all of one agent's events land on one partition and stay ordered. More
 * partitions therefore buys consumer parallelism without weakening that guarantee — but only up to the number of
 * distinct keys, and it can never be lowered without recreating the topic and rehashing every key.
 */
@Configuration
public class KafkaConfiguration {

    /**
     * Three by default: enough to demonstrate that parallelism is partitioned by agent rather than serialized, small
     * enough to run on a single-broker development cluster.
     */
    @Value("${revshare.kafka.partitions:3}")
    private int partitions;

    /**
     * One on a single-broker cluster, because more is impossible. A real deployment wants at least three with
     * {@code min.insync.replicas=2}, so an acknowledged write survives losing a broker.
     */
    @Value("${revshare.kafka.replicas:1}")
    private int replicas;

    @Bean
    public NewTopic commissionEventsTopic() {
        return topic("revshare.commission.events");
    }

    @Bean
    public NewTopic revenueShareEventsTopic() {
        return topic("revshare.revenue-share.events");
    }

    @Bean
    public NewTopic transactionEventsTopic() {
        return topic("revshare.transaction.events");
    }

    private NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(partitions).replicas(replicas).build();
    }
}
