package com.revshare.reporting.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;

/**
 * Turns on multi-document transactions for the read store.
 *
 * <p>Spring Boot does not auto-configure a {@link MongoTransactionManager} — declaring one is the opt-in, and without
 * it {@code @Transactional} on a Mongo repository call is silently a no-op. "Silently" is the problem: the projector
 * would appear to work, and the atomicity it depends on to avoid double-counting a redelivered event would simply not
 * be there. Declaring this bean is what makes {@code DashboardProjectorImpl}'s transaction real.
 *
 * <p><strong>Requires a replica set.</strong> Mongo has no transactions on a standalone server, single node or not.
 * {@code docker-compose.yml} starts it with {@code --replSet rs0} and initiates the set from its healthcheck;
 * Testcontainers' {@code MongoDBContainer} does the equivalent on its own. Pointed at a standalone {@code mongod} this
 * service fails on the first event rather than degrading quietly, which is the outcome to want.
 */
@Configuration
public class MongoTransactionConfiguration {

    /** The factory is auto-configured from {@code spring.data.mongodb.uri}; the manager is not. */
    @Bean
    public MongoTransactionManager mongoTransactionManager(MongoDatabaseFactory databaseFactory) {
        return new MongoTransactionManager(databaseFactory);
    }

    /**
     * The clock the projector stamps documents with.
     *
     * <p>Injected rather than called statically so a test can pin it, matching the write side. The read side has no
     * business rules that depend on time, but {@code lastProjectedAt} is far easier to assert against a fixed clock.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
