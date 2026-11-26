package com.courier.modules.shipment.application;

import com.courier.modules.company.application.BranchService;
import com.courier.modules.company.application.UserService;
import com.courier.modules.company.domain.User;
import com.courier.modules.master.application.PackageTypeService;
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
    @Mock private UserService users;
    @Mock private BranchService branches;
    @Mock private PackageTypeService packageTypes;
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
        when(users.getById(userId)).thenReturn(user);
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
}
