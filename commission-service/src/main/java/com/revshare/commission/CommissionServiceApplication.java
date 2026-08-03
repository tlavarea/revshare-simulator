package com.revshare.commission;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * The write side of the simulator.
 *
 * <p>Owns the Postgres schema, prices closings against the commission plan, tracks each agent's progress toward their
 * cap, distributes revenue share up the sponsorship tree, and records the resulting domain events to a transactional
 * outbox for the read side to consume.
 */
@SpringBootApplication
@EnableTransactionManagement
public class CommissionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CommissionServiceApplication.class, args);
    }
}
