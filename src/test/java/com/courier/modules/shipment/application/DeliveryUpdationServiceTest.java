package com.courier.modules.shipment.application;

import com.courier.modules.company.domain.BranchRepository;
import com.courier.modules.company.domain.CompanyUserRepository;
import com.courier.modules.company.domain.User;
import com.courier.modules.master.domain.PackageTypeRepository;
import com.courier.modules.shipment.domain.DeliveryAssignmentRepository;
import com.courier.modules.shipment.domain.DeliveryAssignmentStatus;
import com.courier.modules.shipment.domain.ShipmentAssetRepository;
import com.courier.modules.shipment.domain.ShipmentRepository;
import com.courier.modules.shipment.domain.ShipmentStatus;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.security.AuthenticatedUser;
import com.courier.shared.security.Roles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryUpdationServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID OWN_BRANCH = UUID.randomUUID();

    @Mock private DeliveryAssignmentRepository assignments;
    @Mock private ShipmentRepository shipments;
    @Mock private ShipmentAssetRepository assets;
    @Mock private CompanyUserRepository users;
    @Mock private BranchRepository branches;
    @Mock private PackageTypeRepository packageTypes;
    @InjectMocks private DeliveryUpdationService service;

    @BeforeEach
    void setUp() {
        CompanyContext.setCompanyId(COMPANY);
        when(assignments.searchForUpdation(any(), any(), any(), any(), any(), any())).thenReturn(Page.empty());
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    private void signIn(String role) {
        UUID userId = UUID.randomUUID();
        AuthenticatedUser u = new AuthenticatedUser(userId, COMPANY, "u@test.com", Set.of(role), "jti");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.authorities()));
        User user = new User();
        user.setBranchId(OWN_BRANCH);
        when(users.findByIdWithinCompany(userId, COMPANY)).thenReturn(java.util.Optional.of(user));
    }

    @Test
    @DisplayName("a branch user sees their own branch, whatever branch they ask for; pending = out for delivery")
    void branchUserScoped() {
        signIn(Roles.BRANCH_MANAGER);
        service.list(DeliveryUpdationService.Tab.PENDING, " PNQ ", UUID.randomUUID(), 0, 20);
        verify(assignments).searchForUpdation(eq(COMPANY), eq(DeliveryAssignmentStatus.ASSIGNED), eq(OWN_BRANCH),
                eq(ShipmentStatus.OUT_FOR_DELIVERY), eq("%pnq%"), any());
    }

    @Test
    @DisplayName("a company admin sees every branch unless one is asked for; delivered = any shipment status")
    void companyAdminAllBranches() {
        signIn(Roles.COMPANY_ADMIN);
        service.list(DeliveryUpdationService.Tab.DELIVERED, null, null, 0, 20);
        verify(assignments).searchForUpdation(eq(COMPANY), eq(DeliveryAssignmentStatus.DELIVERED), isNull(), isNull(),
                isNull(), any());
    }

    @Test
    @DisplayName("a branch user's rows still name the booking branch, package and people of other branches")
    void branchUserGetsOtherBranchNames() {
        signIn(Roles.BRANCH_MANAGER);
        UUID shipmentId = UUID.randomUUID(), bookingBranch = UUID.randomUUID(), rider = UUID.randomUUID(), pkg = UUID.randomUUID();
        com.courier.modules.shipment.domain.DeliveryAssignment a = org.mockito.Mockito.mock(com.courier.modules.shipment.domain.DeliveryAssignment.class);
        when(a.getShipmentId()).thenReturn(shipmentId);
        when(a.getDeliveryUserId()).thenReturn(rider);
        com.courier.modules.shipment.domain.Shipment s = org.mockito.Mockito.mock(com.courier.modules.shipment.domain.Shipment.class);
        when(s.getId()).thenReturn(shipmentId);
        when(s.getBookingBranchId()).thenReturn(bookingBranch);
        when(s.getPackageTypeId()).thenReturn(pkg);
        when(assignments.searchForUpdation(any(), any(), any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(a)));
        when(shipments.findAllByCompanyIdAndIdIn(eq(COMPANY), any())).thenReturn(java.util.List.of(s));
        com.courier.modules.company.domain.Branch b = new com.courier.modules.company.domain.Branch();
        b.setBranchName("Pune Test Branch");
        when(branches.findByIdWithinCompany(bookingBranch, COMPANY)).thenReturn(java.util.Optional.of(b));
        User r = new User();
        r.setFirstName("Chandrakant");
        r.setLastName("Borse");
        when(users.findByIdWithinCompany(rider, COMPANY)).thenReturn(java.util.Optional.of(r));
        com.courier.modules.master.domain.PackageType p = new com.courier.modules.master.domain.PackageType();
        p.setName("Non Document");
        when(packageTypes.findByIdWithinCompany(pkg, COMPANY)).thenReturn(java.util.Optional.of(p));

        var row = service.list(DeliveryUpdationService.Tab.PENDING, null, null, 0, 20).getContent().get(0);

        org.assertj.core.api.Assertions.assertThat(row.bookingBranch()).isEqualTo("Pune Test Branch");
        org.assertj.core.api.Assertions.assertThat(row.deliveryPerson()).isEqualTo("Chandrakant Borse");
        org.assertj.core.api.Assertions.assertThat(row.packageType()).isEqualTo("Non Document");
    }
}
