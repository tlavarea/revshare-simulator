package com.revshare.domain.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    @DisplayName("normalises scale so that equality behaves as callers expect")
    void equalityIsNotScaleSensitive() {
        // BigDecimal.equals compares scale as well as value, so 2.5 and 2.50 are unequal.
        // Normalising in the constructor is what makes Money usable as a map key and in
        // assertions without every call site remembering to use compareTo.
        assertThat(new BigDecimal("2.5")).isNotEqualTo(new BigDecimal("2.50"));
        assertThat(Money.of("2.5")).isEqualTo(Money.of("2.50"));
        assertThat(Money.of("2.5")).hasSameHashCodeAs(Money.of("2.50"));
    }

    @Test
    @DisplayName("rounds half up to the cent")
    void roundsHalfUp() {
        assertThat(Money.of("0.125").amount()).isEqualTo(new BigDecimal("0.13"));
        assertThat(Money.of("0.124").amount()).isEqualTo(new BigDecimal("0.12"));
    }

    @Test
    @DisplayName("applies a rate and rounds the product once")
    void appliesRates() {
        assertThat(Money.of("10000.00").multipliedBy(Percentage.ofPercent("15")))
                .isEqualTo(Money.of("1500.00"));
        // 15% of 33,333.33 is 4,999.9995, which rounds to a whole 5,000.00.
        assertThat(Money.of("33333.33").multipliedBy(Percentage.ofPercent("15")))
                .isEqualTo(Money.of("5000.00"));
    }

    @Test
    @DisplayName("recovers the base amount a share was taken from")
    void divisionInvertsMultiplication() {
        Percentage split = Percentage.ofPercent("15");

        assertThat(Money.of("12000.00").dividedBy(split)).isEqualTo(Money.of("80000.00"));
        assertThat(Money.of("1500.00").dividedBy(split)).isEqualTo(Money.of("10000.00"));
    }

    @Test
    @DisplayName("refuses to divide by a zero rate")
    void rejectsDivisionByZeroRate() {
        assertThatThrownBy(() -> Money.of("100.00").dividedBy(Percentage.ZERO)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("permits negative amounts, which reversals need")
    void allowsNegatives() {
        Money reversal = Money.of("500.00").minus(Money.of("800.00"));

        assertThat(reversal).isEqualTo(Money.of("-300.00"));
        assertThat(reversal.isNegative()).isTrue();
        assertThat(reversal.atLeastZero()).isEqualTo(Money.ZERO);
        assertThat(reversal.negated()).isEqualTo(Money.of("300.00"));
    }

    @Test
    @DisplayName("orders and compares amounts")
    void comparesAmounts() {
        assertThat(Money.min(Money.of("285.00"), Money.of("129.00"))).isEqualTo(Money.of("129.00"));
        assertThat(Money.max(Money.of("285.00"), Money.of("129.00"))).isEqualTo(Money.of("285.00"));
        assertThat(Money.of("12000.00").isGreaterThanOrEqualTo(Money.of("12000.00")))
                .isTrue();
        assertThat(Money.of("11999.99").isLessThan(Money.of("12000.00"))).isTrue();
    }

    @Test
    @DisplayName("keeps percent and fraction forms distinct")
    void percentageFormsAreUnambiguous() {
        // The hundred-fold error this API exists to prevent.
        assertThat(Percentage.ofPercent("15").fraction()).isEqualByComparingTo("0.15");
        assertThat(Percentage.ofFraction(new BigDecimal("0.15"))).isEqualTo(Percentage.ofPercent("15"));
        assertThat(Percentage.ofPercent("15").complement()).isEqualTo(Percentage.ofPercent("85"));
        assertThat(Percentage.ofPercent("15")).hasToString("15%");
    }
}
