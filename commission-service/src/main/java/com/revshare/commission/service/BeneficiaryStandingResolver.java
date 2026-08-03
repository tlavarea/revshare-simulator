package com.revshare.commission.service;

import com.revshare.domain.agent.AgentId;
import com.revshare.domain.agent.CapYear;
import com.revshare.domain.revshare.BeneficiaryStanding;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Gathers the facts {@code RevenueShareCalculator} needs about each potential beneficiary.
 *
 * <p>An interface over a single implementation, deliberately. Every {@code @Service} here is proxied —
 * {@link org.springframework.transaction.annotation.Transactional} guarantees it — and Spring can only produce a JDK
 * dynamic proxy when the bean implements one. Without an interface it falls back to CGLIB, which subclasses the bean:
 * that requires a non-final class with a non-final method for every advised call, silently does nothing when either is
 * final, and generates a class at runtime for each proxied type.
 *
 * <p>Note that the interface alone is not sufficient. Spring Boot defaults {@code spring.aop.proxy-target-class} to
 * {@code true}, which forces class-based proxying whether or not interfaces exist; {@code application.yaml} sets it to
 * {@code false}, and {@code ProxyStrategyIT} asserts the result rather than trusting it.
 *
 * <p>The interface also states the seam plainly. The one caller, {@link RecordClosedTransactionService}, depends on
 * this contract rather than on how the facts are fetched — which is the whole point, since fetching them without an N+1
 * is the entire substance of the implementation.
 */
public interface BeneficiaryStandingResolver {

    /**
     * Resolves the standing of every ancestor eligible to earn from a closing.
     *
     * <p>Every fact is resolved as at {@code closedOn}, never "now". Reprocessing a six-month-old closing has to reach
     * the verdict it reached originally.
     *
     * @param upline the contributor's ancestors within program reach, nearest first
     * @param contributorId whose production funds the distribution
     * @param closedOn the date every fact is resolved as at
     * @param contributorCapYear the allowance window, which belongs to the contributor
     * @return standing per beneficiary; empty when the upline is empty
     */
    Map<AgentId, BeneficiaryStanding> resolve(
            List<AgentId> upline, AgentId contributorId, LocalDate closedOn, CapYear contributorCapYear);
}
