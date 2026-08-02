package com.revshare.domain.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CapYearTest {

    @Test
    @DisplayName("runs from the agent's anniversary, not from January")
    void anchorsOnTheAnniversaryNotTheCalendarYear() {
        LocalDate joined = LocalDate.of(2024, 3, 14);

        CapYear first = CapYear.containing(joined, LocalDate.of(2024, 12, 31));

        assertThat(first.start()).isEqualTo(LocalDate.of(2024, 3, 14));
        assertThat(first.endExclusive()).isEqualTo(LocalDate.of(2025, 3, 14));
        assertThat(first.ordinal()).isZero();
    }

    @Test
    @DisplayName("rolls over on the anniversary, so New Year's Day does not reset the cap")
    void doesNotResetInJanuary() {
        LocalDate joined = LocalDate.of(2024, 3, 14);

        CapYear beforeAnniversary = CapYear.containing(joined, LocalDate.of(2025, 3, 13));
        CapYear onAnniversary = CapYear.containing(joined, LocalDate.of(2025, 3, 14));

        assertThat(beforeAnniversary.ordinal()).isZero();
        assertThat(onAnniversary.ordinal()).isEqualTo(1);
        assertThat(onAnniversary.start()).isEqualTo(LocalDate.of(2025, 3, 14));
    }

    @ParameterizedTest(name = "{0} joined, evaluated on {1}, is cap year {2}")
    @CsvSource({
        "2024-03-14, 2024-03-14, 0",
        "2024-03-14, 2025-03-13, 0",
        "2024-03-14, 2025-03-14, 1",
        "2024-03-14, 2027-06-30, 3",
        "2024-01-01, 2024-12-31, 0",
        "2024-12-31, 2025-01-01, 0",
    })
    @DisplayName("resolves the window a date falls into")
    void resolvesTheContainingWindow(LocalDate joined, LocalDate on, int expectedOrdinal) {
        CapYear capYear = CapYear.containing(joined, on);

        assertThat(capYear.ordinal()).isEqualTo(expectedOrdinal);
        assertThat(capYear.contains(on)).isTrue();
    }

    @Test
    @DisplayName("handles an agent who joined on 29 February")
    void handlesLeapDayAnniversaries() {
        // The awkward case: plusYears clamps 29 February to 28 February in a non-leap year,
        // which a naive implementation lets fall through the gap between two windows.
        LocalDate joined = LocalDate.of(2024, 2, 29);

        for (LocalDate date : new LocalDate[] {
            LocalDate.of(2024, 2, 29),
            LocalDate.of(2025, 2, 27),
            LocalDate.of(2025, 2, 28),
            LocalDate.of(2025, 3, 1),
            LocalDate.of(2028, 2, 29),
        }) {
            CapYear capYear = CapYear.containing(joined, date);
            assertThat(capYear.contains(date))
                    .as("cap year %s should contain the date %s it was derived from", capYear, date)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("gives 29 February joiners a window boundary on 28 February in common years")
    void leapDayAnniversaryFallsBackToTheTwentyEighth() {
        LocalDate joined = LocalDate.of(2024, 2, 29);

        CapYear second = CapYear.containing(joined, LocalDate.of(2025, 6, 1));

        assertThat(second.start()).isEqualTo(LocalDate.of(2025, 2, 28));
        assertThat(second.ordinal()).isEqualTo(1);
    }

    @Test
    @DisplayName("produces windows that tile the timeline without gaps or overlaps")
    void windowsTileTheTimeline() {
        LocalDate joined = LocalDate.of(2024, 2, 29);
        CapYear current = CapYear.containing(joined, joined);

        for (int i = 0; i < 12; i++) {
            CapYear next = current.next(joined);
            assertThat(next.start())
                    .as("cap year %d should start where cap year %d ended", i + 1, i)
                    .isEqualTo(current.endExclusive());
            assertThat(next.ordinal()).isEqualTo(current.ordinal() + 1);
            current = next;
        }
    }

    @Test
    @DisplayName("rejects a date before the agent existed")
    void rejectsDatesBeforeJoining() {
        LocalDate joined = LocalDate.of(2024, 3, 14);

        assertThatThrownBy(() -> CapYear.containing(joined, LocalDate.of(2024, 3, 13)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("precedes the agent's join date");
    }

    @Test
    @DisplayName("treats the end of the window as exclusive")
    void endIsExclusive() {
        CapYear capYear = CapYear.containing(LocalDate.of(2024, 3, 14), LocalDate.of(2024, 6, 1));

        assertThat(capYear.contains(capYear.start())).isTrue();
        assertThat(capYear.contains(capYear.endExclusive().minusDays(1))).isTrue();
        assertThat(capYear.contains(capYear.endExclusive())).isFalse();
    }
}
