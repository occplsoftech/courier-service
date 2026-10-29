package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customerportal.application.ActingCustomer;
import com.courier.modules.customerportal.application.CurrentCustomerResolver;
import com.courier.modules.customerportal.rates.domain.PortalBooking;
import com.courier.modules.customerportal.rates.domain.PortalBookingPhoto;
import com.courier.modules.customerportal.rates.domain.PortalBookingPhotoFile;
import com.courier.modules.customerportal.rates.domain.PortalBookingPhotoFileRepository;
import com.courier.modules.customerportal.rates.domain.PortalBookingPhotoRepository;
import com.courier.modules.customerportal.rates.domain.PortalBookingRepository;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Photos of the parcel and supporting documents for a portal booking. The customer adds them while booking (right after it is
 * made) or later; OCCPL's pickup staff read the parcel photos on the pickup request.
 */
@Service
@RequiredArgsConstructor
public class PortalBookingPhotoService {

    static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_PARCEL = 6;
    static final int MAX_DOCUMENT = 3;
    /** A booking still on its way: photos can be added or removed until it has failed. */
    private static final Set<PortalBooking.Status> OPEN = EnumSet.of(PortalBooking.Status.BOOKED, PortalBooking.Status.OUTCOME_UNKNOWN);

    private final CurrentCustomerResolver resolver;
    private final PortalBookingRepository bookings;
    private final PortalBookingPhotoRepository photos;
    private final PortalBookingPhotoFileRepository files;

    public record PhotoView(UUID id, String kind, String fileName, String contentType, long sizeBytes, Instant uploadedAt) {
    }

    public record FileView(byte[] content, String contentType, String fileName) {
    }

    // ------------------------------------------------------------------ the customer

    @Transactional
    public PhotoView upload(UUID bookingId, String kind, byte[] bytes, String originalName) {
        ActingCustomer acting = resolver.require();
        PortalBooking booking = owned(acting, bookingId);
        if (!OPEN.contains(booking.getStatus())) {
            throw new BusinessRuleException("Photos can be added only to a booking that was made.");
        }
        String k = kind == null ? PortalBookingPhoto.PARCEL : kind.trim().toUpperCase();
        if (!PortalBookingPhoto.PARCEL.equals(k) && !PortalBookingPhoto.DOCUMENT.equals(k)) {
            throw new BusinessRuleException("Kind must be PARCEL or DOCUMENT.");
        }
        String type = contentTypeOf(bytes, PortalBookingPhoto.DOCUMENT.equals(k));
        int max = PortalBookingPhoto.PARCEL.equals(k) ? MAX_PARCEL : MAX_DOCUMENT;
        if (photos.countOfKind(acting.companyId(), bookingId, k) >= max) {
            throw new BusinessRuleException("A booking can have at most " + max + " " + (max == MAX_PARCEL ? "parcel photos" : "documents") + ".");
        }
        PortalBookingPhoto p = new PortalBookingPhoto();
        p.setCustomerId(acting.customerId());
        p.setBookingId(bookingId);
        p.setKind(k);
        p.setFileName(cleanName(originalName, type));
        p.setContentType(type);
        p.setSizeBytes(bytes.length);
        p.setSha256(sha256(bytes));
        PortalBookingPhoto saved = photos.save(p);
        files.save(new PortalBookingPhotoFile(saved.getId(), bytes));
        return view(saved);
    }

    @Transactional(readOnly = true)
    public List<PhotoView> list(UUID bookingId) {
        ActingCustomer acting = resolver.require();
        owned(acting, bookingId);
        return photos.findForBooking(acting.companyId(), bookingId).stream().map(PortalBookingPhotoService::view).toList();
    }

    @Transactional(readOnly = true)
    public FileView file(UUID bookingId, UUID photoId) {
        ActingCustomer acting = resolver.require();
        owned(acting, bookingId);
        return read(acting.companyId(), bookingId, photoId);
    }

    @Transactional
    public void delete(UUID bookingId, UUID photoId) {
        ActingCustomer acting = resolver.require();
        PortalBooking booking = owned(acting, bookingId);
        if (!OPEN.contains(booking.getStatus())) {
            throw new BusinessRuleException("Photos can be removed only from a booking that was made.");
        }
        PortalBookingPhoto p = photos.findOne(photoId, acting.companyId(), bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Photo", photoId));
        p.softDelete(acting.userId());
        photos.save(p);
    }

    // ------------------------------------------------------------------ OCCPL's pickup staff

    /** The parcel photos of the booking behind a pickup request ({@code pickupNo} is the booking's provider reference). The caller has been allowed to read the pickup. */
    @Transactional(readOnly = true)
    public List<PhotoView> forPickup(UUID companyId, String pickupNo) {
        return bookingOfPickup(companyId, pickupNo)
                .map(b -> photos.findForBooking(companyId, b.getId()).stream()
                        .filter(p -> PortalBookingPhoto.PARCEL.equals(p.getKind())).map(PortalBookingPhotoService::view).toList())
                .orElse(List.of());
    }

    @Transactional(readOnly = true)
    public FileView pickupFile(UUID companyId, String pickupNo, UUID photoId) {
        PortalBooking b = bookingOfPickup(companyId, pickupNo).orElseThrow(() -> new ResourceNotFoundException("Photo", photoId));
        return read(companyId, b.getId(), photoId);
    }

    // ------------------------------------------------------------------ internals

    private Optional<PortalBooking> bookingOfPickup(UUID companyId, String pickupNo) {
        return pickupNo == null ? Optional.empty() : bookings.findByProviderRef(companyId, "OCCPL_NETWORK", pickupNo);
    }

    private FileView read(UUID companyId, UUID bookingId, UUID photoId) {
        PortalBookingPhoto p = photos.findOne(photoId, companyId, bookingId).orElseThrow(() -> new ResourceNotFoundException("Photo", photoId));
        PortalBookingPhotoFile f = files.findById(p.getId()).orElseThrow(() -> new ResourceNotFoundException("Photo", photoId));
        return new FileView(f.getContent(), p.getContentType(), p.getFileName());
    }

    private PortalBooking owned(ActingCustomer acting, UUID bookingId) {
        return bookings.findOwned(bookingId, acting.companyId(), acting.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));
    }

    private static PhotoView view(PortalBookingPhoto p) {
        return new PhotoView(p.getId(), p.getKind(), p.getFileName(), p.getContentType(), p.getSizeBytes(), p.getCreatedAt());
    }

    /** JPEG, PNG or WebP (and PDF for a document), read from the first bytes and never from the declared type; at most 5 MB. */
    static String contentTypeOf(byte[] b, boolean pdfAllowed) {
        if (b == null || b.length == 0) {
            throw new BusinessRuleException("Choose a file to upload.");
        }
        if (b.length > MAX_BYTES) {
            throw new BusinessRuleException("The file is larger than 5 MB. Choose a smaller photo.");
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        if (pdfAllowed && b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') {
            return "application/pdf";
        }
        throw new BusinessRuleException(pdfAllowed ? "Upload a JPEG, PNG, WebP or PDF file." : "Upload a JPEG, PNG or WebP photo.");
    }

    static String cleanName(String original, String contentType) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"<>]", "").trim();
        if (name.isEmpty()) {
            name = "photo" + switch (contentType) {
                case "application/pdf" -> ".pdf";
                case "image/png" -> ".png";
                case "image/webp" -> ".webp";
                default -> ".jpg";
            };
        }
        return name.length() <= 150 ? name : name.substring(name.length() - 150);
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
