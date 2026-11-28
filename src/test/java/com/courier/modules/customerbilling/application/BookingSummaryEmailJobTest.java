package com.courier.modules.customerbilling.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class BookingSummaryEmailJobTest {

    @Test
    @DisplayName("the 28 Nov e-mail covers 27 Nov 23:30 IST up to 28 Nov 23:30 IST, so nightly windows meet with no gap")
    void window() {
        Instant[] w = BookingSummaryEmailJob.window(LocalDate.of(2026, 11, 28), ZoneId.of("Asia/Kolkata"));
        assertThat(w[0]).isEqualTo(Instant.parse("2026-11-27T18:00:00Z"));
        assertThat(w[1]).isEqualTo(Instant.parse("2026-11-28T18:00:00Z"));
        assertThat(BookingSummaryEmailJob.window(LocalDate.of(2026, 11, 29), ZoneId.of("Asia/Kolkata"))[0]).isEqualTo(w[1]);
    }
}
