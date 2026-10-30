package com.courier.modules.customerportal.rates.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PortalBookingPhotoRepository extends JpaRepository<PortalBookingPhoto, UUID> {

    @Query("select p from PortalBookingPhoto p where p.companyId = :companyId and p.bookingId = :bookingId order by p.createdAt")
    List<PortalBookingPhoto> findForBooking(@Param("companyId") UUID companyId, @Param("bookingId") UUID bookingId);

    @Query("select p from PortalBookingPhoto p where p.id = :id and p.companyId = :companyId and p.bookingId = :bookingId")
    Optional<PortalBookingPhoto> findOne(@Param("id") UUID id, @Param("companyId") UUID companyId, @Param("bookingId") UUID bookingId);

    @Query("select count(p) from PortalBookingPhoto p where p.companyId = :companyId and p.bookingId = :bookingId and p.kind = :kind")
    long countOfKind(@Param("companyId") UUID companyId, @Param("bookingId") UUID bookingId, @Param("kind") String kind);
}
