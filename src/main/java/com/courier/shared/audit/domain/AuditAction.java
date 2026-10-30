package com.courier.shared.audit.domain;

/**
 * Catalogue of auditable events.
 *
 * <p>An enum rather than a free-text string so that the set is greppable, typo-proof
 * and reportable. Modules add their own constants here as they land; the groupings
 * below mirror {@code MEMORY/modules/*.md}.
 */
public enum AuditAction {

    // --- authentication (Phase 2)
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    LOGOUT,
    LOGOUT_ALL_DEVICES,
    TOKEN_REFRESHED,
    REFRESH_TOKEN_REUSE_DETECTED,
    TOKEN_REVOKED,
    PASSWORD_CHANGED,
    PASSWORD_RESET_REQUESTED,
    PASSWORD_RESET_COMPLETED,
    ACCOUNT_LOCKED,
    ACCOUNT_UNLOCKED,
    EMAIL_VERIFICATION_SENT,
    EMAIL_VERIFIED,
    SESSION_REVOKED,
    /** A refresh was attempted against a session past its TTL or already revoked — see
     *  {@code AuthService#refresh} and {@code LoginAttemptService#recordSessionExpired}. */
    SESSION_EXPIRED,
    /** SUPER_ADMIN opened a "login as company" session. See {@code AuthService#impersonateCompany}. */
    COMPANY_IMPERSONATED,
    /** COMPANY_ADMIN opened a "login as branch" session. See {@code AuthService#impersonateBranch}. */
    BRANCH_IMPERSONATED,
    USER_CREATED,
    ROLE_GRANTED,
    ROLE_REVOKED,

    // --- company, the ownership root (Phase 2). A company is the tenant, so there is
    // one set of constants for it and no TENANT_* parallel.
    COMPANY_CREATED,
    COMPANY_UPDATED,
    COMPANY_ACTIVATED,
    COMPANY_DEACTIVATED,
    COMPANY_SUSPENDED,
    COMPANY_EXPIRED,
    COMPANY_DELETED,
    COMPANY_ROLES_SEEDED,
    COMPANY_SETTINGS_SEEDED,
    COMPANY_SETTINGS_INITIALIZED,
    COMPANY_SETTINGS_UPDATED,

    // --- a company's subscription (Phase 2, SUPER_ADMIN). Distinct from the
    // SUBSCRIPTION_PLAN_* constants further down, which are about the plan catalogue
    // itself rather than one company's place in it.
    COMPANY_SUBSCRIPTION_ASSIGNED,
    COMPANY_SUBSCRIPTION_RENEWED,
    COMPANY_SUBSCRIPTION_SUSPENDED,

    // --- platform administration (SUPER_ADMIN)
    SUPER_ADMIN_USER_CREATED,

    // --- role management (Phase 3, COMPANY_ADMIN). Distinct from ROLE_GRANTED /
    // ROLE_REVOKED below, which are about giving a role to a *user*.
    ROLE_CREATED,
    ROLE_UPDATED,
    ROLE_DELETED,
    ROLE_ACTIVATED,
    ROLE_DEACTIVATED,

    // --- department management. A department's role grants change with it (create/update
    // carry the full set), so there is no separate "assigned" action the way roles have
    // one for permissions.
    DEPARTMENT_CREATED,
    DEPARTMENT_UPDATED,
    DEPARTMENT_DELETED,
    DEPARTMENT_ACTIVATED,
    DEPARTMENT_DEACTIVATED,

    // --- permission management (Phase 3). Catalogue writes are SUPER_ADMIN; grants are
    // the company's own decision and are recorded against the role.
    PERMISSION_CREATED,
    PERMISSION_UPDATED,
    PERMISSION_DELETED,
    ROLE_PERMISSIONS_ASSIGNED,
    ROLE_PERMISSION_REVOKED,

    // --- user management (Phase 3, COMPANY_ADMIN). USER_CREATED / ROLE_GRANTED /
    // ROLE_REVOKED above predate this and are auth's; these are the company-admin actions.
    USER_UPDATED,
    USER_ACTIVATED,
    USER_DEACTIVATED,
    USER_LOCKED,
    USER_UNLOCKED,
    USER_PASSWORD_RESET,
    USER_ROLE_ASSIGNED,
    USER_ROLE_REMOVED,
    USER_BRANCH_ASSIGNED,
    USER_HUB_ASSIGNED,

    // --- branch management (Phase 4, COMPANY_ADMIN / BRANCH_MANAGER)
    BRANCH_CREATED,
    BRANCH_UPDATED,
    BRANCH_ACTIVATED,
    BRANCH_DEACTIVATED,
    BRANCH_MANAGER_ASSIGNED,
    BRANCH_HUB_ASSIGNED,
    BRANCH_USERS_ASSIGNED,
    BRANCH_PINCODES_MAPPED,
    BRANCH_PINCODE_UNMAPPED,

    // --- customer management (Phase 7, COMPANY_ADMIN / BRANCH_MANAGER / OPERATOR).
    // Reusable master data, independent of Shipment Order.
    CUSTOMER_CREATED,
    CUSTOMER_UPDATED,
    KYC_UPDATED,
    CUSTOMER_ACTIVATED,
    CUSTOMER_DEACTIVATED,
    CUSTOMER_ADDRESS_CREATED,
    CUSTOMER_ADDRESS_UPDATED,
    CUSTOMER_ADDRESS_DELETED,

    // --- MSRTC bus depot / station master.
    BUS_DEPOT_CREATED,
    BUS_DEPOT_UPDATED,
    BUS_DEPOT_ACTIVATED,
    BUS_DEPOT_DEACTIVATED,
    BUS_DEPOT_DELETED,
    BUS_DIVISION_CREATED,
    BUS_DIVISION_UPDATED,
    BUS_DIVISION_ACTIVATED,
    BUS_DIVISION_DEACTIVATED,
    BUS_DIVISION_DELETED,
    BUS_CROSSING_ROUTE_SAVED,
    BUS_CROSSING_ROUTE_ACTIVATED,
    BUS_CROSSING_ROUTE_DEACTIVATED,
    BUS_CROSSING_ROUTE_DELETED,
    SHIPMENT_BUS_CROSSING_SET,

    // --- business customer pricing: zones, their locations, and the per-zone rate card.
    PRICING_ZONE_CREATED,
    PRICING_ZONE_UPDATED,
    PRICING_ZONE_ACTIVATED,
    PRICING_ZONE_DEACTIVATED,
    PRICING_ZONE_DELETED,
    PRICING_ZONE_LOCATION_ADDED,
    PRICING_ZONE_LOCATION_REMOVED,
    // --- company zone tariff (V138)
    TARIFF_ZONE_CREATED,
    TARIFF_ZONE_UPDATED,
    TARIFF_ZONE_ACTIVATED,
    TARIFF_ZONE_DEACTIVATED,
    TARIFF_ZONE_DELETED,
    TARIFF_ZONE_LOCATION_ADDED,
    TARIFF_ZONE_LOCATION_REMOVED,
    TARIFF_RATE_CARD_CREATED,
    TARIFF_RATE_CARD_UPDATED,
    TARIFF_RATE_CARD_ACTIVATED,
    TARIFF_RATE_CARD_DEACTIVATED,
    TARIFF_RATE_CARD_DELETED,
    TARIFF_RATE_LANE_SAVED,
    TARIFF_RATE_LANE_DELETED,
    TARIFF_CHARGE_SAVED,
    TARIFF_CHARGE_DELETED,
    // --- customer portal: markup on external courier rates (V128)
    PRICING_MARKUP_RULE_CREATED,
    PRICING_MARKUP_RULE_CLOSED,
    CUSTOMER_COURIER_ACCESS_CHANGED,
    PORTAL_BOOKING_SUBMITTED,
    PORTAL_BOOKING_BOOKED,
    PORTAL_BOOKING_FAILED,
    PORTAL_BOOKING_OUTCOME_UNKNOWN,
    CUSTOMER_RATE_SAVED,
    CUSTOMER_RATE_ACTIVATED,
    CUSTOMER_RATE_DEACTIVATED,
    PDP_RATE_SAVED,
    PDP_RATE_ACTIVATED,
    PDP_RATE_DEACTIVATED,
    PDP_RATE_DELETED,
    PICKUP_CREATED,
    PICKUP_UPDATED,
    PICKUP_ASSIGNED,
    PICKUP_ACCEPTED,
    PICKUP_REJECTED,
    PICKUP_STARTED,
    PICKUP_COMPLETED,
    PICKUP_FAILED,
    PICKUP_BOOKED,
    PICKUP_CANCELLED,
    PDP_EARNING_REPRICED,
    PDP_STATEMENT_GENERATED,
    PDP_STATEMENT_APPROVED,
    PDP_STATEMENT_PAID,
    PDP_STATEMENT_DELETED,
    PDP_BILLING_FREQUENCY_CHANGED,
    CUSTOMER_INVOICE_GENERATED,
    CUSTOMER_INVOICE_PAYMENT_RECORDED,
    CUSTOMER_INVOICE_CANCELLED,
    CUSTOMER_INVOICE_APPROVED,
    CUSTOMER_INVOICE_EMAILED,
    CUSTOMER_INVOICE_PAYMENT_REVERSED,
    CUSTOMER_INVOICE_PROOF_ATTACHED,

    // --- rate master (Phase 4 remainder, COMPANY_ADMIN). RATE_CALCULATED is deliberately
    // absent: a quote reads the rate card and moves nothing, and every other CALCULATE
    // right in the catalogue (see PermissionAction) is unaudited for the same reason.
    RATE_CREATED,
    RATE_UPDATED,
    RATE_ACTIVATED,
    RATE_DEACTIVATED,

    // --- freight factor: a standalone, company-level distance x weight pricing grid,
    // independent of rate master. FREIGHT_CALCULATED is deliberately absent, same
    // reasoning as RATE_CALCULATED above.
    FREIGHT_FACTOR_CREATED,
    FREIGHT_FACTOR_UPDATED,
    FREIGHT_FACTOR_ACTIVATED,
    FREIGHT_FACTOR_DEACTIVATED,

    // --- branch wallet (Phase 5, Finance). Every one of these is money moving, or an
    // attempt to move it, so the trail is the record of record: WALLET_RECHARGE_INITIATED
    // is written when a gateway order is opened, which is intent rather than money — it has
    // no ledger entry by design, and this is where "who tried what" lives.
    WALLET_CREATED,
    WALLET_CREDITED,
    WALLET_DEBITED,
    WALLET_RECHARGE_INITIATED,
    WALLET_RECHARGED,
    // A top-up request moves nothing by itself (WALLET_TOPUP_REQUESTED is intent, same
    // reasoning as WALLET_RECHARGE_INITIATED above); WALLET_TOPUP_APPROVED's own money
    // movement is the WALLET_CREDITED entry approval writes via WalletService.credit.
    WALLET_TOPUP_REQUESTED,
    WALLET_TOPUP_APPROVED,
    WALLET_TOPUP_REJECTED,
    // A company's own Razorpay credentials, used instead of the platform-wide gateway.
    // The audit detail never carries the key secret itself, only enabled/keyId/whether a
    // secret was rotated — same "never returned" rule the secret's own API response follows.
    COMPANY_RAZORPAY_CONFIG_UPDATED,
    COURIER_INTEGRATION_SAVED,
    COURIER_INTEGRATION_TESTED,
    COURIER_INTEGRATION_DISCONNECTED,

    // --- master data (Phase 6, COMPANY_ADMIN). One set for all twelve lists: the entity
    // name is already carried on every audit row, so a MASTER_COUNTRY_CREATED /
    // MASTER_CITY_CREATED / ... explosion would put twelve times the constants in front of
    // a reviewer to say what "entityType" already says.
    MASTER_DATA_CREATED,
    MASTER_DATA_UPDATED,
    MASTER_DATA_ACTIVATED,
    MASTER_DATA_DEACTIVATED,
    MASTER_DATA_DELETED,
    MASTER_DATA_SEEDED,

    // --- shipment booking (Phase 4 remainder, COMPANY_ADMIN / BRANCH_MANAGER / OPERATOR).
    // The wallet debit for a PREPAID booking is its own WALLET_DEBITED entry, written by
    // Finance — see the branch wallet section above — not duplicated here.
    SHIPMENT_BOOKED,
    SHIPMENT_UPDATED,
    SHIPMENT_CANCELLED,
    SHIPMENT_DOCUMENT_UPLOADED,
    SHIPMENT_IMAGE_UPLOADED,

    // --- E-Way Bill Management (V47, auto-generation V68)
    EWAY_BILL_CREATED,
    EWAY_BILL_UPDATED,
    EWAY_BILL_VALIDATED,
    EWAY_BILL_UPLOADED,
    EWAY_BILL_CANCELLED,
    EWAY_BILL_PART_A_GENERATED,
    EWAY_BILL_PART_B_GENERATED,
    EWAY_BILL_GENERATION_FAILED,
    EWAY_BILL_RETRIED,

    // --- shipment movement (V19: minimal Manifest + the movement pipeline on top of it)
    VEHICLE_CREATED,
    VEHICLE_UPDATED,
    VEHICLE_ACTIVATED,
    VEHICLE_DEACTIVATED,
    MANIFEST_CREATED,
    SHIPMENT_OUT_SCANNED,
    MANIFEST_DISPATCHED,
    MANIFEST_SHIPMENT_REMOVED,
    SHIPMENT_IN_SCANNED,
    SHIPMENT_OUT_FOR_DELIVERY_ASSIGNED,
    SHIPMENT_DELIVERED,
    SHIPMENT_POD_UPLOADED,
    SHIPMENT_STATUS_OVERRIDDEN,

    // --- crossing (V37): a shipment's transit through an intermediate branch/hub
    CROSSING_CREATED,
    CROSSING_STATUS_UPDATED,

    // --- subscription plans (Phase 2, SUPER_ADMIN only)
    SUBSCRIPTION_PLAN_CREATED,
    SUBSCRIPTION_PLAN_UPDATED,
    SUBSCRIPTION_PLAN_ACTIVATED,
    SUBSCRIPTION_PLAN_DEACTIVATED,
    SUBSCRIPTION_PLAN_DELETED,

    // --- ticket support (V39, Phase 1): full lifecycle, conversation, categories.
    // TICKET_MESSAGE_ADDED covers both public replies and internal notes; whether a
    // given entry was internal is on the row itself, so no separate constant is needed
    // for it, same reasoning MASTER_DATA_* gives for not exploding per-catalogue.
    TICKET_CREATED,
    TICKET_ASSIGNED,
    TICKET_REASSIGNED,
    TICKET_ESCALATED,
    TICKET_STATUS_CHANGED,
    TICKET_PRIORITY_CHANGED,
    TICKET_CATEGORY_CHANGED,
    TICKET_MESSAGE_ADDED,
    TICKET_ATTACHMENT_UPLOADED,
    TICKET_REOPENED,
    TICKET_CLOSED,
    TICKET_CATEGORY_CREATED,
    TICKET_CATEGORY_UPDATED,
    TICKET_SUB_CATEGORY_CREATED,
    TICKET_SUB_CATEGORY_UPDATED,

    // --- ticket support Phase 2 (V40): SLA rules, company-configured
    TICKET_SLA_RULE_CREATED,
    TICKET_SLA_RULE_UPDATED,

    // --- follow-up management (V44): branch operational task tracking, separate from
    // Ticket Support — see MEMORY/modules/follow-up.md for why the two aren't merged.
    FOLLOWUP_CREATED,
    FOLLOWUP_UPDATED,
    FOLLOWUP_ASSIGNED,
    FOLLOWUP_STATUS_CHANGED,
    FOLLOWUP_RESCHEDULED,
    FOLLOWUP_NOTE_ADDED,

    // --- POD Auto Verification (V48): AI-scored proof-of-delivery, never itself moves a
    // shipment to DELIVERED — see MEMORY/modules/pod-verification.md.
    POD_VERIFICATION_RUN,
    POD_VERIFICATION_APPROVED,
    POD_VERIFICATION_REJECTED,
    POD_VERIFICATION_COMPANY_UPLOAD,

    // --- Communication Center (V50): multi-channel event-driven notifications — see
    // MEMORY/modules/communication.md.
    COMMUNICATION_SETTING_UPDATED,
    COMMUNICATION_TEMPLATE_CREATED,
    COMMUNICATION_TEMPLATE_UPDATED,
    COMMUNICATION_RETRIED,

    // --- District Level Freight (V54): rate setup by From Station + District + weight
    // slab. Configuration only — not wired into Shipment Booking/Commission/Pricing.
    DISTRICT_FREIGHT_CREATED,
    DISTRICT_FREIGHT_UPDATED,
    DISTRICT_FREIGHT_DELETED,
    DISTRICT_FREIGHT_STATUS_CHANGED,
    DISTRICT_FREIGHT_IMPORTED,

    // --- Charge & Charge Settings: a standalone pricing/commission configuration module.
    // Configuration only — not wired into Shipment Booking/Commission/Pricing/Wallet yet;
    // a future calculation engine will consume these rows.
    CHARGE_CREATED,
    CHARGE_UPDATED,
    CHARGE_ACTIVATED,
    CHARGE_DEACTIVATED,
    CHARGE_DELETED,
    CHARGE_SETTING_CREATED,
    CHARGE_SETTING_UPDATED,
    CHARGE_SETTING_DELETED,
    CHARGE_SETTING_ACTIVATED,
    CHARGE_SETTING_DEACTIVATED,

    // --- Menu + Permission Management: the menu hierarchy catalogue (SUPER_ADMIN) and
    // per-user permission overrides on top of a role's defaults (COMPANY_ADMIN/BRANCH_MANAGER).
    MENU_ITEM_CREATED,
    MENU_ITEM_UPDATED,
    MENU_ITEM_DELETED,
    USER_PERMISSIONS_UPDATED,

    // --- Hub Operations: out-scan (duplicate-scan-before-dispatch ledger) and
    // exceptions (Missing/Damaged/Short/Wrong Destination/Misrouted/On Hold) —
    // neither ever changes Shipment.status itself.
    HUB_OUT_SCANNED,
    HUB_EXCEPTION_RAISED,
    HUB_EXCEPTION_RESOLVED,

    // --- generic CRUD, for modules that need nothing more specific
    CREATED,
    UPDATED,
    DELETED,

    // --- security
    ACCESS_DENIED,
    COMPANY_ISOLATION_VIOLATION_BLOCKED
}
