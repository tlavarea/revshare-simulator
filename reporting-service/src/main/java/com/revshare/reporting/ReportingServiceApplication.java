package com.revshare.reporting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The read side.
 *
 * <p>Consumes the domain event stream produced by {@code commission-service} and maintains a denormalized agent
 * dashboard in MongoDB. Holds no business rules: every figure it stores was calculated by the write side's pure
 * calculators and carried in an event.
 *
 * <p>No {@code @EnableMongoRepositories} or {@code @EnableKafka} — both are auto-configured from the starters on the
 * classpath, and adding the annotations would override the auto-configuration's defaults rather than supplement them.
 */
@SpringBootApplication
public class ReportingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReportingServiceApplication.class, args);
    }
}
