package com.courier.modules.manifest.application;

import com.courier.modules.shipment.application.event.ShipmentEvent;
import com.courier.shared.company.CompanyContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Closes a manifest ("Received") once its last shipment is in-scanned — after the in-scan commits, so it never blocks it. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManifestReceiptListener {

    private final ManifestService manifestService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(ShipmentEvent.ArrivedOffManifest event) {
        try {
            CompanyContext.runAs(event.companyId(), () -> manifestService.completeIfAllReceived(event.manifestId()));
        } catch (RuntimeException e) {
            log.warn("Could not close manifest {} after shipment {} was received: {}", event.manifestId(), event.shipmentId(), e.getMessage());
        }
    }
}
