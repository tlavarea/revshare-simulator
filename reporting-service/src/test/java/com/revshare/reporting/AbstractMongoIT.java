package com.revshare.reporting;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MongoDBContainer;

/**
 * Base class for tests that need a real MongoDB.
 *
 * <p>A real Mongo rather than an embedded fake, for the same reason the write side uses a real Postgres: the things
 * worth testing here only exist in the real server. Multi-document transactions need a replica set, {@code Decimal128}
 * needs a server that has the type, and a TTL index needs the background reaper. An in-memory substitute would pass
 * while proving none of it.
 *
 * <p>{@code MongoDBContainer} starts a single-node <em>replica set</em>, not a standalone {@code mongod}, which is what
 * makes {@code @Transactional} in {@code DashboardProjectorImpl} real here. That is a property of this container class
 * specifically — pointing these tests at a plain {@code mongo:8} would fail on the first projected event.
 *
 * <p>One container for the whole suite: it is a static field started once, not a JUnit-managed per-class instance.
 * Collections are cleared between tests instead, which is far cheaper than a container restart and keeps each test
 * starting from an empty read model.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractMongoIT {

    @ServiceConnection
    protected static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

    static {
        MONGO.start();
    }

    @Autowired
    protected MongoTemplate mongo;

    /**
     * Empties the read model before each test.
     *
     * <p>Drops the documents rather than the collections, so the TTL index created at startup survives. Dropping the
     * collection would take the index with it and quietly disable expiry for every test after the first.
     */
    @BeforeEach
    void clearReadModel() {
        mongo.getCollectionNames().forEach(name -> mongo.getCollection(name).deleteMany(new org.bson.Document()));
    }
}
