package com.courier.modules.customerportal.rates.infrastructure;

import com.courier.modules.communication.domain.PortalBookingDirectoryPort;
import com.courier.modules.communication.domain.ShipmentSnapshot;
import com.courier.modules.company.domain.Company;
import com.courier.modules.company.domain.CompanyRepository;
import com.courier.modules.customer.domain.Customer;
import com.courier.modules.customer.domain.CustomerRepository;
import com.courier.modules.customerportal.rates.domain.PortalBooking;
import com.courier.modules.customerportal.rates.domain.PortalBookingRepository;
import com.courier.modules.customerportal.rates.domain.RateQuote;
import com.courier.modules.customerportal.rates.domain.RateQuoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Message data for a portal booking made with an outside courier: the booking's own AWB and reference, the parties the customer
 * entered, and the price they paid. The sender's channel preferences are the booking customer's; the receiver is matched to a
 * customer by mobile when there is one (otherwise opted in, as for a shipment). OCCPL Network bookings are left to their shipment.
 */
@Component
@RequiredArgsConstructor
public class PortalBookingDirectoryAdapter implements PortalBookingDirectoryPort {

    private static final String OCCPL_NETWORK = "OCCPL_NETWORK";

    private final PortalBookingRepository bookings;
    private final RateQuoteRepository quotes;
    private final CompanyRepository companies;
    private final CustomerRepository customers;

    @Override
    @Transactional(readOnly = true)
    public Optional<ShipmentSnapshot> findSnapshot(UUID companyId, UUID bookingId) {
        Optional<PortalBooking> found = bookings.findById(bookingId)
                .filter(b -> companyId.equals(b.getCompanyId()) && !OCCPL_NETWORK.equals(b.getProviderCode()));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        PortalBooking b = found.get();
        RateQuote q = quotes.findOwned(b.getQuoteId(), companyId, b.getCustomerId()).orElse(null);
        Map<String, Object> request = q == null || q.getRequest() == null ? Map.of() : q.getRequest();
        Map<String, Object> pickup = map(request.get("pickup"));
        Map<String, Object> delivery = map(request.get("delivery"));

        Customer owner = customers.findById(b.getCustomerId()).filter(c -> companyId.equals(c.getCompanyId())).orElse(null);
        ShipmentSnapshot.Party sender = owner == null
                ? party(companyId, str(pickup.get("name")), str(pickup.get("phone")))
                : new ShipmentSnapshot.Party(str(pickup.get("name")) == null ? owner.getCompanyName() : str(pickup.get("name")),
                        firstNonBlank(owner.getMobile(), str(pickup.get("phone"))), owner.getId(), owner.getEmail(),
                        owner.isWhatsappEnabled(), owner.isSmsEnabled(),
                        owner.getEmail() != null && !owner.getEmail().isBlank() && owner.isEmailEnabled());
        ShipmentSnapshot.Party receiver = party(companyId, str(delivery.get("name")), str(delivery.get("phone")));
        String companyName = companies.findByCompanyId(companyId).map(Company::effectiveDisplayName).orElse("");
        String reference = firstNonBlank(b.getProviderOrderRef(), b.getAwbNumber());

        return Optional.of(new ShipmentSnapshot(b.getId(), reference, b.getAwbNumber(), companyName, sender, receiver,
                firstNonBlank(str(pickup.get("city")), str(pickup.get("pincode"))),
                firstNonBlank(str(delivery.get("city")), str(delivery.get("pincode"))),
                b.getTotalAmount(), null, null));
    }

    private ShipmentSnapshot.Party party(UUID companyId, String name, String contact) {
        Customer c = contact == null || contact.isBlank() ? null : customers.findByCompanyIdAndMobile(companyId, contact.trim()).orElse(null);
        return new ShipmentSnapshot.Party(name, contact, c == null ? null : c.getId(), c == null ? null : c.getEmail(),
                c == null || c.isWhatsappEnabled(), c == null || c.isSmsEnabled(),
                c != null && c.getEmail() != null && !c.getEmail().isBlank() && c.isEmailEnabled());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String str(Object o) {
        return o == null || o.toString().isBlank() ? null : o.toString().trim();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
