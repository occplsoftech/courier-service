package com.courier.modules.company.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.courier.modules.company.domain.PermissionAction.ACTIVATE;
import static com.courier.modules.company.domain.PermissionAction.APPROVE;
import static com.courier.modules.company.domain.PermissionAction.ASSIGN;
import static com.courier.modules.company.domain.PermissionAction.CALCULATE;
import static com.courier.modules.company.domain.PermissionAction.CANCEL;
import static com.courier.modules.company.domain.PermissionAction.CREATE;
import static com.courier.modules.company.domain.PermissionAction.DEACTIVATE;
import static com.courier.modules.company.domain.PermissionAction.DELETE;
import static com.courier.modules.company.domain.PermissionAction.DELIVER;
import static com.courier.modules.company.domain.PermissionAction.DISPATCH;
import static com.courier.modules.company.domain.PermissionAction.DOWNLOAD;
import static com.courier.modules.company.domain.PermissionAction.EXCEPTION_MANAGE;
import static com.courier.modules.company.domain.PermissionAction.EXPORT;
import static com.courier.modules.company.domain.PermissionAction.IMPORT;
import static com.courier.modules.company.domain.PermissionAction.IN_SCAN;
import static com.courier.modules.company.domain.PermissionAction.OUT_SCAN;
import static com.courier.modules.company.domain.PermissionAction.PRINT;
import static com.courier.modules.company.domain.PermissionAction.READ;
import static com.courier.modules.company.domain.PermissionAction.RECEIVE;
import static com.courier.modules.company.domain.PermissionAction.RECHARGE;
import static com.courier.modules.company.domain.PermissionAction.REJECT;
import static com.courier.modules.company.domain.PermissionAction.RENEW;
import static com.courier.modules.company.domain.PermissionAction.SORT;
import static com.courier.modules.company.domain.PermissionAction.SUSPEND;
import static com.courier.modules.company.domain.PermissionAction.SEARCH;
import static com.courier.modules.company.domain.PermissionAction.UPDATE;
import static com.courier.modules.company.domain.PermissionAction.UPLOAD;
import static com.courier.modules.company.domain.PermissionAction.VALIDATE;

/**
 * Which actions exist for which module.
 *
 * <p>Deliberately <b>not</b> the full 29 × 21 cross product. {@code DASHBOARD_DELETE} and
 * {@code TRACKING_APPROVE} are not rights anyone can hold; seeding them would put 609
 * rows in front of an operator, most of them meaningless, and every one of them would
 * look grantable.
 *
 * <p>This is the single source of truth for the seeded catalogue. The Flyway migration
 * inserts exactly what this declares, and a test asserts the two agree.
 */
public final class DefaultPermissionCatalog {

    /** The common shape: full lifecycle plus listing. */
    private static final Set<PermissionAction> CRUD = EnumSet.of(CREATE, READ, UPDATE, DELETE, SEARCH);

    /**
     * A master-data module: CRUD, bulk movement in and out, and the activate/deactivate
     * pair — deactivating a row is how a company withdraws it from the pickers without
     * deleting it, so a catalogue that can be edited but not deactivated is incomplete.
     */
    private static final Set<PermissionAction> MASTER =
            EnumSet.of(CREATE, READ, UPDATE, DELETE, SEARCH, IMPORT, EXPORT, ACTIVATE, DEACTIVATE);

    private static final Map<PermissionModule, Set<PermissionAction>> MATRIX =
            new EnumMap<>(PermissionModule.class);

    static {
        // Administration. AUTH is read-only: sessions and login history are inspected,
        // never edited — revoking a session is a USER action, not an AUTH one.
        MATRIX.put(PermissionModule.AUTH, EnumSet.of(READ, SEARCH));
        // The full company lifecycle a super admin now has endpoints for. It was READ +
        // UPDATE while creating a company was the only thing anyone could do to one.
        // Nothing here is ever granted to a company role — a company administering its
        // own suspension is not a right, it is a bug.
        MATRIX.put(PermissionModule.COMPANY,
                EnumSet.of(CREATE, READ, UPDATE, DELETE, SEARCH, EXPORT,
                        ACTIVATE, DEACTIVATE, SUSPEND));
        // A company's place on a plan. ASSIGN / RENEW / SUSPEND are the three commercial
        // acts; there is no CREATE or DELETE, because a subscription is not a row anyone
        // makes or destroys — a company always has one.
        MATRIX.put(PermissionModule.SUBSCRIPTION,
                EnumSet.of(READ, SEARCH, ASSIGN, RENEW, SUSPEND));
        // Creating platform operators. No UPDATE or DELETE yet: neither endpoint exists,
        // and seeding a right nothing enforces teaches operators it means something.
        MATRIX.put(PermissionModule.SUPER_ADMIN_USER, EnumSet.of(CREATE, READ, SEARCH));
        MATRIX.put(PermissionModule.USER,
                EnumSet.of(CREATE, READ, UPDATE, DELETE, SEARCH, EXPORT, ASSIGN, ACTIVATE, DEACTIVATE));
        // Same shape as BRANCH below: a department is a handful of company-defined rows,
        // not a bulk-imported catalogue, so no IMPORT.
        MATRIX.put(PermissionModule.DEPARTMENT,
                EnumSet.of(CREATE, READ, UPDATE, DELETE, SEARCH, EXPORT, ACTIVATE, DEACTIVATE));
        MATRIX.put(PermissionModule.ROLE,
                EnumSet.of(CREATE, READ, UPDATE, DELETE, SEARCH, ASSIGN, ACTIVATE, DEACTIVATE));
        MATRIX.put(PermissionModule.PERMISSION,
                EnumSet.of(CREATE, READ, UPDATE, DELETE, SEARCH, ASSIGN));
        // Menus are read and assigned, never created or deleted: the menu tree is the
        // application's own navigation, not a company's data. A branch manager holds
        // these two and none of the PERMISSION rights above.
        MATRIX.put(PermissionModule.MENU, EnumSet.of(READ, ASSIGN));

        // Network and master data.
        MATRIX.put(PermissionModule.BRANCH,
                EnumSet.of(CREATE, READ, UPDATE, DELETE, SEARCH, EXPORT, ACTIVATE, DEACTIVATE));
        // Hub Operations (2026-09-21) added five operational rights on top of the CRUD
        // this module was seeded with while it was still just a branch-shaped master —
        // a hub's screens are receiving, sorting, out-scanning and raising exceptions,
        // not just editing the hub record itself. Load Sheet/vehicle/dispatch reuse
        // MANIFEST_* and dispatch reuses the existing DISPATCH action; only the four
        // hub-specific verbs (IN_SCAN/OUT_SCAN/SORT/EXCEPTION_MANAGE) are new.
        MATRIX.put(PermissionModule.HUB,
                EnumSet.of(CREATE, READ, UPDATE, DELETE, SEARCH, EXPORT, ACTIVATE, DEACTIVATE,
                        IN_SCAN, OUT_SCAN, SORT, DISPATCH, EXCEPTION_MANAGE));
        MATRIX.put(PermissionModule.CUSTOMER,
                EnumSet.of(CREATE, READ, UPDATE, DELETE, SEARCH, IMPORT, EXPORT, ACTIVATE, DEACTIVATE));
        MATRIX.put(PermissionModule.ADDRESS, CRUD);
        // The twelve Phase-6 reference lists share one module; pincodes and routes keep
        // the rights they were seeded with in V6 and gain the same activate/deactivate.
        MATRIX.put(PermissionModule.MASTER_DATA, MASTER);
        // The shared geography. Same shape as a company's own master data, different
        // audience: these are held by a platform operator and by nobody else.
        MATRIX.put(PermissionModule.GLOBAL_MASTER, MASTER);
        MATRIX.put(PermissionModule.PINCODE, MASTER);
        // CALCULATE is read-only pricing (decision: Rate Master, 2026-07-30) — it must
        // reach every desk that books a shipment, not just whoever edits the rate card,
        // which is why it is granted far more broadly than CREATE/UPDATE/DELETE below.
        MATRIX.put(PermissionModule.RATE_MASTER,
                EnumSet.of(CREATE, READ, UPDATE, DELETE, SEARCH, IMPORT, EXPORT, APPROVE,
                        ACTIVATE, DEACTIVATE, CALCULATE));
        // Charge & Charge Settings: a company's own pricing/commission configuration,
        // COMPANY_ADMIN-only. Same master-data shape as ROUTE_MASTER below — full CRUD
        // plus the activate/deactivate pair a configuration row needs to be withdrawn
        // without deleting it.
        MATRIX.put(PermissionModule.CHARGE, MASTER);
        MATRIX.put(PermissionModule.PRICING_ZONE, MASTER);
        MATRIX.put(PermissionModule.TARIFF, MASTER);
        MATRIX.put(PermissionModule.CUSTOMER_RATE, MASTER);
        MATRIX.put(PermissionModule.CUSTOMER_BILLING,
                EnumSet.of(CREATE, READ, UPDATE, DELETE, SEARCH, EXPORT, PRINT));
        MATRIX.put(PermissionModule.ROUTE_MASTER, MASTER);

        // Operations.
        // UPLOAD added for Shipment Booking's POST /shipments/{id}/documents (Invoice,
        // E-Way Bill, Packing List, LR Copy, POD) — 2026-07-30.
        MATRIX.put(PermissionModule.SHIPMENT,
                EnumSet.of(CREATE, READ, UPDATE, DELETE, SEARCH, IMPORT, EXPORT, PRINT, ASSIGN,
                        UPLOAD));
        // "VIEW" from the E-Way Bill brief is seeded as READ — this catalogue has never
        // used a "_VIEW" code (SHIPMENT_READ, CUSTOMER_READ, ...), so EWAY_BILL follows
        // the same vocabulary rather than being the one exception.
        MATRIX.put(PermissionModule.EWAY_BILL,
                EnumSet.of(CREATE, READ, UPDATE, SEARCH, EXPORT, UPLOAD, VALIDATE, CANCEL));
        // Scans are appended, never edited or removed: a tracking history that can be
        // rewritten is not evidence.
        MATRIX.put(PermissionModule.TRACKING, EnumSet.of(CREATE, READ, SEARCH, EXPORT));
        // A manifest's life is create -> assign a vehicle -> dispatch -> receive, and each
        // step is a different desk on a different day. ASSIGN is the vehicle going onto
        // the manifest; DISPATCH and RECEIVE are the two ends of the trunk movement.
        MATRIX.put(PermissionModule.MANIFEST,
                EnumSet.of(CREATE, READ, UPDATE, DELETE, SEARCH, PRINT, EXPORT,
                        ASSIGN, DISPATCH, RECEIVE));
        MATRIX.put(PermissionModule.PICKUP,
                EnumSet.of(CREATE, READ, UPDATE, SEARCH, ASSIGN, PRINT));
        // DISPATCH is "out for delivery" — the run leaves the branch; DELIVER is the
        // shipment closed against the consignee, which for a TO_PAY booking is also when
        // the delivery branch's wallet is debited.
        MATRIX.put(PermissionModule.DELIVERY,
                EnumSet.of(CREATE, READ, UPDATE, SEARCH, ASSIGN, UPLOAD, DISPATCH, DELIVER));

        // Fleet.
        MATRIX.put(PermissionModule.DRIVER,
                EnumSet.of(CREATE, READ, UPDATE, DELETE, SEARCH, EXPORT, ASSIGN, ACTIVATE, DEACTIVATE));
        MATRIX.put(PermissionModule.VEHICLE,
                EnumSet.of(CREATE, READ, UPDATE, DELETE, SEARCH, EXPORT, ASSIGN, ACTIVATE, DEACTIVATE));
        MATRIX.put(PermissionModule.VENDOR,
                EnumSet.of(CREATE, READ, UPDATE, DELETE, SEARCH, EXPORT, ACTIVATE, DEACTIVATE));
        MATRIX.put(PermissionModule.PDP,
                EnumSet.of(CREATE, READ, UPDATE, SEARCH, EXPORT, ACTIVATE, DEACTIVATE));
        MATRIX.put(PermissionModule.PDP_RATE, MASTER);
        MATRIX.put(PermissionModule.PDP_BILLING,
                EnumSet.of(CREATE, READ, UPDATE, DELETE, SEARCH, EXPORT, PRINT));

        // Money. Nothing here may be deleted — financial records are corrected by
        // further records, never removed.
        // RECHARGE rather than CREATE or UPDATE: a wallet is created with its branch and
        // its balance is never assigned, only moved (decision 35). Topping up is the one
        // money act a branch performs on itself, so it is its own right.
        MATRIX.put(PermissionModule.WALLET, EnumSet.of(READ, UPDATE, SEARCH, EXPORT, RECHARGE));
        MATRIX.put(PermissionModule.PAYMENT,
                EnumSet.of(CREATE, READ, UPDATE, SEARCH, EXPORT, APPROVE, REJECT, PRINT));
        MATRIX.put(PermissionModule.INVOICE,
                EnumSet.of(CREATE, READ, UPDATE, SEARCH, EXPORT, PRINT, DOWNLOAD, APPROVE));

        // Insight and platform.
        MATRIX.put(PermissionModule.REPORT, EnumSet.of(READ, SEARCH, EXPORT, DOWNLOAD, PRINT));
        MATRIX.put(PermissionModule.DASHBOARD, EnumSet.of(READ, EXPORT));
        MATRIX.put(PermissionModule.SETTINGS, EnumSet.of(READ, UPDATE));
        MATRIX.put(PermissionModule.NOTIFICATION, CRUD);
        // The audit trail is append-only by design; it is read and exported, nothing more.
        MATRIX.put(PermissionModule.AUDIT, EnumSet.of(READ, SEARCH, EXPORT));
    }

    /**
     * Permissions that require a subscription feature. Carried over from the enum this
     * catalogue replaced, so a plan without bulk booking still cannot have the right
     * granted to any of its roles.
     */
    private static final Map<String, String> PLAN_GATED = Map.of(
            Permission.codeFor(PermissionModule.SHIPMENT, IMPORT), "bulkBooking",
            Permission.codeFor(PermissionModule.CUSTOMER, IMPORT), "bulkBooking");

    private DefaultPermissionCatalog() {
    }

    public static Map<PermissionModule, Set<PermissionAction>> matrix() {
        return Map.copyOf(MATRIX);
    }

    public static Set<PermissionAction> actionsFor(PermissionModule module) {
        return MATRIX.getOrDefault(module, Set.of());
    }

    /** @return the feature flag this code depends on, or null when unconditional */
    public static String requiredFeatureFlag(String permissionCode) {
        return PLAN_GATED.get(permissionCode);
    }

    /** How many permissions the catalogue defines. Asserted against the migration. */
    public static int size() {
        return MATRIX.values().stream().mapToInt(Set::size).sum();
    }
}
