package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customerportal.rates.domain.CourierProviderAdapter;
import com.courier.modules.customerportal.rates.domain.DeliveryStage;
import com.courier.modules.customerportal.rates.domain.PortalBooking;
import com.courier.modules.customerportal.rates.domain.PortalBookingRepository;
import com.courier.modules.customerportal.rates.domain.ShipmentRef;
import com.courier.modules.customerportal.rates.domain.TrackingEvent;
import com.courier.modules.customerportal.rates.domain.TrackingResult;
import com.courier.modules.finance.application.WalletService;
import com.courier.modules.finance.application.command.CustomerBookingRefundCommand;
import com.courier.shared.company.CompanyContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps each booked portal shipment's delivery status ({@code portal_bookings.delivery_*}, V135) in step with its courier,
 * so the history can show and filter by it without asking the courier on every page.
 *
 * <p>Three ways in, one way to store:
 * <ul>
 *   <li>{@link #syncDue}: the background job ({@code TrackingSyncJob}) asks the courier about shipments whose
 *       {@code next_tracking_sync_at} has come — every {@code interval} while on the way, never again once finished.</li>
 *   <li>{@link #record}: whatever a customer's own tracking view fetched is saved too.</li>
 *   <li>{@link #recordPushed}: a courier's webhook. Its status is saved at once and the job confirms it with the
 *       courier shortly after, so a forged or garbled push is corrected by the courier's own answer.</li>
 * </ul>
 * The job runs with no signed-in user: each courier call is made inside {@link CompanyContext#runAs} for the booking's
 * company, and every write names the company explicitly.
 */
@Slf4j
@Service
public class TrackingSyncService {

    private final PortalBookingRepository bookings;
    private final List<CourierProviderAdapter> adapters;
    private final CustomerPortalProperties properties;
    private final TransactionTemplate tx;
    private final WalletService walletService;

    public TrackingSyncService(PortalBookingRepository bookings, List<CourierProviderAdapter> adapters,
                               CustomerPortalProperties properties, TransactionTemplate tx,
                               WalletService walletService) {
        this.walletService = walletService;
        this.bookings = bookings;
        this.adapters = adapters;
        this.properties = properties;
        this.tx = tx;
    }

    /** One run of the background sync. Returns how many shipments were looked at. */
    public int syncDue(Instant now) {
        CustomerPortalProperties.TrackingSync cfg = properties.getTrackingSync();
        if (!cfg.isEnabled()) {
            return 0;
        }
        List<PortalBooking> due = bookings.findDueForTrackingSyncAcrossCompanies(now, PageRequest.of(0, Math.max(1, cfg.getBatchSize())));
        for (PortalBooking b : due) {
            try {
                sync(b, now);
            } catch (RuntimeException e) {
                log.warn("Tracking sync of booking {} with {} failed: {}", b.getId(), b.getProviderCode(), e.getClass().getSimpleName());
                attempt(b.getId(), b.getCompanyId(), now, now.plus(cfg.getInterval()));
            }
        }
        if (!due.isEmpty()) {
            log.info("Tracking sync looked at {} shipment(s)", due.size());
        }
        return due.size();
    }

    private void sync(PortalBooking b, Instant now) {
        CustomerPortalProperties.TrackingSync cfg = properties.getTrackingSync();
        Instant booked = b.getBookedAt() != null ? b.getBookedAt() : b.getCreatedAt();
        Optional<CourierProviderAdapter> adapter = adapter(b.getProviderCode());
        if (adapter.isEmpty() || (booked != null && booked.isBefore(now.minus(cfg.getMaxAge())))) {
            attempt(b.getId(), b.getCompanyId(), now, null); // too old, or a provider this server no longer has: stop asking
            return;
        }
        ShipmentRef ref = new ShipmentRef(b.getCompanyId(), b.getCustomerId(), b.getId().toString(), b.getProviderOrderRef(),
                b.getAwbNumber(), b.getLabelUrl());
        TrackingResult result = CompanyContext.runAs(b.getCompanyId(), () -> adapter.get().track(ref));
        record(b.getId(), b.getCompanyId(), result, now);
    }

    /** Saves a courier's tracking answer for one booking. An answer that says nothing readable only reschedules. */
    public void record(UUID bookingId, UUID companyId, TrackingResult result, Instant now) {
        Instant next = now.plus(properties.getTrackingSync().getInterval());
        if (result == null || !result.available()) {
            attempt(bookingId, companyId, now, next);
            return;
        }
        TrackingEvent newest = result.events() == null || result.events().isEmpty() ? null : result.events().get(0);
        String text = firstNonBlank(result.status(), newest == null ? null : newest.status());
        DeliveryStage stage = DeliveryStage.of(text);
        if (stage == null && newest != null) {
            stage = DeliveryStage.of(newest.description());
        }
        if (stage == null) {
            attempt(bookingId, companyId, now, next);
            return;
        }
        String location = firstNonBlank(result.currentLocation(), newest == null ? null : newest.location());
        Instant at = newest == null ? null : newest.at();
        store(bookingId, companyId, stage, text, location, at, now, DeliveryStage.finished(stage, text) ? null : next);
    }

    /**
     * A courier pushed a status for one of the company's shipments. Saved at once (when readable) and confirmed with the
     * courier on the job's next run. Returns how many bookings it matched.
     */
    public int recordPushed(UUID companyId, String providerCode, String awb, String status, String location, Instant at, Instant now) {
        if (awb == null || awb.isBlank()) {
            return 0;
        }
        Instant confirm = now.plus(properties.getTrackingSync().getAfterWebhook());
        DeliveryStage stage = DeliveryStage.of(status);
        List<PortalBooking> matches = CompanyContext.runAs(companyId, () -> bookings.findByAwb(companyId, providerCode, awb.trim()));
        for (PortalBooking b : matches) {
            if (b.getStatus() != PortalBooking.Status.BOOKED) {
                continue;
            }
            if (stage != null) {
                store(b.getId(), companyId, stage, status, location, at, now, confirm);
            } else {
                tx.executeWithoutResult(s -> bookings.syncSoon(b.getId(), companyId, confirm));
            }
        }
        return matches.size();
    }

    private void store(UUID id, UUID companyId, DeliveryStage stage, String text, String location, Instant at, Instant now,
                       Instant next) {
        tx.executeWithoutResult(s -> bookings.recordDelivery(id, companyId, stage, trim(text, 150), trim(location, 150), at, now, next));
        if (stage == DeliveryStage.CANCELLED) {
            refundCancelled(id, companyId);
        }
    }

    /**
     * The courier cancelled the shipment: whatever the wallet paid for it goes back. Safe to repeat and a no-op for a
     * booking that was never paid from the wallet. A failure is logged for operations rather than failing the sync.
     */
    private void refundCancelled(UUID bookingId, UUID companyId) {
        try {
            CompanyContext.runAs(companyId, () -> walletService.refundCustomerBooking(
                    new CustomerBookingRefundCommand(bookingId, "Courier cancelled the shipment — booking " + bookingId)));
        } catch (RuntimeException e) {
            log.error("Booking {} was cancelled by its courier but its wallet debit could not be refunded; "
                    + "return the money manually", bookingId, e);
        }
    }

    private void attempt(UUID id, UUID companyId, Instant now, Instant next) {
        tx.executeWithoutResult(s -> bookings.recordSyncAttempt(id, companyId, now, next));
    }

    private Optional<CourierProviderAdapter> adapter(String code) {
        return adapters.stream().filter(a -> a.code().equals(code)).findFirst();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a.trim() : (b != null && !b.isBlank() ? b.trim() : null);
    }

    private static String trim(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }
}
