package com.courier.modules.customerportal.integrations.application;

import com.courier.modules.customerportal.integrations.domain.CourierIntegration;
import com.courier.modules.customerportal.integrations.domain.CourierIntegrationRepository;
import com.courier.modules.customerportal.rates.application.TrackingSyncService;
import com.courier.modules.customerportal.rates.infrastructure.PorterAdapter;
import com.courier.modules.customerportal.rates.infrastructure.ProviderTimes;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Status pushes from couriers. The address carries a per-company, per-courier secret
 * ({@code courier_integrations.webhook_token}), which is the only thing that says whose shipment it is; the payload is
 * then read for the tracking number (AWB) and the status.
 *
 * <p>Couriers send different shapes, so reading is lenient: the usual Shiprocket fields ({@code awb},
 * {@code current_status}, {@code current_timestamp}, {@code scans[].location}) and Delhivery's scan push
 * ({@code Shipment.AWB}, {@code Shipment.Status.Status / StatusLocation / StatusDateTime}) are understood, a list of such
 * objects too, and Porter's order push ({@code order_id} = the CRN stored as the tracking number, {@code status} such as
 * {@code order_accepted} or {@code order_end_job}, {@code order_details.event_ts}). Whatever is pushed is confirmed with the courier's own API by the tracking sync shortly after
 * ({@link TrackingSyncService#recordPushed}), so a garbled push does not stick.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CourierTrackingWebhookService {

    private static final List<String> AWB_KEYS = List.of("awb", "AWB", "awb_code", "awb_number", "waybill", "Waybill", "wbn",
            "tracking_number", "trackingNumber", "order_id");
    private static final List<String> STATUS_KEYS = List.of("current_status", "shipment_status", "status", "Status");
    private static final List<String> LOCATION_KEYS = List.of("location", "current_location", "StatusLocation", "ScannedLocation");
    private static final List<String> TIME_KEYS = List.of("current_timestamp", "StatusDateTime", "timestamp", "date", "ScanDateTime");
    private static final List<String> WRAPPERS = List.of("Shipment", "shipment", "data", "ShipmentData");

    private final CourierIntegrationRepository integrations;
    private final TrackingSyncService sync;

    /** One parsed update. */
    public record Update(String awb, String status, String location, Instant at) {
    }

    /** The integration this secret belongs to, if any. */
    public Optional<CourierIntegration> integration(String token) {
        if (token == null || token.length() < 32 || token.length() > 64 || !token.matches("[0-9a-f]+")) {
            return Optional.empty();
        }
        return integrations.findByWebhookTokenAcrossCompanies(token);
    }

    /** Applies a push to the integration's company. Returns how many bookings it matched (0 is not an error). */
    public int receive(CourierIntegration integration, JsonNode body, Instant now) {
        int matched = 0;
        for (Update u : read(body)) {
            matched += sync.recordPushed(integration.getCompanyId(), integration.getProviderCode(), u.awb(), u.status(),
                    u.location(), u.at(), now);
        }
        log.info("{} webhook for company {}: {} booking(s) matched", integration.getProviderCode(), integration.getCompanyId(), matched);
        return matched;
    }

    /** Every update in a payload: one object, a list of them, or an object wrapping either. */
    static List<Update> read(JsonNode body) {
        List<Update> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        if (body.isArray()) {
            body.forEach(n -> out.addAll(read(n)));
            return out;
        }
        if (!body.isObject()) {
            return out;
        }
        String awb = text(body, AWB_KEYS);
        if (awb == null) {
            for (String w : WRAPPERS) {
                if (body.has(w)) {
                    return read(body.get(w));
                }
            }
            return out;
        }
        JsonNode statusNode = body.get("Status");
        String status;
        String location;
        String at;
        if (statusNode != null && statusNode.isObject()) { // Delhivery: { "Status": { "Status": ..., "StatusLocation": ... } }
            status = text(statusNode, STATUS_KEYS);
            location = text(statusNode, LOCATION_KEYS);
            at = text(statusNode, TIME_KEYS);
        } else if (body.has("order_id") && String.valueOf(text(body, STATUS_KEYS)).startsWith("order_")) { // Porter
            status = PorterAdapter.describe(text(body, STATUS_KEYS));
            location = null;
            JsonNode ts = body.path("order_details").path("event_ts");
            out.add(new Update(awb, status, null, ts.isNumber() && ts.asLong() > 0 ? Instant.ofEpochSecond(ts.asLong()) : null));
            return out;
        } else {
            status = text(body, STATUS_KEYS);
            location = text(body, LOCATION_KEYS);
            at = text(body, TIME_KEYS);
            JsonNode scans = body.get("scans");
            if (location == null && scans != null && scans.isArray() && !scans.isEmpty()) {
                location = text(scans.get(scans.size() - 1), LOCATION_KEYS);
            }
        }
        out.add(new Update(awb, status, location, ProviderTimes.parse(at)));
        return out;
    }

    private static String text(JsonNode node, List<String> keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && v.isValueNode() && !v.asText().isBlank()) {
                return v.asText().trim();
            }
        }
        return null;
    }
}
