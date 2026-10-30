package com.courier.modules.shipment.application.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Turns the address of a file we stored into a link a browser can open. The bucket is private, so the stored address answers
 * "Access Denied"; every response that shows an uploaded file (proof of delivery, shipment photos, e-way bill and shipment
 * documents, ticket attachments, top-up proofs) passes its stored address through here. A link works for an hour; reloading the
 * page gets a new one. An address that is not in our bucket (a URL someone pasted) comes back unchanged, and so does everything
 * when no storage is configured.
 */
@Component
@RequiredArgsConstructor
public class SignedLinks {

    static final Duration TTL = Duration.ofHours(1);

    private final FileStoragePort storage;

    public String of(String storedUrl) {
        return storedUrl == null || storedUrl.isBlank() ? storedUrl : storage.presignGet(storedUrl, TTL);
    }
}
