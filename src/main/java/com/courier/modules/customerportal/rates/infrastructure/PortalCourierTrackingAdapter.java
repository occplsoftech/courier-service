package com.courier.modules.customerportal.rates.infrastructure;

import com.courier.modules.customerportal.rates.domain.CourierProviderAdapter;
import com.courier.modules.customerportal.rates.domain.PortalBooking;
import com.courier.modules.customerportal.rates.domain.PortalBookingRepository;
import com.courier.modules.customerportal.rates.domain.RateQuote;
import com.courier.modules.customerportal.rates.domain.RateQuoteRepository;
import com.courier.modules.shipment.domain.CourierBookingTrackingPort;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** What the public tracking page shows for an outside-courier AWB: the booking's last saved delivery status, never a live call. */
@Component
@RequiredArgsConstructor
public class PortalCourierTrackingAdapter implements CourierBookingTrackingPort {

    private final PortalBookingRepository bookings;
    private final RateQuoteRepository quotes;
    private final ObjectProvider<List<CourierProviderAdapter>> adapters;

    @Override
    @Transactional(readOnly = true)
    public Optional<CourierTrack> findByAwb(String awb) {
        if (awb == null || awb.isBlank()) {
            return Optional.empty();
        }
        List<PortalBooking> found = bookings.findOutsideCourierByAwbAcrossCompanies(awb.trim());
        if (found.size() != 1) {
            return Optional.empty();
        }
        PortalBooking b = found.get(0);
        RateQuote q = quotes.findOwned(b.getQuoteId(), b.getCompanyId(), b.getCustomerId()).orElse(null);
        Map<String, Object> request = q == null || q.getRequest() == null ? Map.of() : q.getRequest();
        String stage = b.getStatus() == PortalBooking.Status.CANCELLED ? "CANCELLED"
                : b.getDeliveryStatus() == null ? null : b.getDeliveryStatus().name();
        return Optional.of(new CourierTrack(b.getAwbNumber(), b.getProviderOrderRef(), courierName(b.getProviderCode()), stage,
                b.getDeliveryStatusText(), b.getDeliveryLocation(), b.getBookedAt() != null ? b.getBookedAt() : b.getCreatedAt(),
                b.getDeliveryStatusAt(), city(request.get("pickup")), city(request.get("delivery"))));
    }

    private String courierName(String code) {
        List<CourierProviderAdapter> all = adapters.getIfAvailable(List::of);
        return all.stream().filter(a -> a.code().equals(code)).map(CourierProviderAdapter::displayName).findFirst().orElse(code);
    }

    private static String city(Object party) {
        if (party instanceof Map<?, ?> m) {
            Object c = m.get("city") != null ? m.get("city") : m.get("pincode");
            return c == null || c.toString().isBlank() ? null : c.toString().trim();
        }
        return null;
    }
}
