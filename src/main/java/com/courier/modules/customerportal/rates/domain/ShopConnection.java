package com.courier.modules.customerportal.rates.domain;

import com.courier.shared.domain.CompanyOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A business customer's online store (13.3b), connected with an access token from a custom app in their own store admin. Its open,
 * unfulfilled orders are imported into "Orders to ship"; after booking, the tracking number can be sent back to the store.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "shop_connections")
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class ShopConnection extends CompanyOwnedEntity {

    public enum Platform { SHOPIFY, WOOCOMMERCE }

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "customer_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 20, updatable = false)
    private Platform platform;

    /** e.g. myshop.myshopify.com */
    @Column(name = "shop_domain", nullable = false, length = 120) private String shopDomain;
    @Column(name = "shop_name", length = 150) private String shopName;
    /** The access token, encrypted; never returned. */
    @Column(name = "secrets_enc", columnDefinition = "TEXT") private String secretsEnc;

    @Column(name = "paused", nullable = false) private boolean paused;
    /** Send the tracking number back to the store (it marks the order fulfilled and the store emails the buyer). */
    @Column(name = "fulfil_back", nullable = false) @Builder.Default private boolean fulfilBack = true;

    @Column(name = "last_import_at") private Instant lastImportAt;
    @Column(name = "imported_count", nullable = false) private int importedCount;
    @Column(name = "last_error", length = 300) private String lastError;

    /** Orders that could not be imported, latest first: orderNumber, reason, at. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skipped_json", columnDefinition = "JSON")
    private List<Map<String, Object>> skipped;
}
