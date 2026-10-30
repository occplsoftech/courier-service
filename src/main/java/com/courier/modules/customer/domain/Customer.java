package com.courier.modules.customer.domain;

import com.courier.shared.domain.CompanyOwnedEntity;
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
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.SQLRestriction;

/**
 * Reusable master data: a person or business a company ships for or delivers to,
 * independent of any particular {@code ShipmentOrder} (see {@code MEMORY/modules/shipment.md}
 * for why the two never join at read or write time).
 *
 * <p>Company-owned. {@code customerCode} is unique within the company (including against
 * soft-deleted rows — a shipment may quote it) and immutable once assigned; {@code mobile}
 * is unique within the company too, but is <b>not</b> reserved past a soft delete, since a
 * deleted customer's number is not a code anything else refers to and re-registering the
 * same person later must not be blocked by their own removed record.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "customers",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_customers_company_code",
                        columnNames = {"company_id", "customer_code"})
        },
        indexes = {
                @Index(name = "idx_customers_company_status", columnList = "company_id, status"),
                @Index(name = "idx_customers_company_mobile", columnList = "company_id, mobile"),
                @Index(name = "idx_customers_company_type", columnList = "company_id, customer_type")
        })
// Repeated deliberately: Hibernate does not inherit @Filter from a @MappedSuperclass.
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class Customer extends CompanyOwnedEntity {

    @Column(name = "customer_code", nullable = false, updatable = false, length = 50)
    private String customerCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "customer_type", nullable = false, length = 20)
    @Builder.Default
    private CustomerType customerType = CustomerType.INDIVIDUAL;

    @Column(name = "company_name", length = 150)
    private String companyName;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "middle_name", length = 100)
    private String middleName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "contact_person", length = 150)
    private String contactPerson;

    @Column(name = "mobile", nullable = false, length = 20)
    private String mobile;

    @Column(name = "alternate_mobile", length = 20)
    private String alternateMobile;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "gst_number", length = 15)
    private String gstNumber;

    @Column(name = "pan_number", length = 10)
    private String panNumber;

    /** The customer that registered this one (from the customer portal); null for customers created by company staff. */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.BINARY)
    @Column(name = "parent_customer_id", columnDefinition = "BINARY(16)", updatable = false)
    private java.util.UUID parentCustomerId;

    @Column(name = "website", length = 255)
    private String website;

    /** The brand logo as a {@code data:image/...;base64,...} URL; set only by {@code CustomerLogoService}, which checks it. */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR)
    @Column(name = "logo_data_url", columnDefinition = "MEDIUMTEXT")
    private String logoDataUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_payment_mode", nullable = false, length = 10)
    @Builder.Default
    private CustomerPaymentMode defaultPaymentMode = CustomerPaymentMode.PAID;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_frequency", nullable = false, length = 15)
    @Builder.Default
    private BillingFrequency billingFrequency = BillingFrequency.MONTHLY;

    /** True: this customer's own rate card (pricing zones) prices their bookings. False: the company's rates apply. */
    @Column(name = "use_business_rates", nullable = false)
    @Builder.Default
    private boolean useBusinessRates = false;

    @Column(name = "billing_address_line1", length = 255)
    private String billingAddressLine1;

    @Column(name = "billing_address_line2", length = 255)
    private String billingAddressLine2;

    @Column(name = "billing_city", length = 100)
    private String billingCity;

    @Column(name = "billing_state", length = 100)
    private String billingState;

    @Column(name = "billing_pincode", length = 10)
    private String billingPincode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private CustomerStatus status = CustomerStatus.ACTIVE;

    // Communication Center (V50) preferences — opt-out, not opt-in. Read by
    // com.courier.modules.communication's send-time gate: company channel enabled AND
    // that event's template ACTIVE on that channel AND this customer's own preference.
    @Column(name = "whatsapp_enabled", nullable = false)
    @Builder.Default
    private boolean whatsappEnabled = true;

    @Column(name = "sms_enabled", nullable = false)
    @Builder.Default
    private boolean smsEnabled = true;

    @Column(name = "email_enabled", nullable = false)
    @Builder.Default
    private boolean emailEnabled = true;

    // ---------------------------------------------------------------- behaviour

    public static String normaliseCode(String code) {
        return code == null || code.isBlank() ? null : code.trim().toUpperCase().replace(' ', '_');
    }

    public boolean isActive() {
        return status == CustomerStatus.ACTIVE;
    }

    public void activate() {
        this.status = CustomerStatus.ACTIVE;
    }

    public void deactivate() {
        this.status = CustomerStatus.INACTIVE;
    }

    /**
     * Displayable name: the company name for a business (falling back to the contact's
     * name if none was given), otherwise the person's full name.
     */
    public String displayName() {
        if (customerType == CustomerType.BUSINESS && companyName != null && !companyName.isBlank()) {
            return companyName.trim();
        }
        StringBuilder name = new StringBuilder(firstName == null ? "" : firstName.trim());
        if (middleName != null && !middleName.isBlank()) {
            name.append(' ').append(middleName.trim());
        }
        if (lastName != null && !lastName.isBlank()) {
            name.append(' ').append(lastName.trim());
        }
        return name.toString().trim();
    }

    /** Normalises free-text fields and enforces the one rule that is unconditional: GST is
     * mandatory for a {@link CustomerType#BUSINESS} customer and forbidden for neither, but
     * only required when the type is business. */
    public void applyInvariants() {
        this.customerCode = normaliseCode(customerCode);
        this.firstName = trim(firstName);
        this.middleName = trim(middleName);
        this.lastName = trim(lastName);
        this.companyName = trim(companyName);
        this.contactPerson = trim(contactPerson);
        this.billingAddressLine1 = trim(billingAddressLine1);
        this.billingAddressLine2 = trim(billingAddressLine2);
        this.billingCity = trim(billingCity);
        this.billingState = trim(billingState);
        this.billingPincode = trim(billingPincode);
        if (defaultPaymentMode == null) {
            this.defaultPaymentMode = CustomerPaymentMode.PAID;
        }
        if (billingFrequency == null) {
            this.billingFrequency = BillingFrequency.MONTHLY;
        }
        this.mobile = trim(mobile);
        this.alternateMobile = trim(alternateMobile);
        this.email = email == null ? null : email.trim().toLowerCase();
        this.gstNumber = gstNumber == null || gstNumber.isBlank() ? null : gstNumber.trim().toUpperCase();
        this.panNumber = panNumber == null || panNumber.isBlank() ? null : panNumber.trim().toUpperCase();
        this.website = website == null || website.isBlank() ? null : website.trim();

        if (customerType == null) {
            throw new BusinessRuleException("A customer must have a type.");
        }
        if (status == null) {
            this.status = CustomerStatus.ACTIVE;
        }
        if (customerType == CustomerType.BUSINESS && (gstNumber == null || gstNumber.isBlank())) {
            throw new BusinessRuleException("GST number is mandatory for a business customer.");
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
