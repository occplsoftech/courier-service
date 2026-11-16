package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customer.application.CustomerPortalLookup;
import com.courier.modules.customerportal.rates.domain.CourierProviderAdapter;
import com.courier.modules.customerportal.rates.domain.CustomerCourierAccess;
import com.courier.modules.customerportal.rates.domain.CustomerCourierAccessRepository;
import com.courier.modules.customerportal.rates.domain.ProviderResult;
import com.courier.modules.customerportal.rates.domain.RateRequest;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.exception.BusinessRuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomerCourierAccessServiceTest {

    private final UUID company = UUID.randomUUID();
    private final UUID customer = UUID.randomUUID();
    private final CustomerCourierAccessRepository repository = mock(CustomerCourierAccessRepository.class);
    private final CustomerCourierAccessService service = new CustomerCourierAccessService(repository,
            List.of(adapter("OCCPL_NETWORK"), adapter("DELHIVERY"), adapter("SHIPROCKET")), mock(CustomerPortalLookup.class),
            mock(AuditService.class));

    private static CourierProviderAdapter adapter(String code) {
        return new CourierProviderAdapter() {
            @Override public String code() { return code; }
            @Override public String displayName() { return code; }
            @Override public boolean external() { return !code.equals("OCCPL_NETWORK"); }
            @Override public boolean configured() { return true; }
            @Override public ProviderResult getRates(RateRequest r) { return null; }
        };
    }

    @Test
    @DisplayName("nothing assigned: the customer sees OCCPL Network only")
    void defaultIsOccpl() {
        when(repository.findCodes(company, customer)).thenReturn(List.of());

        assertThat(service.allowedCodes(company, customer)).isEqualTo(Set.of("OCCPL_NETWORK"));
    }

    @Test
    @DisplayName("assigned couriers are exactly what the customer sees; OCCPL Network only if it is one of them")
    void assignedOnly() {
        when(repository.findCodes(company, customer)).thenReturn(List.of("DELHIVERY"));

        assertThat(service.allowedCodes(company, customer)).isEqualTo(Set.of("DELHIVERY"));
    }

    @Test
    @DisplayName("an unknown courier code is refused before anything is changed")
    void unknownCode() {
        // the caller check (COMPANY_ADMIN) is on the method; with no security context the code check is reached through the lookup
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> service.save(customer, List.of("NOPE"))).isInstanceOf(RuntimeException.class);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any(CustomerCourierAccess.class));
    }

    // ------------------------------------------------------------------ branch level (13.2)

    private final UUID branch = UUID.randomUUID();
    private final UUID otherBranch = UUID.randomUUID();
    private final UUID manager = UUID.randomUUID();
    private final com.courier.modules.customerportal.rates.domain.BranchCourierAccessRepository branchRepo =
            mock(com.courier.modules.customerportal.rates.domain.BranchCourierAccessRepository.class);
    private final CustomerPortalLookup lookup = mock(CustomerPortalLookup.class);
    private final com.courier.modules.company.application.UserService users = mock(com.courier.modules.company.application.UserService.class);
    private final com.courier.modules.company.domain.BranchRepository branches = mock(com.courier.modules.company.domain.BranchRepository.class);

    private CustomerCourierAccessService branchLevel(UUID homeBranch) {
        CustomerCourierAccessService svc = new CustomerCourierAccessService(repository,
                List.of(adapter("OCCPL_NETWORK"), adapter("DELHIVERY"), adapter("SHIPROCKET")), lookup, mock(AuditService.class));
        svc.setBranchLevel(branchRepo, users, branches);
        com.courier.modules.customer.domain.Customer c = com.courier.modules.customer.domain.Customer.builder().homeBranchId(homeBranch).build();
        when(lookup.findActiveBusinessCustomer(company, customer)).thenReturn(java.util.Optional.of(c));
        com.courier.modules.company.domain.Branch b = com.courier.modules.company.domain.Branch.builder().branchName("Pune").build();
        b.setId(branch);
        when(branches.findByIdWithinCompany(branch, company)).thenReturn(java.util.Optional.of(b));
        com.courier.modules.company.domain.Branch o = com.courier.modules.company.domain.Branch.builder().branchName("Mumbai").build();
        o.setId(otherBranch);
        when(branches.findByIdWithinCompany(otherBranch, company)).thenReturn(java.util.Optional.of(o));
        return svc;
    }

    private void signedInAs(String role, UUID ownBranch) {
        var user = new com.courier.shared.security.AuthenticatedUser(manager, company, "m@test", Set.of(role), Set.of(), "jti");
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user, null, user.authorities()));
        com.courier.modules.company.domain.User u = mock(com.courier.modules.company.domain.User.class);
        when(u.getBranchId()).thenReturn(ownBranch);
        when(users.getById(manager)).thenReturn(u);
    }

    @org.junit.jupiter.api.AfterEach
    void clear() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("no list of their own: the customer gets their home branch's couriers; with neither, OCCPL Network only")
    void branchDefault() {
        CustomerCourierAccessService svc = branchLevel(branch);
        when(repository.findCodes(company, customer)).thenReturn(List.of());
        when(branchRepo.findCodes(company, branch)).thenReturn(List.of("OCCPL_NETWORK", "DELHIVERY"));
        assertThat(svc.allowedCodes(company, customer)).isEqualTo(Set.of("OCCPL_NETWORK", "DELHIVERY"));

        when(branchRepo.findCodes(company, branch)).thenReturn(List.of());
        assertThat(svc.allowedCodes(company, customer)).isEqualTo(Set.of("OCCPL_NETWORK"));
    }

    @Test
    @DisplayName("the customer's own list wins over their branch's")
    void ownListWins() {
        CustomerCourierAccessService svc = branchLevel(branch);
        when(repository.findCodes(company, customer)).thenReturn(List.of("SHIPROCKET"));
        when(branchRepo.findCodes(company, branch)).thenReturn(List.of("DELHIVERY"));
        assertThat(svc.allowedCodes(company, customer)).isEqualTo(Set.of("SHIPROCKET"));
    }

    @Test
    @DisplayName("the access view says where the couriers come from and names the branch")
    void viewShowsSource() {
        CustomerCourierAccessService svc = branchLevel(branch);
        signedInAs("COMPANY_ADMIN", null);
        when(repository.findCodes(company, customer)).thenReturn(List.of());
        when(branchRepo.findCodes(company, branch)).thenReturn(List.of("DELHIVERY"));
        var v = svc.view(customer);
        assertThat(v.customised()).isFalse();
        assertThat(v.source()).isEqualTo(CustomerCourierAccessService.Source.BRANCH);
        assertThat(v.homeBranchName()).isEqualTo("Pune");
        assertThat(v.couriers()).filteredOn(CustomerCourierAccessService.CourierOption::enabled)
                .extracting(CustomerCourierAccessService.CourierOption::code).containsExactly("DELHIVERY");
    }

    @Test
    @DisplayName("a branch manager sets their own branch's couriers, not another branch's, and only their own customers'")
    void branchManagerLimits() {
        CustomerCourierAccessService svc = branchLevel(otherBranch);
        signedInAs("BRANCH_MANAGER", branch);
        when(branchRepo.findCodes(company, branch)).thenReturn(List.of("DELHIVERY"));

        var saved = svc.saveBranch(branch, List.of("delhivery"));
        assertThat(saved.branchName()).isEqualTo("Pune");
        org.mockito.Mockito.verify(branchRepo).deleteForBranch(company, branch);
        org.mockito.Mockito.verify(branchRepo).save(any(com.courier.modules.customerportal.rates.domain.BranchCourierAccess.class));

        assertThatThrownBy(() -> svc.saveBranch(otherBranch, List.of("DELHIVERY")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> svc.save(customer, List.of("DELHIVERY")))   // the customer's home branch is the other branch
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
}
