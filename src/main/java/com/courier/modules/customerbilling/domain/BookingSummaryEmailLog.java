package com.courier.modules.customerbilling.domain;

import com.courier.shared.domain.CompanyOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One daily booking summary e-mail to one business customer for one day. The unique key
 * {@code (company_id, customer_id, report_date)} is the duplicate guard: the job ticks at 23:30, 23:40 and 23:50 so a
 * failed send retries, and this row is what makes a later tick (or a restart) see "already sent".
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(
        name = "booking_summary_email_log",
        uniqueConstraints = @UniqueConstraint(name = "uk_booking_summary_email_log",
                columnNames = {"company_id", "customer_id", "report_date"}),
        indexes = @Index(name = "ix_booking_summary_email_log_day", columnList = "company_id, report_date"))
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class BookingSummaryEmailLog extends CompanyOwnedEntity {

    public static final String PENDING = "PENDING";
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";

    @Column(name = "customer_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID customerId;

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    @Column(name = "recipient", nullable = false, length = 255)
    private String recipient;

    @Column(name = "booking_count", nullable = false)
    private int bookingCount;

    @Column(name = "status", nullable = false, length = 20)
    private String status = PENDING;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    public boolean isSent() {
        return SENT.equals(status);
    }
}
