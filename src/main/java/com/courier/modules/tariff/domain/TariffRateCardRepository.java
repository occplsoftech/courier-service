package com.courier.modules.tariff.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Rate cards, within a company. */
public interface TariffRateCardRepository extends JpaRepository<TariffRateCard, UUID> {

    @Query("select c from TariffRateCard c where c.id = :id and c.companyId = :companyId")
    Optional<TariffRateCard> findByIdWithinCompany(@Param("id") UUID id, @Param("companyId") UUID companyId);

    /** The company's own cards; a business customer's card is listed with that customer instead. */
    @Query("select c from TariffRateCard c where c.companyId = :companyId and c.customerId is null"
            + " and (:fromZoneId is null or c.fromZoneId = :fromZoneId)"
            + " and (:status is null or c.status = :status)"
            + " and (:search is null or lower(c.code) like :search or lower(c.name) like :search)"
            + " order by c.code, c.effectiveFrom desc")
    List<TariffRateCard> search(@Param("companyId") UUID companyId, @Param("fromZoneId") UUID fromZoneId,
                                @Param("status") TariffStatus status, @Param("search") String search);

    /** The Rate Cards list: {@code owner} ALL, COMPANY (the company's own) or CUSTOMER (business customers' cards). */
    @Query("select c from TariffRateCard c where c.companyId = :companyId"
            + " and (:owner = 'ALL' or (:owner = 'COMPANY' and c.customerId is null) or (:owner = 'CUSTOMER' and c.customerId is not null))"
            + " and (:fromZoneId is null or c.fromZoneId = :fromZoneId)"
            + " and (:status is null or c.status = :status)"
            + " and (:search is null or lower(c.code) like :search or lower(c.name) like :search)"
            + " order by c.code, c.effectiveFrom desc")
    List<TariffRateCard> searchByOwner(@Param("companyId") UUID companyId, @Param("fromZoneId") UUID fromZoneId,
                                       @Param("status") TariffStatus status, @Param("search") String search,
                                       @Param("owner") String owner);

    /** The company's cards of a from zone (customer cards excluded). */
    @Query("select c from TariffRateCard c where c.companyId = :companyId and c.customerId is null"
            + " and c.fromZoneId = :fromZoneId and c.status = :status")
    List<TariffRateCard> findByFromZone(@Param("companyId") UUID companyId, @Param("fromZoneId") UUID fromZoneId,
                                        @Param("status") TariffStatus status);

    /** One business customer's cards, newest start first; {@code fromZoneId} and {@code status} null = any. */
    @Query("select c from TariffRateCard c where c.companyId = :companyId and c.customerId = :customerId"
            + " and (:fromZoneId is null or c.fromZoneId = :fromZoneId)"
            + " and (:status is null or c.status = :status)"
            + " order by c.effectiveFrom desc")
    List<TariffRateCard> findByCustomer(@Param("companyId") UUID companyId, @Param("customerId") UUID customerId,
                                        @Param("fromZoneId") UUID fromZoneId, @Param("status") TariffStatus status);

    @Query("select count(c) > 0 from TariffRateCard c where c.companyId = :companyId"
            + " and c.code = :code and (:excludeId is null or c.id <> :excludeId)")
    boolean existsByCode(@Param("companyId") UUID companyId, @Param("code") String code, @Param("excludeId") UUID excludeId);

    @Query("select count(c) > 0 from TariffRateCard c where c.companyId = :companyId and c.fromZoneId = :zoneId")
    boolean existsByFromZone(@Param("companyId") UUID companyId, @Param("zoneId") UUID zoneId);
}
