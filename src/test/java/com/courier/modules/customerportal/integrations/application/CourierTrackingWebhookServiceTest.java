package com.courier.modules.customerportal.integrations.application;

import com.courier.modules.customerportal.integrations.domain.CourierIntegration;
import com.courier.modules.customerportal.integrations.domain.CourierIntegrationRepository;
import com.courier.modules.customerportal.rates.application.TrackingSyncService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CourierTrackingWebhookServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private final CourierIntegrationRepository repository = mock(CourierIntegrationRepository.class);
    private final TrackingSyncService sync = mock(TrackingSyncService.class);
    private final CourierTrackingWebhookService service = new CourierTrackingWebhookService(repository, sync);

    @Test
    @DisplayName("reads Shiprocket's push: awb, current status, time (India) and the latest scan's place")
    void shiprocket() throws Exception {
        var updates = CourierTrackingWebhookService.read(json.readTree("""
                {"awb":"19041424751540","current_status":"IN TRANSIT","current_timestamp":"2026-10-23 11:15:00",
                 "scans":[{"location":"Pune Hub"},{"location":"Mumbai Hub"}]}"""));

        assertThat(updates).containsExactly(new CourierTrackingWebhookService.Update("19041424751540", "IN TRANSIT",
                "Mumbai Hub", Instant.parse("2026-10-23T05:45:00Z")));
    }

    @Test
    @DisplayName("reads Delhivery's scan push, alone or as a list")
    void delhivery() throws Exception {
        String one = """
                {"Shipment":{"AWB":"1490812345","Status":{"Status":"Dispatched","StatusLocation":"Delhi_Hub (Delhi)",
                 "StatusDateTime":"2026-10-23T10:00:00+05:30","StatusType":"UD"}}}""";

        assertThat(CourierTrackingWebhookService.read(json.readTree(one))).containsExactly(new CourierTrackingWebhookService.Update(
                "1490812345", "Dispatched", "Delhi_Hub (Delhi)", Instant.parse("2026-10-23T04:30:00Z")));
        assertThat(CourierTrackingWebhookService.read(json.readTree("[" + one + "," + one + "]"))).hasSize(2);
        assertThat(CourierTrackingWebhookService.read(json.readTree("{\"hello\":1}"))).isEmpty();
    }

    @Test
    @DisplayName("reads Porter's order push: the CRN, its status in words and the event time")
    void porter() throws Exception {
        var updates = CourierTrackingWebhookService.read(json.readTree("""
                {"status":"order_accepted","order_id":"CRN123456789","order_details":{"event_ts":1664457558,
                 "partner_location":{"lat":12.93468735,"long":77.6095961},
                 "driver_details":{"driver_name":"Test Partnerr","vehicle_number":"HY-45-YU-5677","mobile":"6100001111"}}}"""));

        assertThat(updates).containsExactly(new CourierTrackingWebhookService.Update("CRN123456789",
                "Rider assigned · pickup scheduled", null, Instant.ofEpochSecond(1664457558)));
        assertThat(CourierTrackingWebhookService.read(json.readTree(
                "{\"status\":\"order_end_job\",\"order_id\":\"CRN1\",\"order_details\":{\"event_ts\":1664458152,\"actual_trip_fare\":77}}")))
                .extracting(CourierTrackingWebhookService.Update::status).containsExactly("Delivered");
    }

    @Test
    @DisplayName("only a well-formed, known secret names an integration; its updates go to that company and courier")
    void secretDecides() throws Exception {
        assertThat(service.integration("nope")).isEmpty();
        assertThat(service.integration("../../etc")).isEmpty();
        verify(repository, never()).findByWebhookTokenAcrossCompanies(anyString());

        String token = "ab".repeat(24);
        CourierIntegration row = new CourierIntegration();
        row.setProviderCode("SHIPROCKET");
        row.setCompanyId(UUID.randomUUID());
        when(repository.findByWebhookTokenAcrossCompanies(token)).thenReturn(Optional.of(row));
        when(sync.recordPushed(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        assertThat(service.integration(token)).contains(row);
        Instant now = Instant.now();
        assertThat(service.receive(row, json.readTree("{\"awb\":\"A1\",\"current_status\":\"Delivered\"}"), now)).isEqualTo(1);
        verify(sync).recordPushed(row.getCompanyId(), "SHIPROCKET", "A1", "Delivered", null, null, now);
        assertThat(service.receive(row, null, now)).isZero();
        assertThat(List.of()).isEmpty();
    }
}
