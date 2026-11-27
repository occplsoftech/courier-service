package com.courier.modules.shipment.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Proof of delivery for the public tracking page's verified detail view. {@code photoUrl}
 * and {@code signatureUrl} are short-lived presigned S3 links (see {@code
 * FileStoragePort#presignGet}), never the permanent unsigned URL the authenticated POD
 * endpoints return — a link handed to an unauthenticated caller must not work forever.
 * Populated when a human reviewer approved the capture (verification status {@code PASS}), or
 * when the delivery was saved from Delivery / Delivery Updation with no POD Review entry; a
 * shipment whose capture is awaiting review or was rejected gets no POD block at all.
 * {@code receivedBy} is the receiver as recorded on delivery ("Ramesh (Security)"), set only
 * on the Delivery Updation path.
 */
@Schema(name = "PublicTrackPodResponse")
public record PublicTrackPodResponse(
        String photoUrl,
        String signatureUrl,
        String deliveredBy,
        Instant deliveredAt,
        String receivedBy
) {
}
