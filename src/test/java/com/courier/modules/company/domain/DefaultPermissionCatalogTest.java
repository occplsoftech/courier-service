package com.courier.modules.company.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultPermissionCatalogTest {

    @Test
    @DisplayName("every module has at least one action, and none is the full cross product")
    void everyModuleIsCovered() {
        assertThat(DefaultPermissionCatalog.matrix()).hasSize(PermissionModule.values().length);

        assertThat(DefaultPermissionCatalog.matrix()).allSatisfy((module, actions) -> {
            assertThat(actions).as("actions for %s", module).isNotEmpty();
            // 28 x 15 would seed DASHBOARD_DELETE and TRACKING_APPROVE — rights nobody
            // can hold, in front of every operator building a role.
            assertThat(actions.size()).as("actions for %s", module)
                    .isLessThan(PermissionAction.values().length);
        });
    }

    @Test
    @DisplayName("the catalogue size matches what the migration seeds")
    void sizeMatchesMigration() {
        // V6 inserts 174, V11 a further 13 (the nine MASTER_DATA rights, plus the
        // activate/deactivate pair PINCODE and ROUTE_MASTER were missing), V12 a further 24
        // (the company lifecycle a super admin can now drive, the three commercial
        // subscription acts, the shared geography catalogue, and platform operators), and
        // V13 a further 8 (the new MENU module, and the manifest/delivery/wallet rights the
        // Branch responsibilities needed: MANIFEST_ASSIGN/DISPATCH/RECEIVE,
        // DELIVERY_DISPATCH/DELIVER, WALLET_RECHARGE), and V16 a further 3 (RATE_MASTER
        // gained ACTIVATE/DEACTIVATE, which every other master-shaped module already had,
        // plus the new CALCULATE action for pricing a shipment without editing the rate
        // card), V17 a further 1 (SHIPMENT gained UPLOAD, for Shipment Booking's
        // document-upload endpoint), V47 a further 8 (the new EWAY_BILL module: CREATE/
        // READ/UPDATE/SEARCH/EXPORT/UPLOAD/VALIDATE/CANCEL, the last two new
        // PermissionAction values E-Way Bill Management needed), and V63 a further 9 (the
        // new CHARGE module: CREATE/READ/UPDATE/DELETE/SEARCH/IMPORT/EXPORT/ACTIVATE/
        // DEACTIVATE, the same MASTER shape ROUTE_MASTER already has), and V71 a further 8
        // (the new DEPARTMENT module: CREATE/READ/UPDATE/DELETE/SEARCH/EXPORT/ACTIVATE/
        // DEACTIVATE, the same shape BRANCH already has), and V106 a further 18 (the new
        // PRICING_ZONE and CUSTOMER_RATE modules, 9 MASTER-shaped rights each), and V110 a further 7 (the new
        // CUSTOMER_BILLING module: CREATE/READ/UPDATE/DELETE/SEARCH/EXPORT/PRINT), and V115 a further 23
        // (the new PDP, PDP_RATE and PDP_BILLING modules: 7 + 9 + 7), and V138 a further 9 (the new TARIFF
        // module, MASTER shape). All generated
        // from this matrix.
        // If someone adds a module or action here without a follow-up migration, this is
        // the tripwire.
        assertThat(DefaultPermissionCatalog.size()).isEqualTo(310);
    }

    @Test
    @DisplayName("every permission code is unique")
    void codesAreUnique() {
        Set<String> codes = DefaultPermissionCatalog.matrix().entrySet().stream()
                .flatMap(entry -> entry.getValue().stream()
                        .map(action -> Permission.codeFor(entry.getKey(), action)))
                .collect(java.util.stream.Collectors.toSet());

        assertThat(codes).hasSize(DefaultPermissionCatalog.size());
    }

    @Test
    @DisplayName("append-only and financial modules cannot be deleted from")
    void destructiveActionsAreWithheldWhereTheyMatter() {
        // Tracking is evidence: a scan history that can be edited or removed is not.
        assertThat(DefaultPermissionCatalog.actionsFor(PermissionModule.TRACKING))
                .doesNotContain(PermissionAction.DELETE, PermissionAction.UPDATE);
        // The audit trail is append-only by design.
        assertThat(DefaultPermissionCatalog.actionsFor(PermissionModule.AUDIT))
                .containsExactlyInAnyOrder(PermissionAction.READ, PermissionAction.SEARCH,
                        PermissionAction.EXPORT);
        // Financial records are corrected by further records, never removed.
        assertThat(DefaultPermissionCatalog.actionsFor(PermissionModule.PAYMENT))
                .doesNotContain(PermissionAction.DELETE);
        assertThat(DefaultPermissionCatalog.actionsFor(PermissionModule.INVOICE))
                .doesNotContain(PermissionAction.DELETE);
        assertThat(DefaultPermissionCatalog.actionsFor(PermissionModule.WALLET))
                .doesNotContain(PermissionAction.DELETE, PermissionAction.CREATE);
    }

    @Test
    @DisplayName("only the bulk-import rights are plan gated")
    void planGating() {
        assertThat(DefaultPermissionCatalog.requiredFeatureFlag("SHIPMENT_IMPORT"))
                .isEqualTo("bulkBooking");
        assertThat(DefaultPermissionCatalog.requiredFeatureFlag("CUSTOMER_IMPORT"))
                .isEqualTo("bulkBooking");
        assertThat(DefaultPermissionCatalog.requiredFeatureFlag("SHIPMENT_CREATE")).isNull();
    }

    @Test
    @DisplayName("actionsFor never returns null")
    void actionsForIsNeverNull() {
        for (PermissionModule module : PermissionModule.values()) {
            assertThat(DefaultPermissionCatalog.actionsFor(module))
                    .as("actions for %s", module).isNotNull().isNotEmpty();
        }
    }
}
