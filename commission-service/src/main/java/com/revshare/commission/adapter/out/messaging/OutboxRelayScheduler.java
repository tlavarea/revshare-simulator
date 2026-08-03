package com.revshare.commission.adapter.out.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link OutboxRelay} on a timer.
 *
 * <h2>Why this is a separate bean</h2>
 *
 * <p>The timer reads more naturally as a scheduled method on the relay itself, and that version is silently broken.
 * {@code @Transactional} is applied by a proxy wrapped around the bean, so it takes effect only on calls arriving from
 * outside it. A scheduled method calling {@code relayBatch()} on {@code this} goes straight to the implementation and
 * never touches the proxy — the annotation is still present, still reads correctly, and does nothing.
 *
 * <p>The consequences are not subtle. Rows claimed outside a transaction come back detached, so {@code markPublished()}
 * mutates an object nothing will ever flush and {@code published_at} stays null; the next poll claims the same rows and
 * publishes them again, every 500ms, indefinitely. The {@code FOR UPDATE SKIP LOCKED} claim stops meaning anything too,
 * because the locks are released as soon as the query's own transaction ends rather than being held across the publish.
 *
 * <p>Injecting the relay puts a proxy between the timer and {@code relayBatch()}, which is what makes the transaction
 * real. {@code OutboxRelayIT} pins the behaviour by driving this class rather than calling {@code relayBatch()}
 * directly — a test that calls the relay from outside gets a transaction either way and so cannot see the difference.
 */
@Component
public class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelay relay;

    public OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    /**
     * Polls for unpublished events and relays them.
     *
     * <p>The interval is a latency-versus-load trade with no correct answer: the events are already durable, so a
     * slower poll only delays the read side. Default 500ms.
     */
    @Scheduled(
            fixedDelayString = "${revshare.outbox.poll-interval-ms:500}",
            initialDelayString = "${revshare.outbox.initial-delay-ms:1000}")
    public void relayPendingEvents() {
        try {
            int published = relay.relayBatch();
            if (published > 0) {
                log.debug("relayed {} outbox events", published);
            }
        } catch (RuntimeException e) {
            // Swallowed on purpose. A scheduled method that throws is not retried, and with a
            // fixed delay the next tick comes round anyway — but an uncaught exception here
            // would be logged by the scheduler without context. The rows stay unpublished, so
            // nothing is lost; the next poll picks them up.
            log.error("outbox relay failed; unpublished events remain for the next poll", e);
        }
    }
}
