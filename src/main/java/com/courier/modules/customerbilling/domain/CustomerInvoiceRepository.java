package com.courier.modules.customerbilling.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerInvoiceRepository extends JpaRepository<CustomerInvoice, UUID> {

    /** A customer's approved invoices, newest first: what the customer portal shows (drafts awaiting approval are not). */
    @Query("select i from CustomerInvoice i where i.companyId = :companyId and i.customerId = :customerId "
            + "and i.approvalStatus = com.courier.modules.customerbilling.domain.ApprovalStatus.APPROVED "
            + "order by i.invoiceDate desc, i.invoiceNumber desc")
    List<CustomerInvoice> findApprovedForCustomer(@Param("companyId") UUID companyId, @Param("customerId") UUID customerId);

    @Query("select i from CustomerInvoice i where i.id = :id and i.companyId = :companyId")
    Optional<CustomerInvoice> findByIdWithinCompany(@Param("id") UUID id, @Param("companyId") UUID companyId);

    @Query("select i from CustomerInvoice i where i.companyId = :companyId"
            + " and (:customerId is null or i.customerId = :customerId)"
            + " and (:status is null or i.status = :status)"
            + " and (:approval is null or i.approvalStatus = :approval)"
            + " and (:from is null or i.invoiceDate >= :from) and (:to is null or i.invoiceDate <= :to)"
            + " and (:search is null or lower(i.invoiceNumber) like :search)"
            + " order by i.invoiceDate desc, i.invoiceNumber desc")
    List<CustomerInvoice> search(@Param("companyId") UUID companyId, @Param("customerId") UUID customerId,
                                 @Param("status") InvoiceStatus status,
                                 @Param("approval") ApprovalStatus approval, @Param("from") LocalDate from,
                                 @Param("to") LocalDate to, @Param("search") String search);

    /** An AUTO invoice already issued for this customer's cycle ending {@code periodTo} (any status but cancelled). */
    @Query("select count(i) > 0 from CustomerInvoice i where i.companyId = :companyId and i.customerId = :customerId"
            + " and i.source = com.courier.modules.customerbilling.domain.InvoiceSource.AUTO"
            + " and i.periodTo = :periodTo and i.status <> com.courier.modules.customerbilling.domain.InvoiceStatus.CANCELLED")
    boolean autoInvoiceExists(@Param("companyId") UUID companyId, @Param("customerId") UUID customerId,
                              @Param("periodTo") LocalDate periodTo);

    /** Counts every row ever issued by the company — cancelled and deleted included — to keep numbers unique. */
    @Query(value = "SELECT COUNT(*) FROM customer_invoices WHERE company_id = :companyId", nativeQuery = true)
    long countEverIssued(@Param("companyId") byte[] companyId);

    @Query(value = "SELECT COUNT(*) FROM customer_invoices WHERE company_id = :companyId AND invoice_number = :number",
            nativeQuery = true)
    long countNumber(@Param("companyId") byte[] companyId, @Param("number") String number);
}
