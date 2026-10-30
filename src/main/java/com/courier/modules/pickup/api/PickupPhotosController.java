package com.courier.modules.pickup.api;

import com.courier.modules.customerportal.rates.application.PortalBookingPhotoService;
import com.courier.modules.customerportal.rates.application.PortalBookingPhotoService.PhotoView;
import com.courier.modules.pickup.application.PickupService;
import com.courier.modules.pickup.domain.PickupRequest;
import com.courier.shared.api.ApiResponse;
import com.courier.shared.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * The customer's photos of the parcel for a pickup request that came from the customer portal. Reading the pickup goes through
 * {@link PickupService#get}, so whoever may see the pickup (role, assigned partner) may see its photos, and nobody else.
 */
@RestController
@RequestMapping("/api/v1/pickups/{pickupId}/photos")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Pickups")
public class PickupPhotosController {

    private final PickupService pickups;
    private final PortalBookingPhotoService photos;

    @GetMapping
    @Operation(summary = "Parcel photos the customer attached to this pickup's portal booking")
    public ApiResponse<List<PhotoView>> list(@PathVariable UUID pickupId) {
        PickupRequest p = pickups.get(pickupId);
        return ApiResponse.success(photos.forPickup(SecurityUtils.requireCurrentUser().companyId(), p.getPickupNo()));
    }

    @GetMapping("/{photoId}/file")
    @Operation(summary = "One parcel photo")
    public ResponseEntity<byte[]> file(@PathVariable UUID pickupId, @PathVariable UUID photoId) {
        PickupRequest p = pickups.get(pickupId);
        var f = photos.pickupFile(SecurityUtils.requireCurrentUser().companyId(), p.getPickupNo(), photoId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(f.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .body(f.content());
    }
}
