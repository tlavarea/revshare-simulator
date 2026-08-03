package com.revshare.commission;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Base class for tests that need a real broker as well as a real database.
 *
 * <p>Separate from {@link AbstractPostgresIT} on purpose. A Kafka container costs roughly ten seconds to start, and
 * only a handful of tests need one — there is no reason the eighteen persistence tests should pay for it. Extending
 * {@code AbstractPostgresIT} means the Postgres container is shared rather than started a second time.
 *
 * <p>Uses {@code org.testcontainers.kafka.KafkaContainer}, which drives the plain {@code apache/kafka} image in KRaft
 * mode — the same image and the same mode as {@code docker-compose.yml}, so a test broker and a development broker
 * behave identically. (The older {@code org.testcontainers.containers.KafkaContainer} runs Confluent's image and is
 * deprecated.)
 *
 * <p><strong>Do not drop this to 3.9.0.</strong> That tag cannot start under Testcontainers at all: its entrypoint
 * formats the log directory before Testcontainers has published the mapped port, so the config it validates still
 * carries the placeholder {@code advertised.listeners=0.0.0.0} and the broker exits 1 with "cannot use the nonroutable
 * meta-address". 3.9.1 fixed the format step; 3.9.2 additionally matches the {@code kafka-clients} version Spring Kafka
 * resolves, so the broker and the client under test are the same release.
 */
@TestPropertySource(properties = "spring.kafka.admin.auto-create=true")
public abstract class AbstractKafkaIT extends AbstractPostgresIT {

    @ServiceConnection
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.2");

    static {
        KAFKA.start();
    }

    /**
     * Drains a topic from the beginning and returns what is there.
     *
     * <p>A plain consumer rather than {@code @KafkaListener}, because a listener delivers asynchronously and a test
     * would have to await an unknown number of records. Polling until quiet gives a deterministic snapshot.
     *
     * @param topic topic to read
     * @param expectedCount stop as soon as this many records have arrived
     */
    protected static List<ConsumerRecord<String, String>> drain(String topic, int expectedCount) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        // A fresh group per call, so each test reads the topic from the beginning regardless of
        // what previous tests consumed.
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + java.util.UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        List<ConsumerRecord<String, String>> collected = new java.util.ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + Duration.ofSeconds(20).toMillis();
            while (System.currentTimeMillis() < deadline && collected.size() < expectedCount) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
                polled.records(topic).forEach(collected::add);
            }
        }
        return collected;
    }

    /** Reads a record header as a string, or null when absent. */
    protected static String header(ConsumerRecord<String, String> record, String name) {
        var found = record.headers().lastHeader(name);
        return found == null ? null : new String(found.value(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Convenience for asserting on a record's headers as a map. */
    protected static Map<String, String> headers(ConsumerRecord<String, String> record) {
        Map<String, String> all = new java.util.LinkedHashMap<>();
        record.headers().forEach(h -> all.put(h.key(), new String(h.value(), java.nio.charset.StandardCharsets.UTF_8)));
        return all;
    }
}
