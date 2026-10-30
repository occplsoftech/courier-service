package com.courier.modules.customerportal.rates.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PortalBookingRepository extends JpaRepository<PortalBooking, UUID> {

    @Query("select b from PortalBooking b where b.companyId = :companyId and b.customerId = :customerId "
            + "and b.idempotencyKey = :key")
    Optional<PortalBooking> findByKey(@Param("companyId") UUID companyId, @Param("customerId") UUID customerId,
                                      @Param("key") String key);

    @Query("select b from PortalBooking b where b.id = :id and b.companyId = :companyId and b.customerId = :customerId")
    Optional<PortalBooking> findOwned(@Param("id") UUID id, @Param("companyId") UUID companyId,
                                      @Param("customerId") UUID customerId);

    @Query("select b from PortalBooking b where b.companyId = :companyId and b.providerCode = :provider and b.providerOrderRef = :ref")
    Optional<PortalBooking> findByProviderRef(@Param("companyId") UUID companyId, @Param("provider") String provider,
                                              @Param("ref") String ref);

    @Query("select count(b) > 0 from PortalBooking b where b.quoteId = :quoteId and b.companyId = :companyId "
            + "and b.status in :statuses")
    boolean existsForQuote(@Param("quoteId") UUID quoteId, @Param("companyId") UUID companyId,
                           @Param("statuses") Collection<PortalBooking.Status> statuses);

    @Query("select count(b) from PortalBooking b where b.companyId = :companyId and b.customerId = :customerId "
            + "and b.status in :statuses")
    long countForCustomer(@Param("companyId") UUID companyId, @Param("customerId") UUID customerId,
                          @Param("statuses") Collection<PortalBooking.Status> statuses);

    @Query("select b from PortalBooking b where b.companyId = :companyId and b.customerId = :customerId "
            + "order by b.createdAt desc")
    List<PortalBooking> findForCustomer(@Param("companyId") UUID companyId, @Param("customerId") UUID customerId,
                                        org.springframework.data.domain.Pageable page);

    /** Delivery-stage part of the history filter; {@code :stages} is never empty (all stages when {@code :anyStage}). */
    String DELIVERY_FILTER = "and (:anyStage = true or b.deliveryStatus in :stages or (:unchecked = true and b.deliveryStatus is null))";

    /**
     * One page of the customer's bookings, newest first. {@code provider} "" means every provider; the booking time is
     * within [{@code from}, {@code to}).
     */
    @Query(value = "select b from PortalBooking b where b.companyId = :companyId and b.customerId = :customerId "
            + "and b.status in :statuses and (:provider = '' or b.providerCode = :provider) "
            + "and b.createdAt >= :from and b.createdAt < :to " + DELIVERY_FILTER + " order by b.createdAt desc",
            countQuery = "select count(b) from PortalBooking b where b.companyId = :companyId "
                    + "and b.customerId = :customerId and b.status in :statuses "
                    + "and (:provider = '' or b.providerCode = :provider) and b.createdAt >= :from and b.createdAt < :to "
                    + DELIVERY_FILTER)
    org.springframework.data.domain.Page<PortalBooking> findPage(@Param("companyId") UUID companyId,
                                                                 @Param("customerId") UUID customerId,
                                                                 @Param("statuses") Collection<PortalBooking.Status> statuses,
                                                                 @Param("provider") String provider,
                                                                 @Param("from") java.time.Instant from,
                                                                 @Param("to") java.time.Instant to,
                                                                 @Param("anyStage") boolean anyStage,
                                                                 @Param("stages") Collection<DeliveryStage> stages,
                                                                 @Param("unchecked") boolean includeUnchecked,
                                                                 org.springframework.data.domain.Pageable page);

    /** The providers this customer has booked with, for the history's courier filter. */
    @Query("select distinct b.providerCode from PortalBooking b where b.companyId = :companyId and b.customerId = :customerId")
    List<String> findProviderCodes(@Param("companyId") UUID companyId, @Param("customerId") UUID customerId);

    // ------------------------------------------------------------------ tracking sync (V135)

    /**
     * Booked shipments due for a tracking sync, every company: the background job runs with no company context. Oldest
     * due first.
     */
    @Query("select b from PortalBooking b where b.status = com.courier.modules.customerportal.rates.domain.PortalBooking.Status.BOOKED "
            + "and b.nextTrackingSyncAt is not null and b.nextTrackingSyncAt <= :now order by b.nextTrackingSyncAt asc")
    List<PortalBooking> findDueForTrackingSyncAcrossCompanies(@Param("now") java.time.Instant now,
                                                              org.springframework.data.domain.Pageable page);

    /** The company's booking with this courier and tracking number (a webhook names only these). */
    @Query("select b from PortalBooking b where b.companyId = :companyId and b.providerCode = :provider and b.awbNumber = :awb")
    List<PortalBooking> findByAwb(@Param("companyId") UUID companyId, @Param("provider") String provider, @Param("awb") String awb);

    /** Saves what the courier said. A bulk update: never bumps the version or touches the booking's other columns. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("update PortalBooking b set b.deliveryStatus = :stage, b.deliveryStatusText = :text, b.deliveryLocation = :location, "
            + "b.deliveryStatusAt = :at, b.trackingSyncedAt = :synced, b.nextTrackingSyncAt = :next "
            + "where b.id = :id and b.companyId = :companyId")
    int recordDelivery(@Param("id") UUID id, @Param("companyId") UUID companyId, @Param("stage") DeliveryStage stage,
                       @Param("text") String text, @Param("location") String location, @Param("at") java.time.Instant at,
                       @Param("synced") java.time.Instant synced, @Param("next") java.time.Instant next);

    /** The courier had nothing new (or could not be asked): only when to ask again. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("update PortalBooking b set b.trackingSyncedAt = :synced, b.nextTrackingSyncAt = :next "
            + "where b.id = :id and b.companyId = :companyId")
    int recordSyncAttempt(@Param("id") UUID id, @Param("companyId") UUID companyId,
                          @Param("synced") java.time.Instant synced, @Param("next") java.time.Instant next);

    /** Asks the background job to sync this booking on its next run (a webhook arrived for it). */
    @org.springframework.data.jpa.repository.Modifying
    @Query("update PortalBooking b set b.nextTrackingSyncAt = :when where b.id = :id and b.companyId = :companyId "
            + "and b.status = com.courier.modules.customerportal.rates.domain.PortalBooking.Status.BOOKED")
    int syncSoon(@Param("id") UUID id, @Param("companyId") UUID companyId, @Param("when") java.time.Instant when);
}
