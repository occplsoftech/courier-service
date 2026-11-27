package com.courier.modules.manifest.domain;

/**
 * A manifest's own lifecycle — distinct from, and much shorter than, the shipment status
 * graph it groups: {@code CREATED} (shipments are being scanned onto it) -&gt;
 * {@code DISPATCHED} (vehicle and driver assigned, gone from the branch) -&gt;
 * {@code COMPLETED} (every shipment on it has been in-scanned off the road — set by
 * {@code ManifestService.completeIfAllReceived}, shown as "Received").
 */
public enum ManifestStatus {
    CREATED,
    DISPATCHED,
    COMPLETED
}
