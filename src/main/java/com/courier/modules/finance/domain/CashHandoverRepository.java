package com.courier.modules.finance.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CashHandoverRepository extends JpaRepository<CashHandover, UUID> {

    @Query("select h from CashHandover h where h.id = :id and h.companyId = :companyId")
    Optional<CashHandover> findByIdWithinCompany(@Param("id") UUID id, @Param("companyId") UUID companyId);

    /** Every handover (voided ones included) of a branch on the days given, newest first. */
    @Query("select h from CashHandover h where h.companyId = :companyId and h.branchId = :branchId "
            + "and h.handoverDate between :from and :to order by h.handoverDate desc, h.receivedAt desc")
    List<CashHandover> findForBranchBetween(@Param("companyId") UUID companyId, @Param("branchId") UUID branchId,
                                            @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** The day outstanding cash starts counting for this company (V191): its settings row, else the day the company was
     *  created. A DATE comes back as {@code java.sql.Date} or {@code LocalDate} depending on the driver, so the caller
     *  converts. */
    @Query(value = "select coalesce((select s.tracking_from from cash_handover_settings s where s.company_id = :companyId), "
            + "(select date(c.created_at) from companies c where c.company_id = :companyId))", nativeQuery = true)
    List<Object> findTrackingFrom(@Param("companyId") byte[] companyId);
}
