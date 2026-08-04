package com.revshare.reporting;

import com.revshare.domain.event.DomainEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Base class for tests that drive the real Kafka listener.
 *
 * <p>Separate from {@link AbstractMongoIT} for the same reason the write side splits its two: a broker costs about ten
 * seconds to start and the projection tests do not need one. Extending {@code AbstractMongoIT} shares the Mongo
 * container rather than starting a second.
 *
 * <p>The listener is switched off for every other test in this module (see {@code application-test.yaml}) and turned
 * back on here, so only the tests that have a broker try to reach one.
 *
 * <p>Pinned to {@code apache/kafka:3.9.2} to match the write side and {@code docker-compose.yml}; see
 * {@code AbstractKafkaIT} in {@code commission-service} for why 3.9.0 cannot be used.
 */
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=true")
public abstract class AbstractStreamIT extends AbstractMongoIT {

    @ServiceConnection
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.2");

    static {
        KAFKA.start();
    }

    /**
     * Publishes an event exactly as the outbox relay would: the payload as a JSON string, keyed by the event's
     * partition key, with the {@code event-type} header the reader dispatches on.
     */
    protected static void publish(String topic, DomainEvent event) {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.ACKS_CONFIG, "all");

        ProducerRecord<String, String> record =
                new ProducerRecord<>(topic, null, event.partitionKey(), WriteSideEventFormat.serialise(event));

        record.headers()
                .add(new RecordHeader("event-id", event.eventId().toString().getBytes(StandardCharsets.UTF_8)))
                .add(new RecordHeader(
                        "event-type", event.getClass().getSimpleName().getBytes(StandardCharsets.UTF_8)))
                .add(new RecordHeader(
                        "occurred-at", event.occurredAt().toString().getBytes(StandardCharsets.UTF_8)));

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
            producer.send(record).get();
        } catch (Exception e) {
            throw new IllegalStateException("could not publish to " + topic, e);
        }
    }

    /** Publishes a record with an arbitrary header set, for the cases where the header is what is under test. */
    protected static void publishRaw(String topic, String key, String payload, String eventType) {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        ProducerRecord<String, String> record = new ProducerRecord<>(topic, null, key, payload);
        if (eventType != null) {
            record.headers().add(new RecordHeader("event-type", eventType.getBytes(StandardCharsets.UTF_8)));
        }

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
            producer.send(record).get();
        } catch (Exception e) {
            throw new IllegalStateException("could not publish to " + topic, e);
        }
    }

    /**
     * Polls until the condition holds or the deadline passes.
     *
     * <p>A hand-rolled loop rather than a library, because the only thing needed is "keep checking Mongo until the
     * projection shows up". Consumption is asynchronous, so there is no callback to hang an assertion on and a fixed
     * sleep would either be flaky or slow.
     */
    protected static void await(String description, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while awaiting " + description, e);
            }
        }
        throw new AssertionError("timed out after 30s awaiting " + description);
    }
}
