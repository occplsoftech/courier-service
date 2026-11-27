package com.courier.modules.company.domain;

import com.courier.shared.domain.BaseEntity;
import com.courier.shared.domain.TimeOrderedUuid;
import com.courier.shared.exception.BusinessRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A company — one company of the platform.
 *
 * <p><b>This entity is the company.</b> It extends {@link BaseEntity}, never
 * {@code CompanyOwnedEntity}: filtering the company root by {@code company_id} would be
 * circular. Only {@code SUPER_ADMIN} may read or write it.
 *
 * <p><b>{@code id} and {@code companyId} are two different UUIDs.</b> {@code id} is this
 * row's primary key; {@code companyId} is the discriminator stamped onto every
 * company-owned row in the system and carried in the JWT {@code tid} claim. Keeping them
 * apart means the tenancy key never has to change if this table is ever restructured,
 * and a leaked company id is not automatically a company id.
 *
 * <p>Sub-resources of a company — branches, hubs, service areas, rate cards — belong to
 * this module but are a later phase. Nothing here models them.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "companies",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_companies_company_id", columnNames = "company_id"),
                @UniqueConstraint(name = "uk_companies_code", columnNames = "company_code"),
                @UniqueConstraint(name = "uk_companies_email", columnNames = "email"),
                @UniqueConstraint(name = "uk_companies_gst", columnNames = "gst_number"),
                @UniqueConstraint(name = "uk_companies_pan", columnNames = "pan_number")
        },
        indexes = {
                @Index(name = "idx_companies_status", columnList = "status, is_active"),
                @Index(name = "idx_companies_plan", columnList = "subscription_plan_id"),
                @Index(name = "idx_companies_name", columnList = "company_name")
        })
@SQLRestriction("deleted = false")
public class Company extends BaseEntity {

    public static final String DEFAULT_TIMEZONE = "Asia/Kolkata";
    public static final String DEFAULT_CURRENCY = "INR";
    public static final String DEFAULT_LANGUAGE = "en";
    public static final String DEFAULT_DATE_FORMAT = "dd-MM-yyyy";
    public static final String DEFAULT_TIME_FORMAT = "HH:mm";

    /**
     * The tenancy key. Generated here, never supplied by a caller, and immutable:
     * every company-owned row in the database points at this value, so changing it
     * would orphan the company's entire dataset.
     */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "company_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID companyId;

    /**
     * The company's id on the OCCPL SaaS platform, when it was provisioned from there.
     * Null for companies created inside this app. Never used for tenancy — that is
     * {@link #companyId}.
     */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "external_company_id", columnDefinition = "BINARY(16)")
    private UUID externalCompanyId;

    /** Stable machine key, uppercased on write, immutable after creation. */
    @Column(name = "company_code", nullable = false, updatable = false, length = 50)
    private String companyCode;

    @Column(name = "company_name", nullable = false, length = 150)
    private String companyName;

    /** Registered legal entity name, as it appears on invoices. */
    @Column(name = "legal_name", length = 200)
    private String legalName;

    /** Short name for UI headers; falls back to {@code companyName}. */
    @Column(name = "display_name", length = 100)
    private String displayName;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "subscription_plan_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID subscriptionPlanId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 20)
    private CompanyStatus status;

    @Column(name = "trial_start_date")
    private LocalDate trialStartDate;

    @Column(name = "trial_end_date")
    private LocalDate trialEndDate;

    @Column(name = "subscription_start_date")
    private LocalDate subscriptionStartDate;

    @Column(name = "subscription_end_date")
    private LocalDate subscriptionEndDate;

    /** Primary contact, and the address the first admin account is created against. */
    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "mobile", nullable = false, length = 20)
    private String mobile;

    @Column(name = "alternate_mobile", length = 20)
    private String alternateMobile;

    @Column(name = "website", length = 255)
    private String website;

    @Column(name = "gst_number", length = 15)
    private String gstNumber;

    @Column(name = "pan_number", length = 10)
    private String panNumber;

    @Column(name = "cin_number", length = 21)
    private String cinNumber;

    /** A link to the logo, typed by a super admin on the company form. */
    @Column(name = "logo", length = 500)
    private String logo;

    /** The logo a company admin uploaded (Settings), as a small data: URL like customers' logos; wins over {@link #logo}. */
    @Column(name = "logo_data_url", columnDefinition = "MEDIUMTEXT")
    private String logoDataUrl;

    @Column(name = "favicon", length = 500)
    private String favicon;

    @Column(name = "address_line1", length = 255)
    private String addressLine1;

    @Column(name = "address_line2", length = 255)
    private String addressLine2;

    @Column(name = "country", length = 100)
    private String country;

    @Column(name = "state", length = 100)
    private String state;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Column(name = "timezone", nullable = false, length = 64)
    @Builder.Default
    private String timezone = DEFAULT_TIMEZONE;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = DEFAULT_CURRENCY;

    @Column(name = "language", nullable = false, length = 10)
    @Builder.Default
    private String language = DEFAULT_LANGUAGE;

    @Column(name = "date_format", nullable = false, length = 20)
    @Builder.Default
    private String dateFormat = DEFAULT_DATE_FORMAT;

    @Column(name = "time_format", nullable = false, length = 20)
    @Builder.Default
    private String timeFormat = DEFAULT_TIME_FORMAT;

    /**
     * Denormalised from {@link #status}: true while the company may operate. Kept as a
     * column because it is filtered on constantly and always derived through
     * {@link #syncActiveFlag()} — never set independently, or the two disagree.
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "remarks", length = 500)
    private String remarks;

    // ---------------------------------------------------------------- behaviour

    public static UUID newCompanyId() {
        return TimeOrderedUuid.generate();
    }

    public static String normaliseCode(String code) {
        return code == null ? null : code.trim().toUpperCase();
    }

    public static String normaliseEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    /** Blank tax identifiers are stored as null, so the unique keys ignore them. */
    public static String normaliseTaxId(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toUpperCase();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public String effectiveDisplayName() {
        return displayName == null || displayName.isBlank() ? companyName : displayName;
    }

    /** Normalises every derived and case-sensitive field. Called before each save. */
    public void applyInvariants() {
        this.companyCode = normaliseCode(companyCode);
        this.email = normaliseEmail(email);
        this.gstNumber = normaliseTaxId(gstNumber);
        this.panNumber = normaliseTaxId(panNumber);
        this.cinNumber = normaliseTaxId(cinNumber);
        this.companyName = companyName == null ? null : companyName.trim();
        this.currency = currency == null || currency.isBlank()
                ? DEFAULT_CURRENCY : currency.trim().toUpperCase();
        this.timezone = timezone == null || timezone.isBlank() ? DEFAULT_TIMEZONE : timezone.trim();
        this.language = language == null || language.isBlank() ? DEFAULT_LANGUAGE : language.trim();

        if (trialStartDate != null && trialEndDate != null && trialEndDate.isBefore(trialStartDate)) {
            throw new BusinessRuleException("Trial end date cannot be before the trial start date.");
        }
        if (subscriptionStartDate != null && subscriptionEndDate != null
                && subscriptionEndDate.isBefore(subscriptionStartDate)) {
            throw new BusinessRuleException(
                    "Subscription end date cannot be before the subscription start date.");
        }

        syncActiveFlag();
    }

    /**
     * Moves to a new status, refusing transitions the lifecycle does not allow.
     *
     * @throws BusinessRuleException with {@code INVALID_STATE_TRANSITION} on an illegal move
     */
    public void transitionTo(CompanyStatus target) {
        if (this.status == target) {
            return;
        }
        if (!this.status.canTransitionTo(target)) {
            throw new BusinessRuleException(
                    com.courier.shared.exception.ErrorCode.INVALID_STATE_TRANSITION,
                    "A company cannot move from %s to %s.".formatted(this.status, target));
        }
        this.status = target;
        syncActiveFlag();
    }

    /** True when the trial window has closed. Null dates mean "no trial", never expired. */
    public boolean isTrialElapsed(LocalDate today) {
        return trialEndDate != null && trialEndDate.isBefore(today);
    }

    public boolean isSubscriptionElapsed(LocalDate today) {
        return subscriptionEndDate != null && subscriptionEndDate.isBefore(today);
    }

    private void syncActiveFlag() {
        this.active = status != null && status.isOperational();
    }
}
