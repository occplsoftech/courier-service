package com.courier.modules.pickup.application;

import com.courier.modules.company.application.BranchService;
import com.courier.modules.company.application.UserService;
import com.courier.modules.company.domain.Branch;
import com.courier.modules.company.domain.BranchType;
import com.courier.modules.company.domain.User;
import com.courier.modules.pdp.application.PdpEarningService;
import com.courier.modules.pickup.application.PickupService.SaveCommand;
import com.courier.modules.pickup.domain.PickupRequest;
import com.courier.modules.pickup.domain.PickupRequestRepository;
import com.courier.modules.pickup.domain.PickupStatus;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.security.AuthenticatedUser;
import com.courier.shared.security.Roles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PickupServiceImplTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID PDP_USER = UUID.randomUUID();
    private static final UUID PDP = UUID.randomUUID();
    private static final UUID OTHER_PDP = UUID.randomUUID();

    @Mock private PickupRequestRepository pickups;
    @Mock private BranchService branchService;
    @Mock private UserService userService;
    @Mock private AuditService auditService;
    @Mock private PdpEarningService earningService;
    @Mock private com.courier.modules.company.application.BranchPincodeMappingService pincodeMappings;
    @Mock private com.courier.modules.districtfreight.domain.PincodeCoverageLookupPort coverage;
    @Mock private com.courier.modules.company.domain.BranchRepository branches;

    private PickupServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PickupServiceImpl(pickups, branchService, userService, auditService, earningService, pincodeMappings, coverage, branches);
        CompanyContext.setCompanyId(COMPANY);
        when(pickups.save(any(PickupRequest.class))).thenAnswer(i -> {
            PickupRequest p = i.getArgument(0);
            if (p.getId() == null) {
                p.setId(UUID.randomUUID());
            }
            return p;
        });
        when(pickups.countCreatedBetween(any(), any(), any())).thenReturn(0L);
        when(branchService.getById(PDP)).thenReturn(partner(PDP, BranchType.PICKUP_DELIVERY_PARTNER, true));
        when(branches.findByIdWithinCompany(PDP, COMPANY)).thenReturn(Optional.of(partner(PDP, BranchType.PICKUP_DELIVERY_PARTNER, true)));
        when(branchService.getById(OTHER_PDP)).thenReturn(partner(OTHER_PDP, BranchType.PICKUP_DELIVERY_PARTNER, true));
        when(branches.findByIdWithinCompany(OTHER_PDP, COMPANY)).thenReturn(Optional.of(partner(OTHER_PDP, BranchType.PICKUP_DELIVERY_PARTNER, true)));
        User pdpUser = mock(User.class);
        when(pdpUser.getBranchId()).thenReturn(PDP);
        when(userService.getById(PDP_USER)).thenReturn(pdpUser);
        as(ADMIN, Roles.COMPANY_ADMIN);
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    private static Branch partner(UUID id, BranchType type, boolean active) {
        Branch b = Branch.builder().branchCode("B-" + id.toString().substring(0, 4)).branchName("Partner")
                .branchType(type).build();
        b.setId(id);
        if (!active) {
            b.deactivate();
        }
        return b;
    }

    private void as(UUID userId, String role) {
        AuthenticatedUser principal = new AuthenticatedUser(userId, COMPANY, "u@test", Set.of(role), "jti");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.authorities()));
    }

    private static SaveCommand cmd(UUID pdp) {
        return new SaveCommand(null, "Ravi", "9000000001", "12 MG Road", "Pune", null, "411001", 2,
                new BigDecimal("12"), null, null, null, pdp, null);
    }

    private PickupRequest created(UUID pdp) {
        PickupRequest p = service.create(cmd(pdp));
        when(pickups.findByIdWithinCompany(eq(p.getId()), eq(COMPANY))).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    @DisplayName("create without a partner is REQUESTED; with a partner it is ASSIGNED")
    void createStates() {
        assertThat(service.create(cmd(null)).getStatus()).isEqualTo(PickupStatus.REQUESTED);
        PickupRequest assigned = service.create(cmd(PDP));
        assertThat(assigned.getStatus()).isEqualTo(PickupStatus.ASSIGNED);
        assertThat(assigned.getPdpBranchId()).isEqualTo(PDP);
        assertThat(assigned.getPickupNo()).startsWith("PU-").endsWith("-0001");
    }

    @Test
    @DisplayName("only a Pickup-Delivery Partner can be assigned")
    void assignRequiresPartner() {
        UUID plain = UUID.randomUUID();
        when(branchService.getById(plain)).thenReturn(partner(plain, BranchType.BRANCH, true));
        when(branches.findByIdWithinCompany(plain, COMPANY)).thenReturn(Optional.of(partner(plain, BranchType.BRANCH, true)));
        PickupRequest p = created(null);

        assertThatThrownBy(() -> service.assign(p.getId(), plain))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("not a Pickup-Delivery Partner");
    }

    @Test
    @DisplayName("an inactive partner cannot be assigned")
    void inactivePartner() {
        UUID dead = UUID.randomUUID();
        when(branchService.getById(dead)).thenReturn(partner(dead, BranchType.PICKUP_DELIVERY_PARTNER, false));
        when(branches.findByIdWithinCompany(dead, COMPANY)).thenReturn(Optional.of(partner(dead, BranchType.PICKUP_DELIVERY_PARTNER, false)));
        PickupRequest p = created(null);

        assertThatThrownBy(() -> service.assign(p.getId(), dead))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("inactive");
    }

    @Test
    @DisplayName("full happy path: assign, accept, start, complete, link shipment")
    void happyPath() {
        PickupRequest p = created(null);
        service.assign(p.getId(), PDP);
        as(PDP_USER, Roles.BRANCH_MANAGER);

        service.accept(p.getId());
        service.start(p.getId());
        service.complete(p.getId(), 2, new BigDecimal("11.5"));
        assertThat(p.getStatus()).isEqualTo(PickupStatus.PICKED_UP);
        assertThat(p.getActualWeightKg()).isEqualByComparingTo("11.5");

        UUID shipment = UUID.randomUUID();
        service.linkShipment(p.getId(), shipment, "CP-1");
        assertThat(p.getStatus()).isEqualTo(PickupStatus.BOOKED);
        assertThat(p.getShipmentId()).isEqualTo(shipment);
        org.mockito.Mockito.verify(earningService).recordPickup(eq(PDP), eq(p.getId()), eq(p.getPickupNo()),
                eq("411001"), eq(new BigDecimal("11.5")), any());
    }

    @Test
    @DisplayName("a pickup that already has its portal shipment becomes BOOKED when collected, with no second booking")
    void collectedPortalPickupIsBookedByItself() {
        PickupRequest p = created(PDP);
        UUID shipment = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        service.attachPortalShipment(p.getId(), shipment, "PUN-000007", branch);
        assertThat(p.getStatus()).isEqualTo(PickupStatus.ASSIGNED);
        assertThat(p.getBookingBranchId()).isEqualTo(branch);

        as(PDP_USER, Roles.BRANCH_MANAGER);
        service.accept(p.getId());
        service.start(p.getId());
        service.complete(p.getId(), 1, new BigDecimal("3"));

        assertThat(p.getStatus()).isEqualTo(PickupStatus.BOOKED);
        assertThat(p.getShipmentNo()).isEqualTo("PUN-000007");
        org.mockito.Mockito.verify(earningService).recordPickup(eq(PDP), eq(p.getId()), eq(p.getPickupNo()),
                eq("411001"), eq(new BigDecimal("3")), any());
    }

    @Test
    @DisplayName("the manager of the branch a pickup belongs to can assign it to a partner; another branch's manager cannot")
    void owningBranchAssigns() {
        UUID branch = UUID.randomUUID();
        UUID otherBranch = UUID.randomUUID();
        UUID manager = UUID.randomUUID();
        UUID otherManager = UUID.randomUUID();
        when(branchService.getById(branch)).thenReturn(partner(branch, BranchType.BRANCH, true));
        when(branches.findByIdWithinCompany(branch, COMPANY)).thenReturn(Optional.of(partner(branch, BranchType.BRANCH, true)));
        when(branchService.getById(otherBranch)).thenReturn(partner(otherBranch, BranchType.BRANCH, true));
        when(branches.findByIdWithinCompany(otherBranch, COMPANY)).thenReturn(Optional.of(partner(otherBranch, BranchType.BRANCH, true)));
        User u1 = mock(User.class);
        when(u1.getBranchId()).thenReturn(branch);
        when(userService.getById(manager)).thenReturn(u1);
        User u2 = mock(User.class);
        when(u2.getBranchId()).thenReturn(otherBranch);
        when(userService.getById(otherManager)).thenReturn(u2);

        PickupRequest p = created(null);
        service.attachPortalShipment(p.getId(), null, null, branch);
        // BranchService hides the partner branch from a branch manager; assigning must not depend on it.
        when(branchService.getById(PDP)).thenThrow(new com.courier.shared.exception.ResourceNotFoundException("Branch", PDP));

        as(otherManager, Roles.BRANCH_MANAGER);
        assertThatThrownBy(() -> service.assign(p.getId(), PDP)).hasMessageContaining("Only the branch");
        assertThatThrownBy(() -> service.get(p.getId())).isInstanceOf(com.courier.shared.exception.ResourceNotFoundException.class);

        as(manager, Roles.BRANCH_MANAGER);
        assertThat(service.get(p.getId())).isSameAs(p);
        service.assign(p.getId(), PDP);
        assertThat(p.getStatus()).isEqualTo(PickupStatus.ASSIGNED);
        assertThat(p.getPdpBranchId()).isEqualTo(PDP);
    }

    @Test
    @DisplayName("a partner cannot work a pickup assigned to another partner")
    void foreignPartnerBlocked() {
        PickupRequest p = created(OTHER_PDP);
        as(PDP_USER, Roles.BRANCH_MANAGER);

        assertThatThrownBy(() -> service.accept(p.getId()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("not assigned to your branch");
    }

    @Test
    @DisplayName("states cannot be skipped: no complete before start")
    void cannotSkip() {
        PickupRequest p = created(PDP);

        assertThatThrownBy(() -> service.complete(p.getId(), 1, BigDecimal.ONE))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("cannot become");
    }

    @Test
    @DisplayName("reject returns the pickup to the pool and clears the partner")
    void rejectReturnsToPool() {
        PickupRequest p = created(PDP);
        as(PDP_USER, Roles.BRANCH_MANAGER);

        service.reject(p.getId(), "No rider free");

        assertThat(p.getStatus()).isEqualTo(PickupStatus.REQUESTED);
        assertThat(p.getPdpBranchId()).isNull();
        assertThat(p.getRejectReason()).isEqualTo("No rider free");
    }

    @Test
    @DisplayName("reject, fail and cancel all need a reason")
    void reasonsRequired() {
        PickupRequest p = created(PDP);
        assertThatThrownBy(() -> service.cancel(p.getId(), " ")).isInstanceOf(BusinessRuleException.class);
        as(PDP_USER, Roles.BRANCH_MANAGER);
        assertThatThrownBy(() -> service.reject(p.getId(), "")).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("a collected pickup cannot be cancelled")
    void cannotCancelAfterCollection() {
        PickupRequest p = created(PDP);
        service.accept(p.getId());
        service.start(p.getId());
        service.complete(p.getId(), 1, BigDecimal.TEN);

        assertThatThrownBy(() -> service.cancel(p.getId(), "changed mind"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("cannot become");
    }

    @Test
    @DisplayName("invalid pincode and mobile are refused")
    void validation() {
        assertThatThrownBy(() -> service.create(new SaveCommand(null, "Ravi", "9000000001", "addr", null, null,
                "41100", 1, null, null, null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("6 digits");
        assertThatThrownBy(() -> service.create(new SaveCommand(null, "Ravi", "abc", "addr", null, null,
                "411001", 1, null, null, null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("mobile");
    }

    @Test
    @DisplayName("suggests the mapped partner for the pickup's pincode, only if it is an active PDP")
    void suggestsMappedPartner() {
        PickupRequest p = created(null);
        UUID pincodeId = UUID.randomUUID();
        when(coverage.findByPincode("411001")).thenReturn(Optional.of(new com.courier.modules.districtfreight.domain
                .PincodeCoverageLookupPort.CoverageRef(pincodeId, "411001", true, false, UUID.randomUUID(), "P", "Pune", true, "Pune", UUID.randomUUID())));
        when(pincodeMappings.findBranchForPincode(pincodeId)).thenReturn(Optional.of(partner(PDP, BranchType.PICKUP_DELIVERY_PARTNER, true)));

        assertThat(service.suggestPartner(p.getId())).contains(PDP);

        when(pincodeMappings.findBranchForPincode(pincodeId)).thenReturn(Optional.of(partner(PDP, BranchType.BRANCH, true)));
        assertThat(service.suggestPartner(p.getId())).isEmpty();
    }
}
