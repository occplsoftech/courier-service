package com.courier.modules.master.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The pincode master sheet, verbatim (V176). */
public interface PincodeMasterSheetRepository extends JpaRepository<PincodeMasterSheetRow, Integer> {

    /** Sheet order; {@code q} (lower-case, wrapped in %) matches pincode, city or state; region and zone exactly. */
    @Query("select r from PincodeMasterSheetRow r where (:q is null or lower(r.pincode) like :q or lower(r.city) like :q"
            + " or lower(r.state) like :q) and (:region is null or r.region = :region) and (:zone is null or r.zone = :zone)"
            + " order by r.rowNo")
    Page<PincodeMasterSheetRow> search(@Param("q") String q, @Param("region") String region, @Param("zone") String zone,
                                       Pageable pageable);
}
