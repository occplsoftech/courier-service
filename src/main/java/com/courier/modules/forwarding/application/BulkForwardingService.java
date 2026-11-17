package com.courier.modules.forwarding.application;

import com.courier.modules.forwarding.application.ForwardingService.ForwardOptions;
import com.courier.modules.forwarding.application.ForwardingService.ForwardResult;
import com.courier.shared.exception.ApiException;
import com.courier.shared.exception.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Forwarding several shipments from a hub at once (14.1): the couriers' prices for each, then each chosen service booked one after the
 * other. Every shipment goes through {@link ForwardingService} exactly as a single forward does (who may, blockers, fresh price, audit,
 * never twice), and one shipment's failure never stops the rest. The staff app sends a few at a time, so it can show progress.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkForwardingService {

    /** Per request; the staff app sends small chunks and selects at most 50 in all. */
    static final int MAX_PER_CALL = 20;

    private final ForwardingService forwarding;

    /** One shipment's prices, or why it cannot be priced ({@code error}: not found, not allowed…). */
    public record OptionsRow(UUID shipmentId, ForwardOptions options, String error) {
    }

    public record Item(UUID shipmentId, String providerCode, String serviceCode) {
    }

    /** {@code outcome}: FORWARDED, UNCONFIRMED (check with the courier, never retried), REFUSED (with the reason) or ERROR. */
    public record ResultRow(UUID shipmentId, String outcome, String message, String provider, String serviceName, String awb,
                            String labelUrl, BigDecimal cost) {
    }

    public List<OptionsRow> options(List<UUID> shipmentIds) {
        List<OptionsRow> rows = new ArrayList<>();
        for (UUID id : distinct(shipmentIds)) {
            try {
                rows.add(new OptionsRow(id, forwarding.options(id), null));
            } catch (ApiException e) {
                rows.add(new OptionsRow(id, null, e.getMessage()));
            } catch (org.springframework.security.access.AccessDeniedException e) {
                rows.add(new OptionsRow(id, null, "You may not forward this shipment."));
            } catch (RuntimeException e) {
                log.warn("Pricing shipment {} for forwarding failed", id, e);
                rows.add(new OptionsRow(id, null, "This shipment could not be priced."));
            }
        }
        return rows;
    }

    public List<ResultRow> forward(List<Item> items) {
        if (items == null || items.isEmpty()) {
            throw new BusinessRuleException("Choose the shipments to forward.");
        }
        if (items.size() > MAX_PER_CALL) {
            throw new BusinessRuleException("At most " + MAX_PER_CALL + " shipments per request.");
        }
        Set<UUID> seen = new LinkedHashSet<>();
        List<ResultRow> rows = new ArrayList<>();
        for (Item i : items) {
            if (i == null || i.shipmentId() == null) {
                continue;
            }
            if (!seen.add(i.shipmentId())) {
                rows.add(new ResultRow(i.shipmentId(), "REFUSED", "Listed twice; forwarded once.", null, null, null, null, null));
                continue;
            }
            try {
                ForwardResult r = forwarding.forward(i.shipmentId(), i.providerCode(), i.serviceCode());
                rows.add(new ResultRow(r.shipmentId(), r.outcome(), r.message(), r.provider(), r.serviceName(), r.awb(), r.labelUrl(), r.cost()));
            } catch (ApiException e) {
                rows.add(new ResultRow(i.shipmentId(), "REFUSED", e.getMessage(), i.providerCode(), null, null, null, null));
            } catch (org.springframework.security.access.AccessDeniedException e) {
                rows.add(new ResultRow(i.shipmentId(), "REFUSED", "You may not forward this shipment.", i.providerCode(), null, null, null, null));
            } catch (RuntimeException e) {
                log.error("Bulk forward of shipment {} failed unexpectedly", i.shipmentId(), e);
                rows.add(new ResultRow(i.shipmentId(), "ERROR", "Something went wrong; open the shipment to check whether it was forwarded.",
                        i.providerCode(), null, null, null, null));
            }
        }
        return rows;
    }

    private static List<UUID> distinct(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessRuleException("Choose the shipments to forward.");
        }
        List<UUID> out = new ArrayList<>(new LinkedHashSet<>(ids.stream().filter(java.util.Objects::nonNull).toList()));
        if (out.size() > MAX_PER_CALL) {
            throw new BusinessRuleException("At most " + MAX_PER_CALL + " shipments per request.");
        }
        return out;
    }
}
