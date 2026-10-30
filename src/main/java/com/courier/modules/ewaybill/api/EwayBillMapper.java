package com.courier.modules.ewaybill.api;

import com.courier.modules.ewaybill.api.dto.CreateEwayBillRequest;
import com.courier.modules.ewaybill.api.dto.EwayBillResponse;
import com.courier.modules.ewaybill.api.dto.UpdateEwayBillRequest;
import com.courier.modules.ewaybill.application.command.CreateEwayBillCommand;
import com.courier.modules.ewaybill.application.command.EwayBillDataCommand;
import com.courier.modules.ewaybill.application.command.UpdateEwayBillCommand;
import com.courier.modules.ewaybill.domain.EwayBill;
import org.springframework.stereotype.Component;

/** Wire contract <-> application/domain types for E-Way Bill Management. */
@Component
@lombok.RequiredArgsConstructor
public class EwayBillMapper {

    private final com.courier.modules.shipment.application.storage.SignedLinks links;

    public CreateEwayBillCommand toCommand(CreateEwayBillRequest r) {
        return new CreateEwayBillCommand(r.shipmentId(), new EwayBillDataCommand(
                r.ewayBillNumber(), r.invoiceNumber(), r.invoiceDate(), r.invoiceValue(),
                r.documentType(), r.documentNumber(), r.documentDate(), r.transporterId(),
                r.vehicleNumber(), r.distance(), r.validFrom(), r.validUntil(),
                r.documentUrl(), r.remarks(), r.consignorGstin(), r.consigneeGstin()));
    }

    public UpdateEwayBillCommand toCommand(UpdateEwayBillRequest r) {
        return new UpdateEwayBillCommand(r.version(), new EwayBillDataCommand(
                r.ewayBillNumber(), r.invoiceNumber(), r.invoiceDate(), r.invoiceValue(),
                r.documentType(), r.documentNumber(), r.documentDate(), r.transporterId(),
                r.vehicleNumber(), r.distance(), r.validFrom(), r.validUntil(),
                r.documentUrl(), r.remarks(), r.consignorGstin(), r.consigneeGstin()));
    }

    public EwayBillResponse toResponse(EwayBill b) {
        return new EwayBillResponse(
                b.getId(), b.getShipmentId(), b.getEwayBillNumber(),
                b.getInvoiceNumber(), b.getInvoiceDate(), b.getInvoiceValue(),
                b.getDocumentType() == null ? null : b.getDocumentType().name(),
                b.getDocumentNumber(), b.getDocumentDate(),
                b.getConsignorGstin(), b.getConsigneeGstin(),
                b.getTransporterId(), b.getVehicleNumber(), b.getTransportMode(), b.getDistance(),
                b.getValidFrom(), b.getValidUntil(),
                b.getStatus().name(),
                b.getProviderName(), b.getProviderReference(),
                b.getPartAGeneratedAt(), b.getPartBGeneratedAt(),
                b.getLastError(), b.getRetryCount(),
                links.of(b.getDocumentUrl()), b.getRemarks(),
                b.getCreatedBy(), b.getCreatedAt(), b.getUpdatedBy(), b.getUpdatedAt(), b.getVersion());
    }
}
