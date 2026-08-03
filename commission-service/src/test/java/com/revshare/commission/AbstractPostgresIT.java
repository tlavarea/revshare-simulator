package com.revshare.commission;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests, backed by a real Postgres.
 *
 * <p>A real database rather than H2 or an in-memory fake, because most of what these tests verify does not exist
 * outside Postgres: the {@code uuid[]} sponsorship path, the partial index on the outbox, {@code jsonb} payloads, and
 * every {@code CHECK} constraint the schema relies on to make an invalid row impossible. A test that passed against H2
 * would tell us nothing about whether the migration actually applies in production.
 *
 * <p>The container is started once for the whole suite via a static initialiser rather than with {@code @Container},
 * which would start and stop one per test class. Combined with Spring's context caching that turns a multi-class suite
 * from a container start per class into one for the entire run.
 *
 * <p>{@code @ServiceConnection} wires the datasource straight from the container, so there is no
 * {@code @DynamicPropertySource} block duplicating URL, username and password. Liquibase then applies the real
 * changelog against it — the migrations are under test here too, not assumed.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractPostgresIT {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }
}
