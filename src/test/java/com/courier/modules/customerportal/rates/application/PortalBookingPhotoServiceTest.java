package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customerportal.application.ActingCustomer;
import com.courier.modules.customerportal.application.CurrentCustomerResolver;
import com.courier.modules.customerportal.rates.domain.PortalBooking;
import com.courier.modules.customerportal.rates.domain.PortalBookingPhoto;
import com.courier.modules.customerportal.rates.domain.PortalBookingPhotoFileRepository;
import com.courier.modules.customerportal.rates.domain.PortalBookingPhotoRepository;
import com.courier.modules.customerportal.rates.domain.PortalBookingRepository;
import com.courier.shared.exception.BusinessRuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortalBookingPhotoServiceTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01};
    private static final byte[] PDF = "%PDF-1.4".getBytes();

    private final ActingCustomer acting = new ActingCustomer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private final UUID bookingId = UUID.randomUUID();
    private final CurrentCustomerResolver resolver = mock(CurrentCustomerResolver.class);
    private final PortalBookingRepository bookings = mock(PortalBookingRepository.class);
    private final PortalBookingPhotoRepository photos = mock(PortalBookingPhotoRepository.class);
    private final PortalBookingPhotoFileRepository files = mock(PortalBookingPhotoFileRepository.class);
    private final PortalBookingPhotoService service = new PortalBookingPhotoService(resolver, bookings, photos, files);

    private void bookingIs(PortalBooking.Status status) {
        when(resolver.require()).thenReturn(acting);
        PortalBooking b = PortalBooking.builder().status(status).build();
        b.setId(bookingId);
        when(bookings.findOwned(bookingId, acting.companyId(), acting.customerId())).thenReturn(Optional.of(b));
        when(photos.save(any())).thenAnswer(i -> { PortalBookingPhoto p = i.getArgument(0); p.setId(UUID.randomUUID()); return p; });
    }

    @Test
    @DisplayName("a parcel photo is stored with its type read from the bytes, not from the name")
    void storesPhoto() {
        bookingIs(PortalBooking.Status.BOOKED);

        var view = service.upload(bookingId, "parcel", JPEG, "../../box.exe");

        assertThat(view.contentType()).isEqualTo("image/jpeg");
        assertThat(view.fileName()).isEqualTo("box.exe");
        assertThat(view.kind()).isEqualTo("PARCEL");
        verify(files).save(any());
    }

    @Test
    @DisplayName("something that is not an image is refused, and a PDF only as a document")
    void refusesOtherFiles() {
        bookingIs(PortalBooking.Status.BOOKED);

        assertThatThrownBy(() -> service.upload(bookingId, "PARCEL", "hello".getBytes(), "a.jpg")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.upload(bookingId, "PARCEL", PDF, "a.pdf")).isInstanceOf(BusinessRuleException.class);
        assertThat(service.upload(bookingId, "DOCUMENT", PDF, "invoice.pdf").contentType()).isEqualTo("application/pdf");
    }

    @Test
    @DisplayName("more than 6 parcel photos, or a file over 5 MB, is refused")
    void limits() {
        bookingIs(PortalBooking.Status.BOOKED);
        when(photos.countOfKind(eq(acting.companyId()), eq(bookingId), eq("PARCEL"))).thenReturn(6L);

        assertThatThrownBy(() -> service.upload(bookingId, "PARCEL", JPEG, "a.jpg")).hasMessageContaining("at most 6");
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        big[0] = (byte) 0xFF; big[1] = (byte) 0xD8; big[2] = (byte) 0xFF;
        assertThatThrownBy(() -> service.upload(bookingId, "DOCUMENT", big, "a.jpg")).hasMessageContaining("5 MB");
        verify(files, never()).save(any());
    }

    @Test
    @DisplayName("a failed booking takes no photos")
    void failedBooking() {
        bookingIs(PortalBooking.Status.FAILED);

        assertThatThrownBy(() -> service.upload(bookingId, "PARCEL", JPEG, "a.jpg")).isInstanceOf(BusinessRuleException.class);
    }
}
