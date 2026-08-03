package com.revshare.commission.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.revshare.commission.AbstractPostgresIT;
import com.revshare.domain.port.in.RecordClosedTransaction;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Asserts that every {@code @Service} is proxied through a JDK dynamic proxy rather than CGLIB.
 *
 * <p>This exists because the convention is otherwise unenforceable. Declaring an interface is necessary but <em>not
 * sufficient</em>: Spring Boot defaults {@code spring.aop.proxy-target-class} to {@code true}, which forces class-based
 * proxying regardless of what interfaces a bean implements. A future contributor could add a perfectly good interface,
 * see it injected by that interface, and still be getting CGLIB — with nothing anywhere to say so.
 *
 * <p>Why it matters. CGLIB proxies work by subclassing the bean, so they silently fail to advise a {@code final} class
 * or a {@code final} method, and they generate a class at runtime per proxied type. JDK dynamic proxies have neither
 * problem, and they make the "depend on the interface" rule structural instead of aspirational: a JDK proxy cannot be
 * cast to the implementation, so an accidental dependency on a concrete service fails at startup rather than quietly
 * working.
 */
class ProxyStrategyIT extends AbstractPostgresIT {

    @Autowired
    private RecordClosedTransaction recordClosedTransaction;

    @Autowired
    private BeneficiaryStandingResolver beneficiaryStandingResolver;

    @Test
    @DisplayName("the use case is a JDK dynamic proxy, not a CGLIB subclass")
    void useCaseUsesJdkProxy() {
        assertThat(AopUtils.isAopProxy(recordClosedTransaction))
                .as("@Transactional guarantees this bean is proxied")
                .isTrue();
        assertThat(Proxy.isProxyClass(recordClosedTransaction.getClass()))
                .as("expected a JDK dynamic proxy, got %s", recordClosedTransaction.getClass())
                .isTrue();
        assertThat(AopUtils.isCglibProxy(recordClosedTransaction)).isFalse();
    }

    @Test
    @DisplayName("the resolver is a JDK dynamic proxy, not a CGLIB subclass")
    void resolverUsesJdkProxy() {
        assertThat(AopUtils.isAopProxy(beneficiaryStandingResolver)).isTrue();
        assertThat(Proxy.isProxyClass(beneficiaryStandingResolver.getClass()))
                .as("expected a JDK dynamic proxy, got %s", beneficiaryStandingResolver.getClass())
                .isTrue();
        assertThat(AopUtils.isCglibProxy(beneficiaryStandingResolver)).isFalse();
    }

    @Test
    @DisplayName("services are injectable only by their interface")
    void servicesAreInjectedByInterface() {
        // A JDK proxy implements the interface without extending the implementation, so this
        // is what makes "depend on the contract" enforced rather than merely encouraged.
        assertThat(beneficiaryStandingResolver).isNotInstanceOf(BeneficiaryStandingResolverImpl.class);
        assertThat(recordClosedTransaction).isNotInstanceOf(RecordClosedTransactionService.class);
    }
}
