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
 * <p>An interface over a single implementation, for the seam rather than for the proxy. The one caller,
 * {@link RecordClosedTransactionService}, depends on this contract rather than on how the facts are fetched — which is
 * the point, since fetching them without an N+1 is the entire substance of the implementation, and is the part most
 * likely to be replaced.
 *
 * <p>Explicitly <em>not</em> here to force JDK dynamic proxies. Spring Boot defaults
 * {@code spring.aop.proxy-target-class} to {@code true} and this project leaves it there, so services are proxied by
 * CGLIB. That default exists for good reason: JDK proxies fail with a {@code ClassCastException} anywhere something
 * injects or casts to the concrete type, and Spring repackages both CGLIB and Objenesis inside {@code spring-core}, so
 * the historical objections to class-based proxying — an extra dependency, jar conflicts, a required default
 * constructor — no longer apply. The one genuine cost is that {@code final} classes and methods are silently unadvised,
 * which is avoided by not making service methods final rather than by overriding a framework default.
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
