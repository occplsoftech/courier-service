package com.courier.modules.customerbilling.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface BookingSummaryEmailLogRepository extends JpaRepository<BookingSummaryEmailLog, UUID> {

    Optional<BookingSummaryEmailLog> findByCompanyIdAndCustomerIdAndReportDate(UUID companyId, UUID customerId,
                                                                               LocalDate reportDate);
}
