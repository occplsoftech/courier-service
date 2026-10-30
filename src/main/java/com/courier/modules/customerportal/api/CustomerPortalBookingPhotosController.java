package com.courier.modules.customerportal.api;

import com.courier.modules.customerportal.rates.application.PortalBookingPhotoService;
import com.courier.modules.customerportal.rates.application.PortalBookingPhotoService.FileView;
import com.courier.modules.customerportal.rates.application.PortalBookingPhotoService.PhotoView;
import com.courier.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Photos of the parcel and documents for the signed-in customer's own bookings. */
@RestController
@RequestMapping("/api/v1/customer-portal/bookings/{bookingId}/photos")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Customer portal")
public class CustomerPortalBookingPhotosController {

    private final PortalBookingPhotoService service;

    @GetMapping
    @Operation(summary = "The booking's photos and documents (no file bytes)")
    public ApiResponse<List<PhotoView>> list(@PathVariable UUID bookingId) {
        return ApiResponse.success(service.list(bookingId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Add a photo (kind PARCEL, up to 6) or a document (kind DOCUMENT, up to 3)",
            description = "JPEG, PNG or WebP, read from the file's own bytes; PDF too for a document. At most 5 MB each.")
    public ApiResponse<PhotoView> upload(@PathVariable UUID bookingId, @RequestParam(defaultValue = "PARCEL") String kind,
                                         @RequestParam("file") MultipartFile file) throws IOException {
        return ApiResponse.success(service.upload(bookingId, kind, file.getBytes(), file.getOriginalFilename()), "Photo added");
    }

    @GetMapping("/{photoId}/file")
    @Operation(summary = "The photo itself")
    public ResponseEntity<byte[]> file(@PathVariable UUID bookingId, @PathVariable UUID photoId) {
        return download(service.file(bookingId, photoId));
    }

    @DeleteMapping("/{photoId}")
    @Operation(summary = "Remove a photo")
    public ApiResponse<Void> delete(@PathVariable UUID bookingId, @PathVariable UUID photoId) {
        service.delete(bookingId, photoId);
        return ApiResponse.success(null, "Photo removed");
    }

    static ResponseEntity<byte[]> download(FileView f) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(f.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .body(f.content());
    }
}
