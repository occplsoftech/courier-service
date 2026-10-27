package com.courier.modules.company.domain;

/**
 * The functional area a permission belongs to. First half of a permission code:
 * {@code SHIPMENT_CREATE} is {@link #SHIPMENT} + {@code CREATE}.
 *
 * <p>Modules are listed here before the code that implements them exists — the
 * permission catalogue is the contract a module is built against, and seeding
 * {@code MANIFEST_CREATE} today costs nothing while renaming it after customers hold it
 * costs a migration and a support incident.
 *
 * <p>Ordinals are never persisted; the name is part of every permission code and must
 * not be changed once released.
 */
public enum PermissionModule {

    AUTH("Authentication", 10),
    COMPANY("Company", 20),
    /**
     * A company's place on a plan — assigned, renewed, suspended. Distinct from the plan
     * catalogue itself, which no company role touches at all. Platform-tier throughout.
     */
    SUBSCRIPTION("Subscription", 22),
    /** Platform-tier accounts: the operators who act above every company. */
    SUPER_ADMIN_USER("Platform Operators", 24),
    USER("Users", 30),
    /**
     * Which roles a new user in a department may hold — see
     * {@code com.courier.modules.company.domain.Department}. Sits with Users and Roles: a
     * department is a grouping of the two, not a master-data catalogue.
     */
    DEPARTMENT("Departments", 35),
    ROLE("Roles", 40),
    PERMISSION("Permissions", 50),
    /**
     * Which menus a role sees. Deliberately <em>not</em> the same right as
     * {@link #PERMISSION}: granting a permission is choosing from the whole 200-odd
     * catalogue and can hand out anything in the system, while assigning menus is
     * choosing which of the screens the assigner already reaches a subordinate role also
     * reaches. A branch manager needs the second and must never hold the first — one is
     * "staff my counter", the other is "re-permission the company".
     */
    MENU("Menus", 55),

    BRANCH("Branches", 60),
    HUB("Hubs", 70),
    CUSTOMER("Customers", 80),
    ADDRESS("Addresses", 90),
    PINCODE("Pincodes", 100),
    /**
     * The twelve reference lists of Phase 6 — geography, vehicle / package / service type,
     * payment mode, weight slab. One module rather than twelve: an operator building a
     * role thinks "may they edit master data", not "may they edit districts but not
     * cities", and twelve near-identical rights would be ninety more rows to scroll past
     * for a distinction nobody makes. {@link #PINCODE} and {@link #ROUTE_MASTER} keep
     * their own rights because they predate this and are already granted.
     */
    MASTER_DATA("Master Data", 105),
    /**
     * The geography every company shares — country, state, district, city, area,
     * pincode. Separate from {@link #MASTER_DATA} because the audience is different:
     * these rights belong to a platform operator, and a company role that held
     * {@code MASTER_DATA_UPDATE} must not thereby be able to rename a city for everyone.
     */
    GLOBAL_MASTER("Global Masters", 107),
    RATE_MASTER("Rate Master", 110),
    /**
     * Charge & Charge Settings: a standalone pricing/commission configuration module,
     * scoped to one Service Type per charge. {@code COMPANY_ADMIN}-only, both reads and
     * writes — not yet consumed by Shipment Booking or any freight/commission/wallet
     * calculation; that integration is separate, later work.
     */
    CHARGE("Charges", 112),
    /**
     * Business Customer: a customer's own pricing zones (named groups of districts/cities)
     * and the per-zone rate card. Separate from {@link #RATE_MASTER}, which is the
     * company-wide route-keyed rate card.
     */
    PRICING_ZONE("Pricing Zones", 125),
    CUSTOMER_RATE("Customer Rates", 127),
    /**
     * The company's own zone tariff (V138): zones of states/districts/cities/pincodes and, from Phase 2, the rate cards
     * priced by them. One module for the whole tariff, to keep the permission list (and the JWT) small.
     */
    TARIFF("Zone Tariff", 126),
    /** Business customer invoices: generate (CREATE), record a payment (UPDATE), cancel (DELETE). */
    CUSTOMER_BILLING("Customer Billing", 128),
    ROUTE_MASTER("Route Master", 120),

    SHIPMENT("Shipments", 130),
    /** One row per E-Way Bill raised against a shipment. See {@code com.courier.modules.ewaybill}. */
    EWAY_BILL("E-Way Bill", 135),
    TRACKING("Tracking", 140),
    MANIFEST("Manifests", 150),
    PICKUP("Pickups", 160),
    DELIVERY("Deliveries", 170),

    DRIVER("Drivers", 180),
    VEHICLE("Vehicles", 190),
    VENDOR("Vendors", 200),
    /** Pickup-Delivery Partner management (a Branch of type PICKUP_DELIVERY_PARTNER). */
    PDP("Pickup Delivery Partners", 205),
    /** PDP pickup- and delivery-rate cards (slabs). */
    PDP_RATE("Partner Rates", 206),
    /** PDP earnings and billing statements. */
    PDP_BILLING("Partner Billing", 207),

    WALLET("Wallet", 210),
    PAYMENT("Payments", 220),
    INVOICE("Invoices", 230),

    REPORT("Reports", 240),
    DASHBOARD("Dashboard", 250),
    SETTINGS("Settings", 260),
    NOTIFICATION("Notifications", 270),
    AUDIT("Audit", 280);

    private final String displayName;
    private final int displayOrder;

    PermissionModule(String displayName, int displayOrder) {
        this.displayName = displayName;
        this.displayOrder = displayOrder;
    }

    public String displayName() {
        return displayName;
    }

    /** Base sort key; a permission's own order is this plus the action's offset. */
    public int displayOrder() {
        return displayOrder;
    }
}
