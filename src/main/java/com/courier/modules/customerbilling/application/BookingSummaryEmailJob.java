package com.courier.modules.customerbilling.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Sends the business customers' booking summary every night at 23:30 IST. Each run covers the 24 hours that end at
 * 23:30 today (23:30 yesterday → 23:30 today), so a booking made between 23:30 and midnight is in the next night's
 * e-mail, never lost. The 23:40 and 23:50 ticks only retry a failed send: the ledger
 * ({@code booking_summary_email_log}) makes them no-ops for anyone already sent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingSummaryEmailJob {

    static final LocalTime CUTOFF = LocalTime.of(23, 30);

    private final BookingSummaryEmailService service;

    @Value("${app.booking-summary-email.enabled:true}")
    private boolean enabled = true;

    @Value("${app.booking-summary-email.zone:Asia/Kolkata}")
    private String zone = "Asia/Kolkata";

    @Scheduled(cron = "${app.booking-summary-email.cron:0 30,40,50 23 * * *}",
            zone = "${app.booking-summary-email.zone:Asia/Kolkata}")
    public void run() {
        if (!enabled) {
            return;
        }
        ZoneId z = ZoneId.of(zone);
        LocalDate today = LocalDate.now(z);
        Instant[] w = window(today, z);
        int attempted = service.sendFor(today, w[0], w[1]);
        if (attempted > 0) {
            log.info("Booking summary e-mail for {}: {} send(s) attempted", today, attempted);
        }
    }

    /** [23:30 the day before, 23:30 on {@code day}) in {@code zone}. */
    static Instant[] window(LocalDate day, ZoneId zone) {
        return new Instant[]{day.minusDays(1).atTime(CUTOFF).atZone(zone).toInstant(), day.atTime(CUTOFF).atZone(zone).toInstant()};
    }
}
