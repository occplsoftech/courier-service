package com.courier.modules.company.application;

import com.courier.modules.auth.application.UserProvisioningService;
import com.courier.modules.auth.application.UserProvisioningService.NewBranchUserCommand;
import com.courier.modules.auth.application.UserProvisioningService.ProvisionedBranchUser;
import com.courier.modules.company.application.command.CreateBranchCommand;
import com.courier.modules.company.application.command.UpdateBranchCommand;
import com.courier.modules.company.application.geocoding.GeocodingPort;
import com.courier.modules.company.domain.Branch;
import com.courier.modules.company.domain.BranchRepository;
import com.courier.modules.company.domain.BranchStatus;
import com.courier.modules.company.domain.BranchType;
import com.courier.modules.company.domain.Company;
import com.courier.modules.company.domain.CompanyRepository;
import com.courier.modules.company.domain.CompanyRole;
import com.courier.modules.company.domain.CompanyUserRepository;
import com.courier.modules.company.domain.DefaultRoleCatalog;
import com.courier.modules.company.domain.User;
import com.courier.modules.company.domain.UserStatus;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.DuplicateResourceException;
import com.courier.shared.exception.ForbiddenException;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.AuthenticatedUser;
import com.courier.shared.security.Roles;
import com.courier.shared.company.CompanyContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Branch rules, with repositories and the audit trail mocked. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BranchServiceImplTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID CALLER = UUID.randomUUID();

    private static final UUID BRANCH_USER = UUID.randomUUID();
    private static final UUID BRANCH_MANAGER_ROLE = UUID.randomUUID();
    private static final String GENERATED = "Gp7#tKm2Xq9wZa";

    @Mock private BranchRepository repository;
    @Mock private CompanyUserRepository userRepository;
    @Mock private CompanyRepository companyRepository;
    @Mock private UserProvisioningService userProvisioningService;
    @Mock private BranchRoleProvisioningService branchRoleProvisioningService;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private GeocodingPort geocodingPort;

    private BranchServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BranchServiceImpl(repository, userRepository, companyRepository,
                userProvisioningService, branchRoleProvisioningService, auditService,
                eventPublisher, geocodingPort);
        CompanyContext.setCompanyId(TENANT);
        planted(CALLER, Roles.COMPANY_ADMIN);

        when(geocodingPort.geocode(any())).thenReturn(Optional.empty());
        when(repository.save(any(Branch.class))).thenAnswer(i -> i.getArgument(0));
        when(repository.isCodeTaken(any(), any(), any())).thenReturn(false);

        when(companyRepository.findByCompanyId(TENANT))
                .thenReturn(Optional.of(Company.builder().companyCode("LEGACY_CO").build()));
        // No address is taken, so a derived login needs no suffix.
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
        when(userRepository.findByIdWithinCompany(eq(BRANCH_USER), eq(TENANT)))
                .thenReturn(Optional.of(user(BRANCH_USER)));
        when(userProvisioningService.provisionBranchUser(any())).thenAnswer(i -> {
            NewBranchUserCommand c = i.getArgument(0);
            return new ProvisionedBranchUser(BRANCH_USER, c.email(),
                    c.password() == null ? GENERATED : null);
        });
        when(branchRoleProvisioningService.ensureBranchManagerRole(any(), any()))
                .thenReturn(new BranchRoleProvisioningService.BranchManagerRoleAssignment(
                        branchManagerRole(), false, true));
    }

    /** The company's seeded BRANCH_MANAGER role, as the provisioning service returns it. */
    private static CompanyRole branchManagerRole() {
        CompanyRole role = CompanyRole.builder()
                .roleCode(DefaultRoleCatalog.BRANCH_MANAGER)
                .roleName("Branch Manager")
                .systemRole(true)
                .build();
        role.setId(BRANCH_MANAGER_ROLE);
        role.setCompanyId(TENANT);
        return role;
    }

    private CreateBranchCommand.NewBranchUser branchUser(String email, String password) {
        return new CreateBranchCommand.NewBranchUser(email, null, null, null, password);
    }

    private NewBranchUserCommand provisioned() {
        ArgumentCaptor<NewBranchUserCommand> captor =
                ArgumentCaptor.forClass(NewBranchUserCommand.class);
        verify(userProvisioningService).provisionBranchUser(captor.capture());
        return captor.getValue();
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    private void planted(UUID userId, String... roles) {
        AuthenticatedUser principal = new AuthenticatedUser(
                userId, TENANT, "admin@legacy.test", Set.of(roles), "jti");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.authorities()));
    }

    private CreateBranchCommand createCommand(String code, String name, UUID managerId) {
        return createCommand(code, name, managerId, null);
    }

    private CreateBranchCommand createCommand(String code, String name, UUID managerId,
                                              CreateBranchCommand.NewBranchUser branchUser) {
        return createCommand(code, name, BranchType.BRANCH, managerId, branchUser);
    }

    private CreateBranchCommand createCommand(String code, String name, BranchType type, UUID managerId,
                                              CreateBranchCommand.NewBranchUser branchUser) {
        return new CreateBranchCommand(code, name, type,
                null, null, null, managerId,
                null, null, null, null, "Pune", null, null, "411001",
                null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, branchUser);
    }

    private Branch existing(String code) {
        Branch b = Branch.builder().branchCode(code).branchName("Pune Main")
                .branchType(BranchType.BRANCH).status(BranchStatus.ACTIVE).build();
        b.setCompanyId(TENANT);
        b.setVersion(2L);
        return b;
    }

    private User user(UUID id) {
        User u = User.builder().email("u@legacy.test").passwordHash("h").status(UserStatus.ACTIVE).build();
        u.setId(id);
        u.setCompanyId(TENANT);
        return u;
    }

    // ------------------------------------------------------------------- create

    @Test
    @DisplayName("create normalises and starts ACTIVE")
    void create() {
        Branch saved = service.create(createCommand("  pune main ", "Pune Main", null)).branch();
        assertThat(saved.getBranchCode()).isEqualTo("PUNE_MAIN");
        assertThat(saved.getStatus()).isEqualTo(BranchStatus.ACTIVE);
        verify(auditService).record(eq(AuditAction.BRANCH_CREATED), eq("Branch"), any(), any());
    }

    @Test
    @DisplayName("create provisions the branch's user, places it at the branch and makes it manager")
    void createProvisionsUser() {
        BranchService.BranchCreation created =
                service.create(createCommand("pune_main", "Pune Main", null));

        assertThat(created.userId()).isEqualTo(BRANCH_USER);
        assertThat(created.assignedAsManager()).isTrue();
        assertThat(created.branch().getManagerId()).isEqualTo(BRANCH_USER);
        assertThat(provisioned().roles()).containsExactly(com.courier.modules.auth.domain.Role.BRANCH_MANAGER);

        ArgumentCaptor<User> placed = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(placed.capture());
        assertThat(placed.getValue().getBranchId()).isEqualTo(created.branch().getId());
    }

    @Test
    @DisplayName("create grants the branch's user the company's BRANCH_MANAGER role")
    void createGrantsBranchManagerRole() {
        // The JWT authority and the company role are different things: auth sets the
        // first, and only this makes the account appear in the roles screen holding
        // anything. Before it was wired the branch user held a role that existed nowhere
        // in company_roles.
        BranchService.BranchCreation created =
                service.create(createCommand("pune_main", "Pune Main", null));

        verify(branchRoleProvisioningService).ensureBranchManagerRole(TENANT, BRANCH_USER);
        assertThat(created.branchManagerRoleId()).isEqualTo(BRANCH_MANAGER_ROLE);
        assertThat(created.branchManagerRoleCode()).isEqualTo(DefaultRoleCatalog.BRANCH_MANAGER);
    }

    @Test
    @DisplayName("creating a HUB also grants the account HUB_MANAGER, so Hub Operations shows")
    void createHubGrantsHubManagerRole() {
        service.create(createCommand("pune_hub", "Pune Hub", BranchType.HUB, null, null));

        verify(branchRoleProvisioningService).ensureBranchManagerRole(TENANT, BRANCH_USER);
        verify(branchRoleProvisioningService).ensureHubManagerRole(TENANT, BRANCH_USER);
    }

    @Test
    @DisplayName("creating an ordinary branch does not grant HUB_MANAGER")
    void createBranchDoesNotGrantHubManager() {
        service.create(createCommand("pune_main", "Pune Main", null));

        verify(branchRoleProvisioningService, never()).ensureHubManagerRole(any(), any());
    }

    @Test
    @DisplayName("the role is provisioned for the company the caller is bound to, never one from the request")
    void createGrantsRoleWithinCallersCompany() {
        service.create(createCommand("nagpur", "Nagpur", null));

        ArgumentCaptor<UUID> company = ArgumentCaptor.forClass(UUID.class);
        verify(branchRoleProvisioningService).ensureBranchManagerRole(company.capture(), any());
        assertThat(company.getValue()).isEqualTo(TENANT);
    }

    @Test
    @DisplayName("no branchUser block derives <code>@<company>.local and returns the generated password")
    void createDerivesLogin() {
        BranchService.BranchCreation created =
                service.create(createCommand("pune main", "Pune Main", null));

        assertThat(created.userEmail()).isEqualTo("pune-main@legacy-co.local");
        assertThat(created.temporaryPassword()).isEqualTo(GENERATED);
    }

    @Test
    @DisplayName("a derived address that is taken is suffixed, not failed")
    void createSuffixesTakenDerivedLogin() {
        when(userRepository.findByEmail("pune-main@legacy-co.local"))
                .thenReturn(Optional.of(user(UUID.randomUUID())));

        BranchService.BranchCreation created =
                service.create(createCommand("pune main", "Pune Main", null));

        assertThat(created.userEmail()).isEqualTo("pune-main-2@legacy-co.local");
    }

    @Test
    @DisplayName("a supplied password is used and never echoed back")
    void createHonoursSuppliedCredentials() {
        BranchService.BranchCreation created = service.create(createCommand(
                "pune_main", "Pune Main", null, branchUser("Latur@Legacy.test", "Str0ng#Pass1")));

        assertThat(created.userEmail()).isEqualTo("latur@legacy.test");
        assertThat(created.temporaryPassword()).isNull();
        assertThat(provisioned().password()).isEqualTo("Str0ng#Pass1");
    }

    @Test
    @DisplayName("an explicit manager keeps the branch; the new user is staff, not manager")
    void createKeepsExplicitManager() {
        UUID manager = UUID.randomUUID();
        when(userRepository.findByIdWithinCompany(manager, TENANT)).thenReturn(Optional.of(user(manager)));

        BranchService.BranchCreation created =
                service.create(createCommand("pune_main", "Pune Main", manager));

        assertThat(created.assignedAsManager()).isFalse();
        assertThat(created.branch().getManagerId()).isEqualTo(manager);
    }

    @Test
    @DisplayName("create rejects a duplicate code")
    void createDuplicates() {
        when(repository.isCodeTaken(eq(TENANT), eq("PUNE_MAIN"), isNull())).thenReturn(true);
        assertThatThrownBy(() -> service.create(createCommand("pune_main", "X", null)))
                .isInstanceOf(DuplicateResourceException.class).hasMessageContaining("branchCode");
    }

    @Test
    @DisplayName("create allows a duplicate name")
    void createAllowsDuplicateName() {
        when(repository.isCodeTaken(any(), any(), any())).thenReturn(false);
        assertThatCode(() -> service.create(createCommand("other", "Pune Main", null)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("create rejects a manager who is not a company user")
    void createBadManager() {
        UUID manager = UUID.randomUUID();
        when(userRepository.findByIdWithinCompany(manager, TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(createCommand("pune_main", "Pune Main", manager)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("user of this company");
    }

    @Test
    @DisplayName("create without a bound company is refused")
    void createNoCompany() {
        CompanyContext.clear();
        assertThatThrownBy(() -> service.create(createCommand("pune_main", "Pune Main", null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("No company is bound");
    }

    @Test
    @DisplayName("create geocodes when the administrator leaves latitude/longitude blank")
    void createGeocodesWhenBlank() {
        when(geocodingPort.geocode(any())).thenReturn(Optional.of(
                new GeocodingPort.Coordinates(new BigDecimal("18.5204300"), new BigDecimal("73.8567400"))));

        Branch saved = service.create(createCommand("pune_main", "Pune Main", null)).branch();

        assertThat(saved.getLatitude()).isEqualByComparingTo("18.520430");
        assertThat(saved.getLongitude()).isEqualByComparingTo("73.856740");
    }

    @Test
    @DisplayName("create never geocodes when latitude/longitude were supplied")
    void createSkipsGeocodeWhenSupplied() {
        CreateBranchCommand command = new CreateBranchCommand(
                "pune_main", "Pune Main", BranchType.BRANCH,
                null, null, null, null,
                null, null, null, null, "Pune", null, null, "411001",
                new BigDecimal("18.0"), new BigDecimal("73.0"), null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);

        service.create(command);

        verify(geocodingPort, never()).geocode(any());
    }

    // ------------------------------------------------------------------- update

    private UpdateBranchCommand updateCommand(String name, Long version) {
        return new UpdateBranchCommand("PUNE_MAIN", name, BranchType.BRANCH,
                null, null, null, null, null, null, null, "Mumbai", null, null, "400001",
                null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, version);
    }

    @Test
    @DisplayName("update applies changes when the version matches")
    void update() {
        Branch b = existing("PUNE_MAIN");
        when(repository.findByIdWithinCompany(b.getId(), TENANT)).thenReturn(Optional.of(b));

        Branch saved = service.update(b.getId(), updateCommand("Pune Central", 2L));
        assertThat(saved.getBranchName()).isEqualTo("Pune Central");
        assertThat(saved.getCity()).isEqualTo("Mumbai");
    }

    @Test
    @DisplayName("update geocodes when the request leaves latitude/longitude blank, same as create")
    void updateGeocodesWhenBlank() {
        Branch b = existing("PUNE_MAIN");
        when(repository.findByIdWithinCompany(b.getId(), TENANT)).thenReturn(Optional.of(b));
        when(geocodingPort.geocode(any())).thenReturn(Optional.of(
                new GeocodingPort.Coordinates(new BigDecimal("19.0760000"), new BigDecimal("72.8777000"))));

        // updateCommand() carries no latitude/longitude but does carry city "Mumbai" /
        // postal "400001" — exactly the shape a real edit-without-coordinates request has.
        Branch saved = service.update(b.getId(), updateCommand("Pune Central", 2L));

        assertThat(saved.getLatitude()).isEqualByComparingTo("19.076000");
        assertThat(saved.getLongitude()).isEqualByComparingTo("72.877700");
    }

    @Test
    @DisplayName("update never geocodes when latitude/longitude were supplied, including to clear them")
    void updateSkipsGeocodeWhenSupplied() {
        Branch b = existing("PUNE_MAIN");
        b.setLatitude(new BigDecimal("18.000000"));
        b.setLongitude(new BigDecimal("73.000000"));
        when(repository.findByIdWithinCompany(b.getId(), TENANT)).thenReturn(Optional.of(b));

        UpdateBranchCommand command = new UpdateBranchCommand(
                "PUNE_MAIN", "Pune Central", BranchType.BRANCH,
                null, null, null, null, null, null, null, "Mumbai", null, null, "400001",
                new BigDecimal("21.0"), new BigDecimal("79.0"), null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, 2L);

        service.update(b.getId(), command);

        verify(geocodingPort, never()).geocode(any());
    }

    @Test
    @DisplayName("update rejects a stale version")
    void updateStale() {
        Branch b = existing("PUNE_MAIN");
        when(repository.findByIdWithinCompany(b.getId(), TENANT)).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.update(b.getId(), updateCommand("X", 1L)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a branch manager may update only the branch they manage")
    void managerUpdatesOwnOnly() {
        Branch own = existing("PUNE_MAIN");
        own.setManagerId(CALLER);
        Branch other = existing("MUMBAI");
        other.setManagerId(UUID.randomUUID());
        planted(CALLER, Roles.BRANCH_MANAGER);
        when(repository.findByIdWithinCompany(own.getId(), TENANT)).thenReturn(Optional.of(own));
        when(repository.findByIdWithinCompany(other.getId(), TENANT)).thenReturn(Optional.of(other));

        // Their own branch: allowed (proceeds to the version check).
        service.update(own.getId(), updateCommand("Pune Central", 2L));

        // Another branch of the same company: forbidden.
        assertThatThrownBy(() -> service.update(other.getId(), updateCommand("X", 2L)))
                .isInstanceOf(ForbiddenException.class);
    }

    // ---------------------------------------------------------------- lifecycle

    @Test
    @DisplayName("activate and deactivate are idempotent")
    void lifecycleIdempotent() {
        Branch b = existing("PUNE_MAIN");
        when(repository.findByIdWithinCompany(b.getId(), TENANT)).thenReturn(Optional.of(b));

        service.activate(b.getId());  // already ACTIVE
        verify(repository, never()).save(any());

        b.deactivate();
        service.deactivate(b.getId());  // already INACTIVE
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("delete is soft and deactivates")
    void deleteSoft() {
        Branch b = existing("PUNE_MAIN");
        when(repository.findByIdWithinCompany(b.getId(), TENANT)).thenReturn(Optional.of(b));

        service.delete(b.getId());

        assertThat(b.isDeleted()).isTrue();
        assertThat(b.getStatus()).isEqualTo(BranchStatus.INACTIVE);
        verify(repository).save(b);
        verify(repository, never()).delete(any(Branch.class));
    }

    // ------------------------------------------------------------------- manager

    @Test
    @DisplayName("assign manager validates the user and records the previous one")
    void assignManager() {
        Branch b = existing("PUNE_MAIN");
        UUID manager = UUID.randomUUID();
        when(repository.findByIdWithinCompany(b.getId(), TENANT)).thenReturn(Optional.of(b));
        when(userRepository.findByIdWithinCompany(manager, TENANT)).thenReturn(Optional.of(user(manager)));

        Branch saved = service.assignManager(b.getId(), manager);

        assertThat(saved.getManagerId()).isEqualTo(manager);
        verify(auditService).record(eq(AuditAction.BRANCH_MANAGER_ASSIGNED), any(), any(), any());
    }

    // ----------------------------------------------------------------------- hub

    private Branch hub(String code) {
        Branch h = existing(code);
        h.setBranchType(BranchType.HUB);
        h.setId(UUID.randomUUID());
        return h;
    }

    @Test
    @DisplayName("assign hub stores the hub id and audits the change")
    void assignHub() {
        Branch b = existing("PUNE_MAIN");
        Branch h = hub("HUB_ONE");
        when(repository.findByIdWithinCompany(b.getId(), TENANT)).thenReturn(Optional.of(b));
        when(repository.findByIdWithinCompany(h.getId(), TENANT)).thenReturn(Optional.of(h));

        Branch saved = service.assignHub(b.getId(), h.getId());

        assertThat(saved.getAssignedHubId()).isEqualTo(h.getId());
        verify(auditService).record(eq(AuditAction.BRANCH_HUB_ASSIGNED), any(), any(), any());
    }

    @Test
    @DisplayName("courier pickup location is trimmed and saved, and blank clears it")
    void courierPickupLocation() {
        Branch b = existing("PUNE_MAIN");
        when(repository.findByIdWithinCompany(b.getId(), TENANT)).thenReturn(Optional.of(b));

        assertThat(service.setCourierPickupLocation(b.getId(), "  PUNE-HUB ").getCourierPickupLocation()).isEqualTo("PUNE-HUB");
        assertThat(service.setCourierPickupLocation(b.getId(), "  ").getCourierPickupLocation()).isNull();
        verify(auditService, org.mockito.Mockito.times(2)).record(eq(AuditAction.BRANCH_UPDATED), any(), any(), any());
    }

    @Test
    @DisplayName("assign hub rejects a target that is not a HUB branch")
    void assignHubRejectsNonHub() {
        Branch b = existing("PUNE_MAIN");
        Branch other = existing("OTHER");
        other.setId(UUID.randomUUID());
        when(repository.findByIdWithinCompany(b.getId(), TENANT)).thenReturn(Optional.of(b));
        when(repository.findByIdWithinCompany(other.getId(), TENANT)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.assignHub(b.getId(), other.getId()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("not a hub");
        assertThat(b.getAssignedHubId()).isNull();
        verify(repository, never()).save(any(Branch.class));
    }

    @Test
    @DisplayName("assign hub rejects an inactive hub")
    void assignHubRejectsInactiveHub() {
        Branch b = existing("PUNE_MAIN");
        Branch h = hub("HUB_ONE");
        h.setStatus(BranchStatus.INACTIVE);
        when(repository.findByIdWithinCompany(b.getId(), TENANT)).thenReturn(Optional.of(b));
        when(repository.findByIdWithinCompany(h.getId(), TENANT)).thenReturn(Optional.of(h));

        assertThatThrownBy(() -> service.assignHub(b.getId(), h.getId()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("not active");
        verify(repository, never()).save(any(Branch.class));
    }

    @Test
    @DisplayName("assign hub rejects a branch being its own hub")
    void assignHubRejectsSelf() {
        Branch h = hub("HUB_ONE");
        when(repository.findByIdWithinCompany(h.getId(), TENANT)).thenReturn(Optional.of(h));

        assertThatThrownBy(() -> service.assignHub(h.getId(), h.getId()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("own hub");
        verify(repository, never()).save(any(Branch.class));
    }

    @Test
    @DisplayName("assign hub rejects a hub outside the company")
    void assignHubRejectsForeignHub() {
        Branch b = existing("PUNE_MAIN");
        UUID foreign = UUID.randomUUID();
        when(repository.findByIdWithinCompany(b.getId(), TENANT)).thenReturn(Optional.of(b));
        when(repository.findByIdWithinCompany(foreign, TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assignHub(b.getId(), foreign))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).save(any(Branch.class));
    }

    @Test
    @DisplayName("assign hub with null clears it, and reassigning replaces it")
    void assignHubClearAndReplace() {
        Branch b = existing("PUNE_MAIN");
        Branch h1 = hub("HUB_ONE");
        Branch h2 = hub("HUB_TWO");
        when(repository.findByIdWithinCompany(b.getId(), TENANT)).thenReturn(Optional.of(b));
        when(repository.findByIdWithinCompany(h1.getId(), TENANT)).thenReturn(Optional.of(h1));
        when(repository.findByIdWithinCompany(h2.getId(), TENANT)).thenReturn(Optional.of(h2));

        service.assignHub(b.getId(), h1.getId());
        service.assignHub(b.getId(), h2.getId());
        assertThat(b.getAssignedHubId()).isEqualTo(h2.getId());

        service.assignHub(b.getId(), null);
        assertThat(b.getAssignedHubId()).isNull();
    }

    @Test
    @DisplayName("assign hub is COMPANY_ADMIN only")
    void assignHubIsAdminOnly() throws Exception {
        var pre = BranchServiceImpl.class.getMethod("assignHub", UUID.class, UUID.class)
                .getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
        assertThat(pre).isNotNull();
        assertThat(pre.value()).contains("COMPANY_ADMIN");
    }

    // --------------------------------------------------------------- assign users

    @Test
    @DisplayName("assign users places company users, skips those already there, rejects foreign ids")
    void assignUsers() {
        Branch b = existing("PUNE_MAIN");
        UUID here = UUID.randomUUID();
        UUID already = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        when(repository.findByIdWithinCompany(b.getId(), TENANT)).thenReturn(Optional.of(b));

        User hereUser = user(here);
        User alreadyUser = user(already);
        alreadyUser.assignBranch(b.getId());
        when(userRepository.findAllByIdInWithinCompany(any(), eq(TENANT)))
                .thenReturn(List.of(hereUser, alreadyUser));
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        BranchService.AssignUsersResult result =
                service.assignUsers(b.getId(), List.of(here, already, foreign));

        assertThat(result.assigned()).containsExactly(here);
        assertThat(result.skipped()).containsExactly(already);
        assertThat(result.rejected()).containsExactly(foreign);
        assertThat(hereUser.getBranchId()).isEqualTo(b.getId());
    }

    // --------------------------------------------------------------------- read

    @Test
    @DisplayName("getById on a foreign branch is a 404")
    void getMissing() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdWithinCompany(id, TENANT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById(id)).isInstanceOf(ResourceNotFoundException.class);
    }
}
